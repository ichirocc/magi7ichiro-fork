package com.magi.app.v6

import com.magi.app.toHankakuKigou
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
                        groupSymbol = state.groups.getOrNull(gi)?.kigou?.let { toHankakuKigou(it) } ?: "?",
                        shiftSymbol = state.shifts.getOrNull(k)?.kigou?.let { toHankakuKigou(it) } ?: k.toString(),
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

        // 2b) [監査#8 / Web HF557 A4 の native 移植] 連勤・回数窓制約(cons1)の不能設定
        //   d1>期間: 窓が期間を超え、判定が一度も走らず無言で無効。 d2>d1: 物理的に不可能で全員・全窓が発火し続ける。
        for (c in p.cons1) {
            val sym = state.shifts.getOrNull(c.shiftIdx)?.kigou ?: c.shiftIdx.toString()
            if (c.day1 > p.T) {
                out.add(SettingIssue(IssueKind.CONSTRAINT, "連勤/休制約「$sym ${c.day1}日で${c.day2}回以上」",
                    "窓${c.day1}日が期間${p.T}日を超えるため、この制約は一度も判定されません（無言で無効です）",
                    "制約設定（連勤・回数）で日数を期間${p.T}日以下に直すか、この行を削除してください"))
            } else if (c.day2 > c.day1) {
                out.add(SettingIssue(IssueKind.CONSTRAINT, "連勤/休制約「$sym ${c.day1}日で${c.day2}回以上」",
                    "${c.day1}日の窓に${c.day2}回は物理的に不可能で、全員・全期間が違反になり続けます",
                    "制約設定（連勤・回数）で回数を${c.day1}回以下に直すか、この行を削除してください"))
            }
        }

        // 2c) [監査#5] 担当可能者ゼロの回数制約(cons2) — canDoガード後は事実上無効になるため案内する。
        for (c in p.cons2) {
            val eligible = (0 until p.S).count { p.canDo(it, c.shiftIdx) }
            if (eligible == 0) {
                val sym = state.shifts.getOrNull(c.shiftIdx)?.kigou ?: c.shiftIdx.toString()
                out.add(SettingIssue(IssueKind.CONSTRAINT, "回数制約「$sym を${c.count}回以上」",
                    "このシフトを担当できる職員がいないため、この制約は事実上無効です",
                    "担当設定（グループ×シフト）で担当者を追加するか、この行を削除してください"))
            }
        }

        // 2d) [監査#9] 期間より長い連続パターン — パース段階で除外済み（Problem.c3OverT）。理由を案内する。
        for ((fam, seqStr) in p.c3OverT) {
            val famJp = c3FamilyJp(fam)
            val negative = fam == "c3n" || fam == "c3mn"
            out.add(SettingIssue(IssueKind.CONSTRAINT, "連続パターン「$seqStr」($famJp)",
                if (negative) "パターン長が期間${p.T}日を超えるため期間内に発生し得ず、この制約は無効です"
                else "パターン長が期間${p.T}日を超えるため物理的に充足できず、この制約は無効です",
                "連続パターン設定(ws4)でパターンを${p.T}日以下に短縮するか、この行を削除してください"))
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

        // 6) [事前診断] シフト単位の構造的な過拘束（席数 vs 下限/上限の合計）。実行前に「何をしても無理」を提示し、
        //    無駄な最適化(数分)を避ける。誤検知を避けるため、明確に矛盾する2ケースのみ（読み取り専用・データ不変）。
        for (k in 0 until p.K) {
            var seatsLo = 0; var seatsHi = 0; var hasDemand = false
            for (j in 0 until p.T) {
                val n1 = p.need1[k][j]
                if (n1 < 0) continue   // need 未設定の日は対象外
                hasDemand = true
                val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else n1
                seatsLo += maxOf(n1, 0); seatsHi += maxOf(hi, 0)
            }
            if (!hasDemand) continue
            val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
            var capable = 0; var loSum = 0; var capSum = 0; var allCapped = true
            for (i in 0 until p.S) {
                if (!p.canDo(i, k)) continue
                capable++
                val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
                if (lo != Int.MIN_VALUE && lo > 0) loSum += lo
                if (hi != Int.MAX_VALUE) capSum += hi else allCapped = false
            }
            // A) 下限の合計 > 必要数(上限)の合計 → 全員の下限を満たすと必要数を超える＝過剰配置/下限割れが不可避。
            if (loSum > seatsHi) {
                out.add(SettingIssue(IssueKind.DEMAND, "「$sym」の回数下限の合計",
                    "担当者の下限の合計が${loSum}回ですが、必要数の合計は${seatsHi}回しかありません。全員の下限は同時に満たせず、過剰配置か下限割れが必ず出ます",
                    "「$sym」の個人下限を下げる(ws1)か、必要人数を増やしてください(ws2)"))
            }
            // B) 全担当者に上限があり、上限の合計 < 必要数 → 席を埋めきれず人員不足(covU)が不可避。
            if (capable > 0 && allCapped && capSum < seatsLo) {
                out.add(SettingIssue(IssueKind.DEMAND, "「$sym」の必要人数",
                    "必要数の合計は${seatsLo}回ですが、担当者の上限の合計は${capSum}回しかありません。席を埋めきれず人員不足になります",
                    "「$sym」の個人上限を上げる/担当者を増やす(ws1)か、必要人数を下げてください(ws2)"))
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
        // [スパム対策] 各違反家族の詳細列挙の上限。1パターン把握には十分な件数に絞り、長大化を防ぐ
        //   （以前は 12〜15。c1/c3m など大量家族の1行が極端に伸びていた）。総数は「(N件)」で常に保持。
        val DETAIL_CAP = 8
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
        //    注: 下限/上限/適切回数の「計」は設定済み職員のみの合計。未設定者がいると実効上限は無制限なので、
        //    上限計<需要でも不足とは限らない（不足の構造判定は全員に上限がある場合に限定する）。
        run {
            val cnt = countMatrix(p, s)
            for (k in 0 until p.K) {
                var demand = 0
                for (j in 0 until p.T) { val n = p.need1[k][j]; if (n > 0) demand += n }
                var doable = 0; var loSum = 0; var hiSum = 0; var aptSum = 0
                var loCnt = 0; var hiCnt = 0; var aptCnt = 0; var cur = 0
                for (i in 0 until p.S) {
                    cur += cnt[i][k]
                    if (!p.canDo(i, k)) continue
                    doable++
                    val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val t = p.apt[i][k]
                    if (lo != Int.MIN_VALUE) { loSum += lo; loCnt++ }
                    if (hi != Int.MAX_VALUE) { hiSum += hi; hiCnt++ }
                    if (t >= 0) { aptSum += t; aptCnt++ }
                }
                val hasRange = loCnt > 0 || hiCnt > 0
                val hasApt = aptCnt > 0
                if (demand == 0 && !hasRange && !hasApt) continue   // 需給の概念が薄いシフトは省略
                val notes = ArrayList<String>()
                // 実際の過不足: 最適化結果(現状) vs 日次需要 = covO/covU の方向。
                if (demand > 0 && cur > demand) notes.add("現状${cur}>需要${demand}→過剰${cur - demand}(covO)")
                if (demand > 0 && cur < demand) notes.add("現状${cur}<需要${demand}→不足${demand - cur}(covU)")
                // 構造要因(過剰): 各人が下限/適切回数まで埋める圧力(=確実に埋まる量)の合計が需要超過。
                val pull = maxOf(loSum, aptSum)
                val pullSrc = if (aptSum >= loSum) "適切回数" else "下限"
                if (demand > 0 && pull > demand) notes.add("供給圧力${pull}(${pullSrc})>需要${demand}")
                // 構造要因(不足): 全担当者に上限があり、その合計が需要未満のときのみ（未設定者は無制限なので除外）。
                if (demand > 0 && doable > 0 && hiCnt == doable && hiSum < demand) notes.add("全${doable}名の上限計${hiSum}<需要${demand}→構造的に不足")
                fun cs(sum: Int, c: Int) = if (c == doable) "$sum" else "$sum(${c}/${doable}名)"
                val tag = if (notes.any { it.contains("過剰") || it.contains("不足") }) "需給注意" else "需給"
                val rangeStr = if (hasRange) " 下限計${cs(loSum, loCnt)} 上限計${cs(hiSum, hiCnt)}" else ""
                val aptStr = if (hasApt) " 適切回数計${cs(aptSum, aptCnt)}" else ""
                out.add("[D] $tag ${sym(k)}: 需要$demand 担当${doable}名$rangeStr$aptStr 現状$cur" +
                    (if (notes.isNotEmpty()) " → ${notes.joinToString(" / ")}" else ""))
            }
        }

        // 0b) 上下チェック(全シフト網羅): 下限/上限(staffRange)が設定された全シフトについて、個人別の
        //     下限割れ(low)/上限超過(high)を担当者ぶん洗い出す。違反詳細(low/high)は違反のみ列挙だが、
        //     こちらは設定済みシフトを網羅し違反0でも「上下OK」を出す。判定は UnifiedViolationChecker と一致
        //     （low: lo!=0 かつ canDo かつ 回数<lo / high: 回数>hi）。読み取り専用。
        run {
            val cnt = countMatrix(p, s)
            for (k in 0 until p.K) {
                val lows = ArrayList<String>(); val highs = ArrayList<String>()
                var hasBound = false
                for (i in 0 until p.S) {
                    if (!p.canDo(i, k)) continue
                    val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val n = cnt[i][k]
                    if (lo != Int.MIN_VALUE && lo != 0) { hasBound = true; if (n < lo) lows.add("${nm(i)} $n<$lo") }
                    if (hi != Int.MAX_VALUE) { hasBound = true; if (n > hi) highs.add("${nm(i)} $n>$hi") }
                }
                if (!hasBound) continue
                fun part(label: String, xs: List<String>) =
                    if (xs.isEmpty()) "${label}0名" else "${label}${xs.size}名(${xs.take(8).joinToString(" ")}${if (xs.size > 8) " …他${xs.size - 8}件" else ""})"
                val tag = if (lows.isEmpty() && highs.isEmpty()) "上下OK" else "上下注意"
                out.add("[D] $tag ${sym(k)}: ${part("下限割れ", lows)} / ${part("上限超過", highs)}")
            }
        }

        // 1) 被覆: 必要数/現状数の実値（needViolations は k,j キー）。covU/covO のみ扱う。
        if (report.needViolations.isNotEmpty()) {
            val cov = coverage(p, s)
            val byFam = LinkedHashMap<String, MutableList<String>>()
            for ((key, cls) in report.needViolations) {
                // [診断強化②③] c41/c41s は被覆ではなく「群(スキル)×シフトの人数制約」。被覆テンプレ(必要{need1})では
                //   群が判別できず、休など need1=-1 のシフトで「必要-1」と誤表示される。専用集約(1b)へ回す。
                if (cls == "vio-c41" || cls == "vio-c41s") continue
                val parts = key.split(','); val k = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (k !in 0 until p.K || j !in 0 until p.T) continue
                val n1 = p.need1[k][j]; val n2 = if (p.use2) p.need2[k][j] else n1
                val needStr = if (p.use2 && n2 >= 0 && n2 != n1) "$n1~$n2" else "$n1"
                byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }.add("${day(j)} ${sym(k)} 必要$needStr/現状${cov[j][k]}")
            }
            emit(byFam, DETAIL_CAP)
        }

        // 1b) [診断強化②③＋スパム削減] c41/c41s = 日次・群(スキル)×シフトの人数が[下限,上限]に収まるか。
        //     被覆テンプレでは群が消え、複数群が同じ(シフト,日)で1件に潰れて件数も合わない(例 score c41=124 vs 詳細31)。
        //     cons 行ごとに「群/スキル × シフト・下限上限・違反日数・現状人数範囲」で集約し、どの群が何日どれだけ
        //     外れたかを最小行で示す（124件→cons行数の数行に圧縮）。
        run {
            fun emitCons(rows: List<C41>, fam: String, memberOf: (Int) -> Int, groupSym: (Int) -> String) {
                for (c in rows) {
                    var vdays = 0; var minZ = Int.MAX_VALUE; var maxZ = 0
                    for (j in 0 until p.T) {
                        var z = 0
                        for (i in 0 until p.S) if (memberOf(i) == c.groupIdx && s[i][j] == c.shiftIdx) z++
                        if (z < c.l || z > c.u) { vdays++; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z }
                    }
                    if (vdays > 0) {
                        val range = if (minZ == maxZ) "$minZ" else "$minZ〜$maxZ"
                        out.add("[D] 違反詳細 $fam: ${groupSym(c.groupIdx)}×${sym(c.shiftIdx)} ${vdays}日違反 (下限${c.l}/上限${c.u}, 現状$range)")
                    }
                }
            }
            emitCons(p.cons41, "c41", { i -> p.sgrp[i] }, { g -> state.groups.getOrNull(g)?.kigou ?: "群$g" })
            emitCons(p.cons41s, "c41s", { i -> p.ssk[i] }, { g -> state.skillGroups.getOrNull(g)?.kigou ?: "スキル$g" })
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
            emit(byFam, DETAIL_CAP)
        }

        // 3) セル違反: 誰の・何日・どのシフト（violations は i,j キー）
        if (report.violations.isNotEmpty()) {
            val byFam = LinkedHashMap<String, MutableList<String>>()
            for ((key, cls) in report.violations) {
                val parts = key.split(','); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (i !in 0 until p.S || j !in 0 until p.T) continue
                byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }.add("${nm(i)} ${day(j)}=${sym(s[i][j])}")
            }
            emit(byFam, DETAIL_CAP)
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

        // 6) [監査#7] SOFT 桁溢れ（辞書式崩壊）: soft 合計がスコア上限 1,000,000 に接近/超過すると、
        //   HARD 1件(=1,000,000) と soft が桁で干渉し「必須違反ゼロ優先」が崩れる。初期解の soft で概算警告。
        run {
            val soft = Evaluator(p).fullEvalParts(normalizeSchedule(state.schedule.toIntArray2D(), p))[1]
            if (soft >= 900_000L) {
                out.add(SettingIssue(IssueKind.CONSTRAINT, "SOFT違反の合計が過大（${soft}）",
                    "調整項(SOFT)の合計がスコア上限 1,000,000 に接近しており、必須(HARD)違反ゼロを最優先する評価が崩れる恐れがあります",
                    "解消不能な制約（回数>日数の連勤条件など）や、多数の同時禁止(C42)・広すぎる範囲制約を見直して調整項を減らしてください"))
            }
        }
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
