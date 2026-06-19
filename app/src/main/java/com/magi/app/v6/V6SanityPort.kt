package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.MagiState

/**
 * Deeper native port of V6 Web diagnostics: detectImpossibleWishes(),
 * buildLoadDataBitSummary(), buildShiftCountDiagnostic(), and the practical parts
 * of buildSanityCheck().  It intentionally returns structured Kotlin data so both
 * Compose and tests can consume the same result.
 */
data class ImpossibleWish(
    val staffIndex: Int,
    val dayIndex: Int,
    val staffName: String,
    val groupSymbol: String,
    val shiftSymbol: String,
    val reason: String,
)

/** 設定ミスの種別。UI でアイコン/誘導先タブを切り替えるのに使う。 */
enum class IssueKind { WISH, CONSTRAINT, DEMAND, RANGE }

/**
 * [設定ミスの誘導修正] 制約・希望の設定間違いを「どこが・なぜ・どう直すか」で人間に提示するための構造化項目。
 * - where: 場所（例「佐藤 7/3 希望『日勤』」「連続パターン『Dﾃ→A4』」）
 * - problem: 何が問題か（平易な日本語）
 * - fix: 具体的な直し方（どの画面で何をするか）
 */
/**
 * [ワンタップ修正] カード上で画面遷移・スクロールなしに直せる安全な単一操作の種類。
 * NONE = 自動修正不可（編集画面へ誘導）。それ以外はカードのボタン1つで適用→自動再診断。
 */
enum class SettingFixAction { NONE, REMOVE_WISH, DELETE_DUP_SEQ, ZERO_RANGE_LO, CLAMP_RANGE_LO, CAP_DEMAND }

data class SettingIssue(
    val kind: IssueKind,
    val where: String,
    val problem: String,
    val fix: String,
    // --- ワンタップ修正（少ないスクロール・少ないボタン操作のための直接修正情報） ---
    val action: SettingFixAction = SettingFixAction.NONE,
    val actionLabel: String = "",          // ボタン文言（空=ワンタップ不可で編集画面へ）
    val wishKey: String? = null,           // REMOVE_WISH: "i,j"
    val seqFamily: String? = null,         // DELETE_DUP_SEQ: c3 / c3n / c3m / c3mn
    val seqKey: String? = null,            // DELETE_DUP_SEQ: "Dﾃ→A4"（→区切り・非空のみ）
    val rangeKey: String? = null,          // ZERO/CLAMP_RANGE_LO: "i,k"
    val newLo: String? = null,             // ZERO/CLAMP_RANGE_LO: 新しい下限
    val demandShiftIdx: Int? = null,       // CAP_DEMAND: シフトidx
    val demandCap: Int? = null,            // CAP_DEMAND: 担当可能人数（上限）
)

data class ShiftCountDiagnostic(
    val staffIndex: Int,
    val staffName: String,
    val shiftSymbol: String,
    val count: Int,
    val lo: Int?,
    val hi: Int?,
    val status: String,
)

data class V6SanityReport(
    val ok: Boolean,
    val warns: List<String>,
    val notes: List<String>,
    val loadDataBitSummary: String,
    val loadDataBitDetails: List<String>,
    val shiftCountDiagnostics: List<ShiftCountDiagnostic>,
    val impossibleWishes: List<ImpossibleWish>,
    val duplicateSeqConstraints: List<String>,
    val guidance: List<SettingIssue>,
)

object V6SanityPort {
    fun build(state: MagiState, schedule: Array<IntArray> = state.schedule.toIntArray2D()): V6SanityReport {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        val warns = ArrayList<String>()
        val notes = ArrayList<String>()

        val invalidAssignments = invalidAssignmentCells(state, p, s)
        if (invalidAssignments.isNotEmpty()) {
            warns.add("担当不可または範囲外の配置が ${invalidAssignments.size} セルあります")
        }

        val impossible = detectImpossibleWishes(state, p)
        if (impossible.isNotEmpty()) {
            warns.add("実現不能な希望シフトが ${impossible.size} 件あります")
        }

        val dup = findDuplicateSeqConstraints(state)
        if (dup.isNotEmpty()) warns.add("連続パターン制約の重複が ${dup.size} 件あります")

        val badRanges = badStaffRanges(state, p)
        if (badRanges > 0) warns.add("staffRange の範囲外キーまたは lo>hi が ${badRanges} 件あります")

        val impossibleDemand = impossibleDemandDays(state, p)
        if (impossibleDemand.isNotEmpty()) {
            val head = ArrayList<String>()
            val lim = minOf(4, impossibleDemand.size)
            var idx = 0
            while (idx < lim) {
                head.add(impossibleDemand[idx])
                idx++
            }
            val suffix = if (impossibleDemand.size > 4) " …" else ""
            warns.add("担当可能人数を超える需要があります: ${head.joinToString(" / ")}$suffix")
        }

        var aptSet = 0
        for (row in state.groupShiftApt) {
            for (cell in row) {
                if (cell.trim().isNotEmpty()) aptSet++
            }
        }
        notes.add("groupShiftApt 適切回数: ${aptSet} 件")
        notes.add("shifts=${p.K} groups=${p.G} staff=${p.S} days=${p.T}")
        if (state.use2Patterns) notes.add("2世代需要(MIN=OR)が有効") else notes.add("需要はP1のみ")

        return V6SanityReport(
            ok = warns.isEmpty(),
            warns = warns,
            notes = notes,
            loadDataBitSummary = buildLoadDataBitSummary(state, p, s),
            loadDataBitDetails = buildLoadDataBitDetails(state, p),
            shiftCountDiagnostics = buildShiftCountDiagnostic(state, p, s),
            impossibleWishes = impossible,
            duplicateSeqConstraints = dup,
            guidance = buildGuidance(state, p),
        )
    }

    fun detectImpossibleWishes(state: MagiState, p: Problem = Problem(state)): List<ImpossibleWish> {
        val out = ArrayList<ImpossibleWish>()
        for ((key, k) in state.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val j = parts.getOrNull(1)?.toIntOrNull()
            val reason = when {
                i == null || j == null -> "希望キーが i,j 形式ではありません"
                i !in 0 until p.S || j !in 0 until p.T -> "スタッフまたは日付が範囲外です"
                k !in 0 until p.K -> "希望シフトが範囲外です"
                !p.canDo(i, k) -> "スタッフのグループでは担当不可です"
                else -> null
            }
            if (reason != null) {
                val si = i?.takeIf { it in 0 until p.S } ?: -1
                val gi = si.takeIf { it >= 0 }?.let { p.sgrp[it] } ?: -1
                out.add(
                    ImpossibleWish(
                        staffIndex = si,
                        dayIndex = j ?: -1,
                        staffName = state.staff.getOrNull(si)?.name ?: "#$si",
                        groupSymbol = state.groups.getOrNull(gi)?.kigou ?: "?",
                        shiftSymbol = state.shifts.getOrNull(k)?.kigou ?: k.toString(),
                        reason = reason,
                    )
                )
            }
        }
        return out.sortedWith(compareBy<ImpossibleWish> { it.staffIndex }.thenBy { it.dayIndex })
    }

    /**
     * [設定ミスの誘導修正] 制約・希望の設定間違いを、人間が直せる粒度（誰の/何日の/どのシフト/どの制約、
     * そして具体的な直し方）で列挙する。検出済みの構造化データを平易な日本語の指示文に変換するだけで、
     * 重み・データは一切変更しない（読み取り専用＝安全）。表示順は「直すべき度合い」が高い順。
     */
    fun buildGuidance(state: MagiState, p: Problem = Problem(state)): List<SettingIssue> {
        val out = ArrayList<SettingIssue>()

        // 1) 希望シフトの設定ミス（担当外・範囲外など）
        for (w in detectImpossibleWishes(state, p)) {
            val where = "${w.staffName} ${safeDayLabel(state.startDate, w.dayIndex)} 希望「${w.shiftSymbol}」"
            val fix = when {
                w.reason.contains("担当不可") ->
                    "この希望を取り消すか、設定(ws1)で${w.staffName}さんの担当に「${w.shiftSymbol}」を追加してください"
                w.reason.contains("範囲外") ->
                    "希望のシフト記号・日付が勤務表の範囲内かを確認してください"
                else -> "希望の入力（i,j形式）を確認してください"
            }
            val canOneTap = w.reason.contains("担当不可")
            out.add(SettingIssue(IssueKind.WISH, where, "実現できない希望です（${w.reason}）", fix,
                action = if (canOneTap) SettingFixAction.REMOVE_WISH else SettingFixAction.NONE,
                actionLabel = if (canOneTap) "この希望を取消" else "",
                wishKey = if (canOneTap) "${w.staffIndex},${w.dayIndex}" else null))
        }

        // 2) 連続パターン制約の重複（例: c3n:Dﾃ→A4）
        for (d in findDuplicateSeqConstraints(state)) {
            val famRaw = d.substringBefore(':')
            val fam = c3FamilyJp(famRaw)
            val seq = d.substringAfter(':')
            out.add(SettingIssue(IssueKind.CONSTRAINT, "連続パターン「$seq」($fam)",
                "同じパターンが2重に登録されています", "連続パターン設定(ws4)で「$seq」の重複行を1つ削除してください",
                action = SettingFixAction.DELETE_DUP_SEQ, actionLabel = "重複を1つ削除",
                seqFamily = famRaw, seqKey = seq))
        }

        // 3) 需要 > 担当可能人数（その枠は誰をどう並べても必ず不足）
        for (j in 0 until p.T) for (k in 0 until p.K) {
            val need = p.need1[k][j]
            if (need <= 0) continue
            var capable = 0
            for (i in 0 until p.S) if (p.canDo(i, k)) capable++
            if (need > capable) {
                val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
                out.add(SettingIssue(IssueKind.DEMAND, "${safeDayLabel(state.startDate, j)} $sym",
                    "必要${need}人ですが担当できるのは${capable}人だけです",
                    "担当できるスタッフを増やす(ws1)か、必要人数を${capable}人以下に下げてください(ws2)",
                    action = SettingFixAction.CAP_DEMAND, actionLabel = "必要数を${capable}人に下げる",
                    demandShiftIdx = k, demandCap = capable))
            }
        }

        // 4) 回数レンジ(staffRange)の設定ミス
        for ((key, r) in state.staffRange) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val k = parts.getOrNull(1)?.toIntOrNull()
            val lo = r.lo.trim().toIntOrNull()
            val hi = r.hi.trim().toIntOrNull()
            val name = i?.let { state.staff.getOrNull(it)?.name } ?: "#$i"
            val sym = k?.let { state.shifts.getOrNull(it)?.kigou } ?: "$k"
            if (i == null || k == null || i !in 0 until p.S || k !in 0 until p.K) {
                out.add(SettingIssue(IssueKind.RANGE, "回数設定 $key", "対象スタッフ/シフトが範囲外です", "設定(ws1)で正しいスタッフ・シフトに付け直してください"))
                continue
            }
            if (lo != null && hi != null && lo > hi) {
                out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」回数", "下限$lo > 上限$hi で矛盾しています", "設定(ws1)で下限≤上限に直してください",
                    action = SettingFixAction.CLAMP_RANGE_LO, actionLabel = "下限を${hi}に下げる", rangeKey = key, newLo = hi.toString()))
            }
            if (lo != null && lo > 0 && !p.canDo(i, k)) {
                out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」回数", "担当できないシフトに下限${lo}が設定されています", "下限を0にするか、${name}さんの担当に「$sym」を追加してください(ws1)",
                    action = SettingFixAction.ZERO_RANGE_LO, actionLabel = "下限を0にする", rangeKey = key, newLo = "0"))
            }
            if (lo != null && lo > p.T) {
                out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」回数", "下限${lo}が期間日数(${p.T}日)を超えています", "下限を${p.T}以下に直してください(ws1)",
                    action = SettingFixAction.CLAMP_RANGE_LO, actionLabel = "下限を${p.T}に下げる", rangeKey = key, newLo = p.T.toString()))
            }
        }

        // 5) 1人の各シフト下限の合計が期間日数を超える（割り当て不能）
        for (i in 0 until p.S) {
            var sumLo = 0
            for (k in 0 until p.K) {
                val lo = p.rangeLo[i][k]
                if (lo != Int.MIN_VALUE && lo > 0) sumLo += lo
            }
            if (sumLo > p.T) {
                val name = state.staff.getOrNull(i)?.name ?: "#$i"
                out.add(SettingIssue(IssueKind.RANGE, "$name の回数下限の合計",
                    "各シフトの下限の合計が${sumLo}で、期間日数(${p.T}日)を超えています",
                    "どれかのシフトの下限を下げてください（合計を${p.T}以下に）(ws1)"))
            }
        }

        return out
    }

    private fun c3FamilyJp(fam: String): String = when (fam) {
        "c3" -> "必須MUST"
        "c3n" -> "禁止FORBIDDEN"
        "c3m" -> "希望Want"
        "c3mn" -> "回避Hate"
        else -> fam
    }

    /**
     * [デバッグ用] 確定スケジュールの制約違反を「家族ごとに・場所と実値つき」で列挙する。
     *  - 被覆(covU/covO/c41/c41s): 必要数/現状数 を実値表示（needが未設定=demand無しかどうかも即判明）
     *  - 回数(low/high/c2): 回数/下限/上限
     *  - セル(c1/c3/c3n/c3m/c3mn/c42/c42s/pref/groupViol): 誰の・何日・どのシフトか
     * 読み取り専用（重み・データ不変＝安全）。家族ごと最大件数で打ち切り、ログ肥大を防ぐ。
     */
    fun buildViolationDebug(state: MagiState, schedule: Array<IntArray>, report: ViolationReport): List<String> {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        val out = ArrayList<String>()
        fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: k.toString()
        fun nm(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun day(j: Int) = safeDayLabel(state.startDate, j)
        fun emit(byFam: Map<String, MutableList<String>>, cap: Int) {
            for ((fam, items) in byFam) {
                val shown = items.take(cap).joinToString(" ; ")
                val more = if (items.size > cap) " …他${items.size - cap}件" else ""
                out.add("[D] 違反詳細 $fam(${items.size}件): $shown$more")
            }
        }

        // 0) 需給サマリ: シフトごとに「日次需要」と「個人下限/上限・適切回数(クランプ後)の供給圧力・現状配置」を
        //    対比し、過剰(covO=日数オーバー)/不足(covU)の構造的要因を一目で示す。読み取り専用（重み・データ不変）。
        //    例: Dﾃ 需要31 < 適切回数計35 → 各人をその回数へ近づける圧力が需要を超え、過剰配置(1日2人)が出る。
        run {
            val cnt = countMatrix(p, s)
            for (k in 0 until p.K) {
                var demand = 0
                for (j in 0 until p.T) { val n = p.need1[k][j]; if (n > 0) demand += n }
                var doable = 0; var loSum = 0; var hiSum = 0; var aptSum = 0
                var hasRange = false; var hasApt = false; var cur = 0
                for (i in 0 until p.S) {
                    cur += cnt[i][k]
                    if (!p.canDo(i, k)) continue
                    doable++
                    val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val t = p.apt[i][k]
                    if (lo != Int.MIN_VALUE) { loSum += lo; hasRange = true }
                    if (hi != Int.MAX_VALUE) { hiSum += hi; hasRange = true }
                    if (t >= 0) { aptSum += t; hasApt = true }
                }
                if (demand == 0 && !hasRange && !hasApt) continue   // 需給の概念が薄いシフトは省略
                val pull = maxOf(loSum, aptSum)                     // 各人は下限と適切回数の高い方まで埋まりやすい
                val pullSrc = if (aptSum >= loSum) "適切回数" else "下限"
                val notes = ArrayList<String>()
                if (demand > 0 && pull > demand) notes.add("供給圧力${pull}(${pullSrc})>需要${demand}→過剰見込+${pull - demand}")
                if (demand > 0 && hiSum in 1 until demand) notes.add("需要${demand}>上限計${hiSum}→不足見込${demand - hiSum}")
                val tag = if (notes.isEmpty()) "需給" else "需給注意"
                val rangeStr = if (hasRange) " 下限計$loSum 上限計$hiSum" else ""
                val aptStr = if (hasApt) " 適切回数計$aptSum" else ""
                out.add("[D] $tag ${sym(k)}: 需要$demand 担当${doable}名$rangeStr$aptStr 現状$cur" +
                    (if (notes.isNotEmpty()) " → ${notes.joinToString(" / ")}" else ""))
            }
        }

        // 1) 被覆: 必要数/現状数の実値（needViolations は k,j キー）
        if (report.needViolations.isNotEmpty()) {
            val cov = coverage(p, s)
            val byFam = LinkedHashMap<String, MutableList<String>>()
            for ((key, cls) in report.needViolations) {
                val parts = key.split(','); val k = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (k !in 0 until p.K || j !in 0 until p.T) continue
                val n1 = p.need1[k][j]; val n2 = if (p.use2) p.need2[k][j] else n1
                val needStr = if (p.use2 && n2 >= 0 && n2 != n1) "$n1~$n2" else "$n1"
                byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }.add("${day(j)} ${sym(k)} 必要$needStr/現状${cov[j][k]}")
            }
            emit(byFam, 12)
        }

        // 2) 回数: 回数/下限/上限（countViolations は i,k キー）
        if (report.countViolations.isNotEmpty()) {
            val cnt = countMatrix(p, s)
            val byFam = LinkedHashMap<String, MutableList<String>>()
            for ((key, cls) in report.countViolations) {
                val parts = key.split(','); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val k = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (i !in 0 until p.S || k !in 0 until p.K) continue
                val lo = p.rangeLo[i][k].takeIf { it != Int.MIN_VALUE }
                val hi = p.rangeHi[i][k].takeIf { it != Int.MAX_VALUE }
                byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }
                    .add("${nm(i)} ${sym(k)} 回数${cnt[i][k]}" + (lo?.let { " 下限$it" } ?: "") + (hi?.let { " 上限$it" } ?: ""))
            }
            emit(byFam, 12)
        }

        // 3) セル違反: 誰の・何日・どのシフト（violations は i,j キー）
        if (report.violations.isNotEmpty()) {
            val byFam = LinkedHashMap<String, MutableList<String>>()
            for ((key, cls) in report.violations) {
                val parts = key.split(','); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (i !in 0 until p.S || j !in 0 until p.T) continue
                byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }.add("${nm(i)} ${day(j)}=${sym(s[i][j])}")
            }
            emit(byFam, 15)
        }

        if (out.isEmpty()) out.add("[D] 違反詳細: 制約違反はありません")
        return out
    }

    private fun buildLoadDataBitSummary(state: MagiState, p: Problem, schedule: Array<IntArray>): String {
        var assigned = 0
        for (row in schedule) {
            for (v in row) {
                if (v in 0 until p.K) assigned++
            }
        }
        val possible = p.S * p.T
        var allowBits = 0
        for (g in 0 until p.G) {
            allowBits += p.bucket.getOrNull(g)?.size ?: 0
        }
        val wishCount = state.wishes.size
        val rangeCount = state.staffRange.size
        return "LoadDataBit: staffN=${p.S} termT=${p.T} shiftK=${p.K} assigned=$assigned/$possible allowBits=$allowBits wishes=$wishCount ranges=$rangeCount"
    }

    private fun buildLoadDataBitDetails(state: MagiState, p: Problem): List<String> {
        val out = ArrayList<String>()
        for (g in 0 until p.G) {
            val allowedParts = ArrayList<String>()
            for (k in p.bucket[g]) {
                allowedParts.add(state.shifts.getOrNull(k)?.kigou ?: k.toString())
            }
            val allowed = allowedParts.joinToString(" ")
            var members = 0
            for (staff in state.staff) {
                if (staff.groupIdx == g) members++
            }
            out.add("Group ${state.groups.getOrNull(g)?.kigou ?: g}: members=$members allowed=[$allowed]")
        }
        return out
    }

    private fun buildShiftCountDiagnostic(state: MagiState, p: Problem, schedule: Array<IntArray>): List<ShiftCountDiagnostic> {
        val counts = countMatrix(p, schedule)
        val out = ArrayList<ShiftCountDiagnostic>()
        for (i in 0 until p.S) for (k in 0 until p.K) {
            val lo = p.rangeLo[i][k].takeIf { it != Int.MIN_VALUE }
            val hi = p.rangeHi[i][k].takeIf { it != Int.MAX_VALUE }
            if (lo == null && hi == null) continue
            val n = counts[i][k]
            val status = when {
                lo != null && n < lo -> "LOW"
                hi != null && n > hi -> "HIGH"
                else -> "OK"
            }
            out.add(ShiftCountDiagnostic(i, state.staff.getOrNull(i)?.name ?: "#$i", state.shifts.getOrNull(k)?.kigou ?: k.toString(), n, lo, hi, status))
        }
        return out.sortedWith(compareBy<ShiftCountDiagnostic> { it.status != "LOW" && it.status != "HIGH" }.thenBy { it.staffIndex })
    }

    private fun invalidAssignmentCells(state: MagiState, p: Problem, schedule: Array<IntArray>): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = schedule[i][j]
            if (k !in 0 until p.K) out.add("$i,$j=範囲外($k)")
            else if (!p.canDo(i, k)) out.add("$i,$j=${state.shifts.getOrNull(k)?.kigou ?: k}")
        }
        return out
    }

    private fun badStaffRanges(state: MagiState, p: Problem): Int {
        var bad = 0
        for ((key, r) in state.staffRange) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val k = parts.getOrNull(1)?.toIntOrNull()
            val lo = r.lo.trim().toIntOrNull()
            val hi = r.hi.trim().toIntOrNull()
            if (i == null || k == null || i !in 0 until p.S || k !in 0 until p.K) bad++
            if (lo != null && hi != null && lo > hi) bad++
        }
        return bad
    }

    private fun impossibleDemandDays(state: MagiState, p: Problem): List<String> {
        val out = ArrayList<String>()
        for (j in 0 until p.T) for (k in 0 until p.K) {
            val need = p.need1[k][j]
            if (need <= 0) continue
            var capable = 0
            for (i in 0 until p.S) {
                if (p.canDo(i, k)) capable++
            }
            if (need > capable) out.add("${safeDayLabel(state.startDate, j)} ${state.shifts.getOrNull(k)?.kigou ?: k}: need=$need capable=$capable")
        }
        return out
    }

    private fun findDuplicateSeqConstraints(state: MagiState): List<String> {
        val out = ArrayList<String>()
        collectDuplicateSeq("c3", state.cons3, out)
        collectDuplicateSeq("c3n", state.cons3n, out)
        collectDuplicateSeq("c3m", state.cons3m, out)
        collectDuplicateSeq("c3mn", state.cons3mn, out)
        return out
    }

    private fun collectDuplicateSeq(name: String, rows: List<C3Row>, out: MutableList<String>) {
        val seen = HashSet<String>()
        for (r in rows) {
            val parts = ArrayList<String>()
            for (item in r.pattern) {
                if (item.isBlank()) break
                parts.add(item)
            }
            val key = parts.joinToString("→")
            if (key.isBlank()) continue
            if (!seen.add(key)) out.add("$name:$key")
        }
    }
}

private fun safeDayLabel(startDate: String, offset: Int): String = try {
    val d = java.time.LocalDate.parse(startDate).plusDays(offset.toLong())
    val wd = "月火水木金土日"[d.dayOfWeek.value - 1]
    "${d.monthValue}/${d.dayOfMonth}($wd)"
} catch (_: Exception) {
    "${offset + 1}日"
}
