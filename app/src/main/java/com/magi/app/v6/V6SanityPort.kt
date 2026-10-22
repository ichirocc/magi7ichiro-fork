package com.magi.app.v6

import com.magi.app.toHankakuKigou
import com.magi.app.model.C3Row
import com.magi.app.model.MagiState
import com.magi.app.model.ShiftRole

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

/** 希望どうしの衝突（[V6SanityPort.wishSelfConflicts]、定義は docs/business-logic.md）。family="c3n"＝days は禁止の並びの窓、
 *  "c3w"＝days は [前日, 希望日]。shifts は days と同じ順の希望の勤務。 */
data class WishSelfConflict(val staff: Int, val family: String, val days: List<Int>, val shifts: List<Int>) {
    val wishKeys: List<String> get() = days.map { "$staff,$it" }
}

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
enum class SettingFixAction { NONE, REMOVE_WISH, DELETE_DUP_SEQ, ZERO_RANGE_LO, CLAMP_RANGE_LO, CAP_DEMAND, CLAMP_GROUP_RANGE_LO }

data class SettingIssue(
    val kind: IssueKind,
    val where: String,
    val problem: String,
    val fix: String,
    // --- ワンタップ修正（少ないスクロール・少ないボタン操作のための直接修正情報） ---
    val action: SettingFixAction = SettingFixAction.NONE,
    val actionLabel: String = "",          // ボタン文言（空=ワンタップ不可で編集画面へ）
    val wishKey: String? = null,           // REMOVE_WISH: "i,j"
    val wishShift: Int? = null,            // REMOVE_WISH: 診断したときの希望の勤務（変わっていたら取り消さない）
    val wishStaffId: Int? = null,          // REMOVE_WISH: 職員 ID（並び替えのあとも同じ人を指す）
    val wishFingerprint: Long? = null,     // REMOVE_WISH: 診断したときの入力の指紋（[wishDiagnosisFingerprint]）
    val seqFamily: String? = null,         // DELETE_DUP_SEQ: c3 / c3n / c3m / c3mn
    val seqKey: String? = null,            // DELETE_DUP_SEQ: "Dﾃ→A4"（→区切り・最初の空白まで＝c3SeqKey）
    val rangeKey: String? = null,          // ZERO/CLAMP_RANGE_LO: "i,k"
    val newLo: String? = null,             // ZERO/CLAMP_RANGE_LO: 新しい下限
    val demandShiftIdx: Int? = null,       // CAP_DEMAND: シフトidx
    val demandCap: Int? = null,            // CAP_DEMAND: 担当可能人数（上限）
    // CAP_DEMAND: 日付つきの不足はその日の例外(needDay1/2)だけを書く。null は「全日が同じ上限で不足」の1件＝標準の必要数を下げる。
    val demandDayIdx: Int? = null,
    // CLAMP_GROUP_RANGE_LO: 群/スキル群のレンジ行。行は List なので index で指すと、診断からタップまでの間に
    //   並びが変わると別の行を壊す。DELETE_DUP_SEQ と同じく**内容一致**で指す（data class の equals）。
    val groupRangeFamily: String? = null,  // "c41" / "c41s"
    val groupRangeRow: com.magi.app.model.C41Row? = null,
    /** 意図した設定に由来する案内（上限 0 × 希望）。ログで「設定ミス」と呼ばない。 */
    val neutral: Boolean = false,
    /** [backlog #11] 個人の上限0（パート等の意図した指定）の案内。直す項目として数えない（[fixItems]）。 */
    val zeroCap: Boolean = false,
)

/** 個人の上限0（休を除く）の表示名。設定ミスでなく意図した対象外として見せる（backlog #11、2026-10-22 ユーザー決定）。 */
const val ZERO_CAP_LABEL = "対象外（上限0）"

/** 直す項目として数える診断（個人の上限0の案内を除く）。 */
fun fixItems(issues: List<SettingIssue>): List<SettingIssue> = issues.filterNot { it.zeroCap }

/** [3.687.0/R02] 希望の取消を診断した入力の指紋（職員 ID・グループと担当可否・日・その日と前後の希望・シフトの並び・希望の前日に禁止・期間）。
 *  当てる直前に照合し、違えば診断が古いので取り消さない。 */
fun wishDiagnosisFingerprint(state: MagiState, staffIndex: Int, day: Int): Long {
    val st = state.staff.getOrNull(staffIndex) ?: return 0L
    val g = st.groupIdx
    val parts = listOf<Any?>(st.id, g, state.groupShift.getOrNull(g), day,
        state.wishes["$staffIndex,${day - 1}"], state.wishes["$staffIndex,$day"], state.wishes["$staffIndex,${day + 1}"],
        state.shifts.map { it.kigou }, state.cons3w, state.startDate, state.endDate)
    var h = 1125899906842597L
    for (x in parts) h = 31 * h + x.hashCode()
    return h
}

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
        // [2026-09-02, 外部レビュー#73] buildGuidance自身は引数で受けたpを使い回すが、その呼び出し元
        //   であるこの入口がProblem(state)を毎回新規構築していた。analyzeParallel()（MagiViewModel.kt）が
        //   このbuild/buildViolationDebug/V6PortAnalyzer.analyze/diagnoseCoverage/diagnoseForbiddenRunsを
        //   同じstateに対して並列実行するため、後者3つが使うcachedProblem(state)と揃え、同一stateなら
        //   ProblemCacheのメモ化を共有する（挙動は完全に同一、Problem再構築の重複を1回省くだけ）。
        val p = cachedProblem(state)
        val s = normalizeSchedule(schedule, p)
        val warns = ArrayList<String>()
        val notes = ArrayList<String>()

        // [3.603.0/backlog#24] restIdx の全面 nullable 化＝休が1つも無い設定は最適化/チェックの入口で
        //   ブロックする（旧: 記号"休"の字面一致に失敗すると黙って index 0 へフォールバックし、実データ破損の
        //   原因になった）。ok=false で warns に積むこの入口の既存契約に乗せる。
        if (p.restIdx == null) warns.add("休みシフトが設定されていません")

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
        if (state.use2Patterns) notes.add("2世代需要(セル毎OR/AND: #4b)が有効") else notes.add("需要はP1のみ")

        return V6SanityReport(
            ok = warns.isEmpty(),
            warns = warns,
            notes = notes,
            loadDataBitSummary = buildLoadDataBitSummary(state, p, s),
            loadDataBitDetails = buildLoadDataBitDetails(state, p),
            shiftCountDiagnostics = buildShiftCountDiagnostic(state, p, s),
            impossibleWishes = impossible,
            duplicateSeqConstraints = dup,
            guidance = buildGuidance(state, p) + listOfNotNull(handPlacedUpperZeroIssue(state, p, s)),
        )
    }

    /**
     * [S6 §14 Q5] 個人の上限 0 のシフトが手で置いてある（希望・手動で固定したセルを除く）。本実行の入口の clear が外すので、
     * 再作成すると消える。盤面に依存するため [buildGuidance]（設定だけの診断）には入れない。
     */
    fun handPlacedUpperZeroIssue(state: MagiState, p: Problem, s: Array<IntArray>): SettingIssue? {
        val cells = ArrayList<String>()
        for (i in 0 until minOf(p.S, s.size)) for (j in 0 until minOf(p.T, s[i].size)) {
            val k = s[i][j]
            if (k !in 0 until p.K || k == p.restIdx || !p.canDo(i, k) || p.rangeHi[i][k] != 0) continue
            if (p.wishLocked(i, j) && p.lockTo(i, j) == k) continue
            cells += "${state.staff.getOrNull(i)?.name ?: "#$i"} ${safeDayLabel(state.startDate, j)}「${state.shifts.getOrNull(k)?.kigou ?: "$k"}」"
        }
        if (cells.isEmpty()) return null
        return SettingIssue(IssueKind.RANGE, "$ZERO_CAP_LABEL の勤務（${cells.take(3).joinToString("・")}${if (cells.size > 3) " ほか" else ""}）",
            "今の勤務表に個人の上限0のシフトが ${cells.size}件入っています。再作成すると外されます",
            "パート等で入れない指定なら、このままで構いません", neutral = true, zeroCap = true)
    }

    /** 直す場所＝編集タブの区分と節の見出し（`MagiApp.kt` の区分・`CollapsibleSection`・各カードの題と同じ名前。見出しを変えたらここも）。
     *  文の最初の場所だけ [EDIT] を前に付け、同じ文の 2 つ目からは区分から書く。 */
    private object Place {
        const val TAB = "編集タブ"
        const val EDIT = "編集タブの"
        const val WISH = "『月次条件』の『希望シフト登録』"
        const val NEED = "『月次条件』の『必要人数設定』"
        const val NEED_DAYS = "『月次条件』の『日別の必要人数（例外）一覧』"
        const val MONTH = "『月次条件』の『対象の月』"
        const val STAFF = "『職員管理』"
        const val SHIFT = "『年間マスター』の『① シフト・グループ』"
        const val SKILL = "『年間マスター』の『② スキルグループ』"
        const val COUNT = "『年間マスター』の『③ 回数（1人あたり）』"
        const val HEAD = "『年間マスター』の『④ 人数と組み合わせ』"
        const val RULE = "『年間マスター』の『⑤ 並び・期間の制約』"
        const val PIN = "勤務表タブ"
    }

    // 既定引数は cachedProblem(state)（build/aptBalances と同じ。p を渡さない呼出＝HfSwapPolish の
    //   detectHF70Anomalies・V6FinalPort の structuralHardFloor 呼出で Problem を毎回作らないため）。
    fun detectImpossibleWishes(state: MagiState, p: Problem = cachedProblem(state)): List<ImpossibleWish> {
        val out = ArrayList<ImpossibleWish>()
        for ((key, k) in state.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val j = parts.getOrNull(1)?.toIntOrNull()
            // 「担当不可」「範囲外」の語は wishIssues が直し方の分岐に読む＝言い換えるときは両方を揃える。
            val reason = when {
                i == null || j == null -> "希望の職員と日付を読み取れません"
                i !in 0 until p.S || j !in 0 until p.T -> "職員または日付が範囲外です"
                k !in 0 until p.K -> "希望シフトが範囲外です"
                !p.canDo(i, k) -> "グループ「${state.groups.getOrNull(p.sgrp[i])?.kigou?.let { toHankakuKigou(it) } ?: "?"}」では担当不可です"
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

    /** 希望どうしの衝突を職員→先頭日→族の順に返す（盤面に依存しない）。同じ職員・同じ日の並びは 1 組（重複行は検査 2 が扱う）。 */
    fun wishSelfConflicts(state: MagiState): List<WishSelfConflict> = wishSelfConflicts(cachedProblem(state))

    fun wishSelfConflicts(p: Problem): List<WishSelfConflict> {
        val out = ArrayList<WishSelfConflict>()
        val seen = HashSet<Pair<Int, List<Int>>>()
        for (i in 0 until p.S) {
            val mine = ArrayList<WishSelfConflict>()
            for (c in p.cons3n) {
                val seq = c.seq
                val d = seq.size
                if (d == 0 || d > p.T) continue
                for (j in 0..p.T - d) {
                    if ((0 until d).all { l -> p.wishFixed(i, j + l) && p.wish[i][j + l] == seq[l] }) {
                        val days = (j until j + d).toList()
                        if (seen.add(i to days)) mine.add(WishSelfConflict(i, "c3n", days, seq.toList()))
                    }
                }
            }
            if (p.c3wBan != null) for (j in 0 until p.T - 1) {
                if (p.wishFixed(i, j) && p.c3wBanned(i, j, p.wish[i][j])) {
                    mine.add(WishSelfConflict(i, "c3w", listOf(j, j + 1), listOf(p.wish[i][j], p.wish[i][j + 1])))
                }
            }
            mine.sortWith(compareBy<WishSelfConflict> { it.days.first() }.thenBy { it.family })
            out.addAll(mine)
        }
        return out
    }

    /** 回避の並び（c3mn・要調整）の窓がまるごと希望で固定され、その並びそのものになっているもの（盤面に依存しない、family="c3mn"）。
     *  希望を 1 件取り消すか回避の並びを見直すまで残る。必須ではないので [wishSelfConflicts]（必須の衝突）とは分ける（設定の案内・残存分析が共有）。 */
    fun wishAvoidRuns(p: Problem): List<WishSelfConflict> {
        val out = ArrayList<WishSelfConflict>()
        for (i in 0 until p.S) for (c in p.cons3mn) {
            val d = c.seq.size
            if (d == 0 || d > p.T) continue
            for (j in 0..p.T - d) {
                if ((0 until d).all { l -> p.wishFixed(i, j + l) && p.wish[i][j + l] == c.seq[l] }) out.add(WishSelfConflict(i, "c3mn", (j until j + d).toList(), c.seq.toList()))
            }
        }
        return out
    }

    /** [wishAvoidRuns] のうち盤面で成立している窓の数（報告の c3mn と同じ単位＝窓ごとに 1）。 */
    fun wishAvoidSoft(p: Problem, schedule: Array<IntArray>): Int =
        wishAvoidRuns(p).count { g -> g.days.all { j -> schedule.getOrNull(g.staff)?.getOrNull(j) == p.wish[g.staff][j] } }

    /** 希望どうしの衝突が生む HARD の区間（report.hard の単位＝cons3n は行ごと・窓ごとに 1、c3w はセルごとに 1）。
     *  c3n＝窓の全セルが希望で固定され禁止の並びそのもの、c3w＝希望 Y で固定したセル（翌日の希望 X が禁じる）。 */
    private data class WishHardSpan(val staff: Int, val from: Int, val to: Int, val family: String)

    private fun wishHardSpans(p: Problem): List<WishHardSpan> {
        val out = ArrayList<WishHardSpan>()
        for (i in 0 until p.S) {
            for (c in p.cons3n) {
                val d = c.seq.size
                if (d == 0 || d > p.T) continue
                for (j in 0..p.T - d) {
                    if ((0 until d).all { l -> p.wishFixed(i, j + l) && p.wish[i][j + l] == c.seq[l] }) out.add(WishHardSpan(i, j, j + d - 1, "c3n"))
                }
            }
            if (p.c3wBan != null) for (j in 0 until p.T) {
                if (p.wishFixed(i, j) && p.c3wBanned(i, j, p.wish[i][j])) out.add(WishHardSpan(i, j, j, "c3w"))
            }
        }
        return out
    }

    /** 「希望と禁止の衝突」の件数（HF70・残存分析・E0 の到達判定が共有する単一ソース、report.hard と同じ単位）。
     *  c3n/c3w＝盤面で成立している衝突の窓/セル、pref＝衝突の区間にかかる希望を崩したセル。 */
    fun wishConflictHard(p: Problem, schedule: Array<IntArray>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val prefCells = HashSet<Pair<Int, Int>>()
        for (sp in wishHardSpans(p)) {
            val row = schedule.getOrNull(sp.staff) ?: continue
            var held = true
            for (j in sp.from..sp.to) if (row.getOrNull(j) != p.wish[sp.staff][j]) { held = false; prefCells.add(sp.staff to j) }
            if (held) out[sp.family] = (out[sp.family] ?: 0) + 1
        }
        if (prefCells.isNotEmpty()) out["pref"] = prefCells.size
        return out
    }

    /** 必須 [hard] 件のうち希望どうしのぶつかり（[wishConflictHard]）の件数。族ごとに報告の内訳で頭打ちにする（必須内訳ログ・ホームが共有）。 */
    fun wishConflictHardShare(selfConflict: Map<String, Int>, breakdown: Map<String, Int>, hard: Int): Int =
        selfConflict.entries.sumOf { (k, v) -> minOf(v, breakdown[k] ?: 0) }.coerceIn(0, maxOf(hard, 0))

    /** [E0] 希望衝突の床（report.hard と同単位、[structuralHardFloor] とは別に扱う）＝衝突の最小 HARD＋日の証明の日数。 */
    fun wishConflictHardFloor(p: Problem): Int = wishConflictFloorParts(p).let { it.first + it.second }

    /** [wishConflictHardFloor] の内訳（衝突の最小 HARD, 日の証明の日数）。衝突は職員ごとに「崩すセル数＋崩れずに残る区間数」の最小を
     *  区間 DP で出す（どの盤面でも [wishConflictHard] の合計以下）。日の証明は衝突の区間の日と構造的 covU の日を数えない。
     *  [zeroCapBinding]＝上限 0 に頼る日の証明も数える（ログの参考値 N_mayPlace。`mayPlace` を守る盤面でだけ健全＝手置きの上限 0 がある盤面では超えうるので頭打ちには使わない）。 */
    fun wishConflictFloorParts(p: Problem, zeroCapBinding: Boolean = false): Pair<Int, Int> {
        val spans = wishHardSpans(p)
        var conflict = 0
        val conflictDays = HashSet<Int>()
        for ((_, ss) in spans.groupBy { it.staff }) {
            for (sp in ss) for (j in sp.from..sp.to) conflictDays.add(j)
            val byEnd = ss.groupBy { it.to }
            // f[b+1]＝最後に崩したセルが b（-1＝まだ無し）のときの最小費用。
            var f = IntArray(p.T + 1) { if (it == 0) 0 else Int.MAX_VALUE / 2 }
            for (t in 0 until p.T) {
                val g = IntArray(p.T + 1) { Int.MAX_VALUE / 2 }
                for (b in 0..p.T) if (f[b] < Int.MAX_VALUE / 2) {
                    g[b] = minOf(g[b], f[b])
                    g[t + 1] = minOf(g[t + 1], f[b] + 1)
                }
                for (sp in byEnd[t].orEmpty()) for (b in 0..p.T) if (b - 1 < sp.from) g[b] += 1
                f = g
            }
            conflict += f.min()
        }
        var days = 0
        val proofDays = if (zeroCapBinding) ConstraintMus.analyzeDayConflicts(p).map { it.day } else ConstraintMus.dayProofsWithoutZeroCap(p)
        for (j in proofDays) {
            if (j in conflictDays) continue
            if ((0 until p.K).any { k -> p.covUCell(k, j, placeableFor(p, k, j)) > 0 }) continue
            days++
        }
        return conflict to days
    }

    /** [E0] 希望どうしの c3w の件数（c3w がこの件数までなら、pref==0 のとき全て希望由来）。 */
    fun wishConflictC3wCount(p: Problem): Int = wishHardSpans(p).count { it.family == "c3w" }

    /** [E0] 盤面の HARD が全て希望由来か（衝突の窓・セル・崩した希望＋日の証明の日数までの残り）。 */
    fun hardAllWishOrigin(p: Problem, schedule: Array<IntArray>, report: ViolationReport, dayProofs: Int): Boolean {
        val w = wishConflictHard(p, schedule)
        if ((w["c3n"] ?: 0) > (report.breakdown["c3n"] ?: 0) || (w["c3w"] ?: 0) > (report.breakdown["c3w"] ?: 0)) return false
        val rest = report.hard - w.values.sum()
        return rest in 0..dayProofs
    }

    /** シフト単位の「証明可能に解消不能な covU 不足」。担当可能人数 capable(k) を全員そのシフトへ
     *  就けても残る不足（= covUCell(k,j,capable) の総和）。covUCell は got 単調減少なので、これは当該セルの
     *  covU 最小値＝どう割り当てても避けられない不足量。need1/need2 両設定時は covUCell が MIN(OR救済) を返す
     *  ため過大検出しない。誤検知ゼロ・読み取り専用・データ不変。 */
    const val ZERO_CAP_SHORTFALL_NOTE = "$ZERO_CAP_LABEL の人を数えていません（パート等で入れない指定なら、このままで構いません）"

    data class ForcedCovU(val shiftIndex: Int, val shiftSymbol: String, val cells: Int, val amount: Int)

    fun forcedCovU(state: MagiState, p: Problem = cachedProblem(state)): List<ForcedCovU> {
        val out = ArrayList<ForcedCovU>()
        for (k in 0 until p.K) {
            var cells = 0; var amount = 0
            for (j in 0 until p.T) {
                val u = p.covUCell(k, j, placeableFor(p, k, j))   // [3.507.5] 置ける人数（上限 0 は除外、固定は含む）
                if (u > 0) { cells++; amount += u }
            }
            if (amount > 0) {
                val sym = state.shifts.getOrNull(k)?.kigou?.let { toHankakuKigou(it) } ?: k.toString()
                out.add(ForcedCovU(k, sym, cells, amount))
            }
        }
        return out
    }

    /** データ起因で証明可能に解消不能な HARD 違反の下限（report.hard と同単位＝covU 不足量の総和）。
     *  ・covU: forcedCovU の総量。有資格者を全員そのシフトに就けても埋まらない席＝どう探索しても消えない HARD。
     *  ・実現不能希望(pref): 監査#11② で HARD 寄与0（対称除外）のため下限に含めない。
     *  ・群外配置(groupViol): 探索は canDo ガードで群外を置かない＋不可能希望は gate 済＝構造下限では常時0。
     *  構造(assignability/need)のみ依存で最適化中に変化しないため一度だけ算出してよい。 */
    /** 上限0を数えなければ（canDo で数えると）不足が減る＝この配布不可に個人の上限0（入れない指定）が絡む。 */
    fun zeroCapInShortfall(p: Problem, f: ForcedCovU): Boolean = (0 until p.T).sumOf { j ->
        maxOf(0, p.covUCell(f.shiftIndex, j, (0 until p.S).count { i -> p.canDo(i, f.shiftIndex) || (p.wishLocked(i, j) && p.lockTo(i, j) == f.shiftIndex) }))
    } < f.amount

    fun structuralHardFloor(state: MagiState, p: Problem = cachedProblem(state)): Int =
        forcedCovU(state, p).sumOf { it.amount }

    /**
     * [3.354.0/6b・6c 共通] 職員 i の、シフト k 以外の担当可能シフトの個人上限の合計（上限未設定は期間日数
     * で丸める）。`p.T - この値` が「他シフトの上限を守る限り k に必ず回ってくる日数」の下界になる。
     * 合計が期間日数に達した時点で下界は 0 以下＝発火しないので早期に打ち切る。
     */
    internal fun otherShiftCapSum(p: Problem, i: Int, k: Int): Int {
        var sum = 0
        for (k2 in 0 until p.K) {
            if (k2 == k || !p.canDo(i, k2)) continue
            val hi = p.rangeHi[i][k2]
            sum += if (hi == Int.MAX_VALUE) p.T else minOf(maxOf(hi, 0), p.T)
            if (sum >= p.T) return sum
        }
        return sum
    }

    /**
     * [3.354.0] 個人の担当構成から強制される **(apt + high) の合計下限**。
     *
     * 個人上限(rangeHi)は SOFT なので「必ず k に forcedMin 回入る」とは言えない（実機ログで確認: 6b が
     * 「B4 は最低20回」と言う職員が、休の上限を1日超過して B4=19 に着地していた）。ただし上限を d 日ぶん
     * 破れば count は forcedMin−d まで下がる代わりに high が d 増えるので、**両者の和** は
     * `forcedMin − 目標` を下回れない。よってこの値は apt+high の真の下限になる。
     *
     * 同じ職員の複数シフトで下界が立つ場合は上限超過ぶん(d)が共有されうるため、**職員ごとに最大値だけ**を
     * 取って合計する（保守的＝過大に見積もらない）。読み取り専用・スコア不変。
     */
    fun structuralPersonalFloor(p: Problem): Int {
        var floor = 0
        for (i in 0 until p.S) {
            var best = 0
            for (k in 0 until p.K) {
                val t = p.apt[i][k]
                if (t < 0 || !p.canDo(i, k)) continue
                val d = (p.T - otherShiftCapSum(p, i, k)) - t
                if (d > best) best = d
            }
            floor += best
        }
        return floor
    }

    /**
     * [設定ミスの誘導修正] 制約・希望の設定間違いを、人間が直せる粒度（誰の/何日の/どのシフト/どの制約、
     * そして具体的な直し方）で列挙する。検出済みの構造化データを平易な日本語の指示文に変換するだけで、
     * 重み・データは一切変更しない（読み取り専用＝安全）。表示順は「直すべき度合い」が高い順。
     */
    /**
     * [適切回数の検算・単一ソース] シフトごとの「適切回数(apt)の合計」と「それを受け止められる上限」を返す。
     *
     * 盤面を一切参照しない（need / apt / staffRange だけで決まる）ため、勤務表を作る**前**でも計算できる。
     * これが [buildGuidance] の検査6-C の実体で、設定画面（目標カード）もこの同じ関数を読む。
     * 二重実装にすると「診断は警告を出すのに設定画面は何も言わない」というズレが生まれるため、
     * 判定は必ずここへ集約する。
     *
     * - 通常シフト: capacity = 必要数(上限)の合計 seatsHi。目標の合計がこれを超えると、
     *   全員の目標は同時に満たせない（目標割れ aptLow か過剰配置 covO/aptHigh が必ず出る）。
     * - 休(restIdx): 「1日に何人休んでよいか」という座席上限を持たないため、
     *   capacity = 各職員が他シフトの個人下限を満たしたうえで最大何日休めるかの合計。
     *
     * [3.301.0 で直った挙動] 旧実装は検査6-C を「必要人数が1日でも設定されているシフト」のループ内に
     * 置いていたため、**休に必要人数を設定しない通常運用では休の判定が一度も実行されなかった**
     * （golden_state で実測: 休の必要人数設定日=0）。3.235.0 で入れた休向け restCapacity 比較が
     * そのゲートに阻まれて死んでいた潜在バグ。休は必要人数を参照しないので、ここではゲートの外に出す。
     * 通常シフトのゲート（必要人数が1日も無ければ対象外）は維持する：勤務シフトの必要人数未設定は
     * 「まだ設定していない」だけの可能性が高く、上限0とみなすと誤検知になる（3.235.0 の指摘と同型）。
     * なお休の capacity は休自身の個人上限(rangeHi)を見ていない＝実際より大きく見積もる。
     * 検出漏れ側に倒れるだけで誤検知は増えない。
     */
    data class AptBalance(
        val shiftIdx: Int,
        val kigou: String,
        /** 適切回数の合計（担当可能な職員ぶん・staffRange クランプ後の実効値）。 */
        val aptSum: Int,
        /** それを受け止められる上限（通常＝必要数の合計 / 休＝最大可能日数の合計）。 */
        val capacity: Int,
        val isRest: Boolean,
    ) {
        /** 目標の合計が上限を超えている＝何をしても目標割れか過剰配置が出る。 */
        val overloaded: Boolean get() = aptSum > capacity
        /** 何回ぶん届かないか。 */
        val shortfall: Int get() = (aptSum - capacity).coerceAtLeast(0)
    }

    /**
     * [適切回数の検算] 目標が設定されているシフトについて [AptBalance] を返す（盤面不要）。
     *
     * [p] は既定で state のキャッシュを使うが、[buildGuidance] のように呼び出し元が既に Problem を
     * 持っている場合はそれを渡す（同じ関数が別の Problem を見て診断と設定画面がズレるのを防ぐ）。
     */
    /**
     * 休(rest)の実質的な上限＝各職員が「他の担当シフトの個人下限」を満たしたうえで最大何日休めるか、の合計。
     *
     * 休は「1日に何人休んでよいか」という座席（必要人数）の概念を持たないため、必要人数の合計と比べても
     * 意味がない。他シフトの下限が未設定なら minOther=0＝ほぼ T 日休める計算になり、誤検知を避ける側へ
     * 保守的に丸まる。[3.235.0] で適切回数の検査へ導入したものを [3.316.0] で下限合計の検査とも共有する。
     */
    internal fun restCapacity(p: Problem): Int {
        // [3.603.0] 休が無ければ「休の上限」という概念自体が無い＝0（呼び出し元は p.restIdx==k のときだけ呼ぶ）。
        val k = p.restIdx ?: return 0
        var cap = 0
        for (i in 0 until p.S) {
            if (!p.canDo(i, k)) continue
            var minOther = 0
            for (k2 in 0 until p.K) {
                if (k2 == k || !p.canDo(i, k2)) continue
                val lo2 = p.rangeLo[i][k2]
                if (lo2 != Int.MIN_VALUE && lo2 > 0) minOther += lo2
            }
            cap += maxOf(0, p.T - minOther)
        }
        return cap
    }

    fun aptBalances(state: MagiState, p: Problem = cachedProblem(state)): List<AptBalance> {
        val out = ArrayList<AptBalance>()
        for (k in 0 until p.K) {
            var aptSum = 0
            var anyApt = false
            for (i in 0 until p.S) {
                if (!p.canDo(i, k)) continue
                val a = p.aptRaw[i][k]   // [3.508.0] 設定した目標を検算する（実効目標 apt は到達範囲へ丸め済み）
                if (a >= 0) { aptSum += a; anyApt = true }
            }
            if (!anyApt) continue   // 目標が1つも設定されていないシフトは検算対象外
            val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
            if (k == p.restIdx) {
                out.add(AptBalance(k, sym, aptSum, restCapacity(p), isRest = true))
            } else {
                var seatsHi = 0
                var hasDemand = false
                var capKnown = true
                for (j in 0 until p.T) {
                    if (!needDefined(p, k, j)) { capKnown = false; continue }   // [3.409.22] need2 単独定義も席として数える
                    hasDemand = true
                    seatsHi += maxOf(effectiveCap(p, k, j), 0)
                }
                if (!hasDemand) continue   // 必要人数が1日も設定されていない＝比較対象がない
                // [3.475.0/論理監査] 未定義の日は「席0」でなく「上限なし」。1日でも未定義なら seatsHi は
                //   上限として成立しない（検査6 と同じ capKnown ゲート。Ws1Editor の「必ず届きません」も
                //   この値を読むため、ここで出さないことで誤警告を根元から止める）。
                if (!capKnown) continue
                out.add(AptBalance(k, sym, aptSum, seatsHi, isRest = false))
            }
        }
        return out
    }

    /**
     * 下限>上限 なら (下限, 上限) を返す。**入力ダイアログの阻止と、この事後診断が同じ判定を使う**ための単一ソース。
     * 片方だけ緩いと「画面は通すのに、あとから直せと言われる」入力が生まれる（3.403.0）。
     * 両方が数値のときだけ矛盾と見なす＝空欄（未設定）や数値でない値は 2h/2f の別の検査が扱う。
     */
    fun rangeOrderConflict(lo: String?, hi: String?): Pair<Int, Int>? {
        val l = lo?.trim()?.toIntOrNull() ?: return null
        val h = hi?.trim()?.toIntOrNull() ?: return null
        return if (l > h) l to h else null
    }

    fun buildGuidance(state: MagiState, p: Problem = cachedProblem(state)): List<SettingIssue> =
        GuidanceBuilder(state, p).run()

    /** [3.507.5/レビュー第6段] 設定ミス検査を話題ごとのメソッドに分けた入れ物。各メソッドは `out` に追加するだけで、文言と順序は分割前と同じ。 */
    /** 設定ミス検査の表示上限（3.507.5 で集約。値は据え置き）。 */
    private object Guidance {
        const val SOFT_OVERFLOW = 900_000L   // SOFT 合計がこれ以上ならスコア上限 1,000,000 との桁干渉を警告
        const val PREVIEW = 3                // 件数つき警告に並べる例の数
        const val NAME_PREVIEW = 4           // 職員名を並べる数
        const val MUS_TOP = 3                // 最小説明（MUS）を出す上限
        const val FORCED_SHORT_MARK = "何度つくっても残る人員不足"   // 検査7 の場所の印（sorted が最優先にする目印）
    }

    private class GuidanceBuilder(val state: MagiState, val p: Problem) {
        val out = ArrayList<SettingIssue>()
        fun symOf(k: Int): String = state.shifts.getOrNull(k)?.kigou ?: k.toString()
        fun nameOf(i: Int): String = state.staff.getOrNull(i)?.name ?: "#$i"
        fun capableCount(k: Int): Int = (0 until p.S).count { p.canDo(it, k) }
        /** 日の並び（先頭 PREVIEW 件＋「ほか」）。 */
        fun daysText(days: List<Int>): String =
            days.take(Guidance.PREVIEW).joinToString("・") { safeDayLabel(state.startDate, it) } + (if (days.size > Guidance.PREVIEW) " ほか" else "")
        /** 職員 i がシフト k に希望で固定した日（6d・6e の件数と同じ数え方）。 */
        fun wishedDays(i: Int, k: Int): List<Int> = (0 until p.T).filter { j -> p.wishFixed(i, j) && p.wish[i][j] == k }

        fun run(): List<SettingIssue> {
            wishIssues(); duplicateSeqIssues(); duplicateRuleIssues(); mustForbiddenSeqIssues(); c1RuleIssues(); unusableRowIssues(); nonNumericIssues(); assignmentIssues()
            demandCapacityIssues(); staffRangeIssues(); seatIssues(); forcedCountIssues(); forcedCovUIssues(); duplicateKeyIssues()
            musIssues(); softOverflowIssue()
            return sorted()
        }

        fun wishIssues() {
            // 1) 希望シフトの設定ミス（担当外・範囲外など）
            for (w in detectImpossibleWishes(state, p)) {
                val where = "${w.staffName} ${safeDayLabel(state.startDate, w.dayIndex)} 希望「${w.shiftSymbol}」"
                val fix = when {
                    w.reason.contains("担当不可") ->
                        "本人と相談してこの希望を調整する（希望は外れます。元に戻せます）か、${Place.EDIT}${Place.SHIFT}の担当可否で、グループ「${w.groupSymbol}」が「${w.shiftSymbol}」を担当できるようにしてください"
                    w.reason.contains("範囲外") ->
                        "${Place.EDIT}${Place.WISH}で、この希望のシフトと日付が今の勤務表の範囲内かを確かめてください"
                    else -> "${Place.EDIT}${Place.WISH}で、この希望を入れ直してください"
                }
                val canOneTap = w.reason.contains("担当不可")
                out.add(SettingIssue(IssueKind.WISH, where, "実現できない希望です（${w.reason}）", fix,
                    action = if (canOneTap) SettingFixAction.REMOVE_WISH else SettingFixAction.NONE,
                    actionLabel = if (canOneTap) "この希望を取消" else "",
                    wishKey = if (canOneTap) "${w.staffIndex},${w.dayIndex}" else null,
                    wishShift = if (canOneTap) state.wishes["${w.staffIndex},${w.dayIndex}"] else null,
                    wishStaffId = if (canOneTap) state.staff.getOrNull(w.staffIndex)?.id else null,
                    wishFingerprint = if (canOneTap) wishDiagnosisFingerprint(state, w.staffIndex, w.dayIndex) else null))
            }
            val selfConflicts = wishSelfConflicts(p)
            // 1b) [3.542.0] 希望の前日に禁止(c3w)が希望どうしで衝突＝前日の Y も希望固定なら最適化器は解消できない。
            for (g in selfConflicts) {
                if (g.family != "c3w") continue
                val (i, j) = g.staff to g.days[0]
                val y = symOf(g.shifts[0]); val x = symOf(g.shifts[1])
                out.add(SettingIssue(IssueKind.WISH,
                    "${nameOf(i)} ${safeDayLabel(state.startDate, j)} 希望「$y」→ ${safeDayLabel(state.startDate, j + 1)} 希望「$x」",
                    "「$x の希望の前日は $y 禁止」に希望どうしで当たっています。希望は固定なので計算では解消できません",
                    "${Place.EDIT}${Place.WISH}で本人と相談してどちらかの希望を調整する（希望は外れます。元に戻せます）か、${Place.RULE}の「希望の前日に禁止」でこの行を見直してください",
                    action = SettingFixAction.REMOVE_WISH, actionLabel = "前日の希望を取消", wishKey = "$i,$j", wishShift = g.shifts[0],
                    wishStaffId = state.staff.getOrNull(i)?.id, wishFingerprint = wishDiagnosisFingerprint(state, i, j)))
            }
            // 1c) 禁止の並び(c3n)の窓がまるごと希望固定（例: 休の希望 3 連日と「休→休→休」禁止）。どれを取り消すかは利用者が選ぶ＝ワンタップなし。
            //     窓が 1 セル（単独の禁止シフト）なら関わる希望は 1 件＝単数の文言。
            for (g in selfConflicts) {
                if (g.family != "c3n") continue
                val seq = g.shifts.joinToString("→") { symOf(it) }
                val one = g.days.size == 1
                out.add(SettingIssue(IssueKind.WISH,
                    "${nameOf(g.staff)} ${g.days.joinToString("・") { safeDayLabel(state.startDate, it) }} 希望「$seq」",
                    "禁止の並び「$seq」に${if (one) "希望が" else "希望どうしで"}当たっています。希望は固定なので計算では解消できません",
                    "${Place.EDIT}${Place.WISH}で本人と相談して${if (one) "この希望" else "いずれか1件の希望"}を調整する（希望は外れます。元に戻せます）か、${Place.RULE}の「禁止の並び」で「$seq」を見直してください"))
            }
            // 1d) 回避の並び(c3mn)の窓がまるごと希望固定。必須ではなく要調整として残る＝案内（neutral）。
            for (g in wishAvoidRuns(p)) {
                val seq = g.shifts.joinToString("→") { symOf(it) }
                val one = g.days.size == 1
                out.add(SettingIssue(IssueKind.WISH,
                    "${nameOf(g.staff)} ${g.days.joinToString("・") { safeDayLabel(state.startDate, it) }} 希望「$seq」",
                    "回避の並び「$seq」に${if (one) "希望が" else "希望どうしで"}当たっています。希望は固定なので計算では外せず、要調整として残ります",
                    "${Place.EDIT}${Place.WISH}で本人と相談して${if (one) "この希望" else "いずれか1件の希望"}を調整する（希望は外れます。元に戻せます）か、${Place.RULE}の「回避の並び」で「$seq」を見直してください", neutral = true))
            }
        }

        fun duplicateSeqIssues() {
            // 2) 連続パターン制約の重複（例: c3n:Dﾃ→A4）
            for (d in findDuplicateSeqConstraints(state)) {
                val famRaw = d.substringBefore(':')
                val fam = c3FamilyJp(famRaw)
                val seq = d.substringAfter(':')
                out.add(SettingIssue(IssueKind.CONSTRAINT, "$fam「$seq」",
                    "同じ並びが2重に登録されています", "${Place.EDIT}${Place.RULE}の「$fam」で「$seq」の重複を1つ削除してください",
                    action = SettingFixAction.DELETE_DUP_SEQ, actionLabel = "重複を1つ削除",
                    seqFamily = famRaw, seqKey = seq))
            }
        }

        /** 2') 並び以外の族の同じ行（CSV 取込・既存データはダイアログを通らない）。エンジンは dedup しない＝知らせるだけ。
         *  `Problem` の解決後の値で比べる＝評価が実際に 2 回数える行だけ。希望の前日に禁止は禁止表（`c3wBan`）に畳まれ 1 本分。 */
        fun duplicateRuleIssues() {
            fun gsym(g: Int) = state.groups.getOrNull(g)?.kigou ?: "#$g"
            fun ssym(g: Int) = state.skillGroups.getOrNull(g)?.kigou ?: "#$g"
            fun bound(l: Int, u: Int) = "${if (l > 0) "$l" else ""}〜${if (u == Int.MAX_VALUE) "" else "$u"}"
            fun <T> report(rows: List<T>, key: (T) -> List<Int>, where: (T) -> String, place: String, counted: Boolean = true) {
                for (same in rows.groupBy(key).values) {
                    val n = same.size
                    if (n < 2) continue
                    out.add(SettingIssue(IssueKind.CONSTRAINT, where(same[0]),
                        if (counted) "同じ行が${n}本あります。違反を${n}回数えるので、この決まりだけ重みが${n}倍になります"
                        else "同じ行が${n}本あります（評価は1本分で変わりません）",
                        "${Place.EDIT}${place}で、この行の重複を削除してください（自動では消しません）"))
                }
            }
            report(p.cons1, { listOf(it.day1, it.shiftIdx, it.day2) }, { "期間の制約「${symOf(it.shiftIdx)} ${it.day1}日で${it.day2}回以上」" }, "${Place.RULE}の「期間の制約」")
            report(p.cons2, { listOf(it.shiftIdx, it.count) }, { "個人の合計「${symOf(it.shiftIdx)} 合計${it.count}回以上」" }, "${Place.RULE}の「個人の合計」")
            report(p.cons3w, { listOf(it.wishIdx, it.prevIdx) }, { "希望の前日に禁止「${symOf(it.wishIdx)} の希望の前日は ${symOf(it.prevIdx)}」" }, "${Place.RULE}の「希望の前日に禁止」", counted = false)
            report(p.cons41, { listOf(it.groupIdx, it.shiftIdx, it.l, it.u) }, { "グループのレンジ「${gsym(it.groupIdx)}・${symOf(it.shiftIdx)} ${bound(it.l, it.u)}」" }, "${Place.HEAD}の「グループのレンジ」")
            report(p.cons42, { listOf(it.g1, it.s1, it.g2, it.s2) }, { "グループペア禁止「${gsym(it.g1)}の${symOf(it.s1)} ✕ ${gsym(it.g2)}の${symOf(it.s2)}」" }, "${Place.HEAD}の「グループペア禁止」")
            report(p.cons41s, { listOf(it.groupIdx, it.shiftIdx, it.l, it.u) }, { "スキルグループのレンジ「${ssym(it.groupIdx)}・${symOf(it.shiftIdx)} ${bound(it.l, it.u)}」" }, "${Place.HEAD}の「スキルグループのレンジ」")
            report(p.cons42s, { listOf(it.g1, it.s1, it.g2, it.s2) }, { "スキルグループペア禁止「${ssym(it.g1)}の${symOf(it.s1)} ✕ ${ssym(it.g2)}の${symOf(it.s2)}」" }, "${Place.HEAD}の「スキルグループペア禁止」")
        }

        /**
         * [3.534.0/経緯は docs/history/3.4xx.md] 必須(c3)/推奨(c3m)はrun-deficit（L以上連続で満たす）、
         * 禁止(c3n,HARD)は窓マッチ（N連続の窓が1つでもあれば発火）。同じシフトでL>=Nなら必須を満たす
         * 連続は必ずN連続を含み常に未達＝探索の失敗でなく設定の衝突。c3mn(回避,SOFT)は対象外。
         */
        fun mustForbiddenSeqIssues() {
            fun singleShiftRun(row: C3Row): Pair<String, Int>? {
                val parts = c3SeqBody(row.pattern)
                if (parts.size < 2 || parts.any { it != parts[0] }) return null
                return parts[0] to parts.size
            }
            // 複数シフトの並びは「先頭を置いたら残りが続く」を求める（Evaluator.c3check）＝中に禁止の並びがあれば先頭を置くたびに
            //   どちらかが違反になる。単一シフト連どうしは下の連続数の比較が受け持つ。
            fun seqText(seq: IntArray) = seq.joinToString("→") { symOf(it) }
            fun containsRun(hay: IntArray, needle: IntArray): Boolean =
                needle.isNotEmpty() && (0..hay.size - needle.size).any { o -> needle.indices.all { hay[o + it] == needle[it] } }
            for ((famRaw, list) in listOf("c3" to p.cons3, "c3m" to p.cons3m)) for (c in list) {
                if (C3Run.isSingleShiftSeq(c.seq)) continue
                val n = p.cons3n.firstOrNull { containsRun(c.seq, it.seq) } ?: continue
                val famJp = c3FamilyJp(famRaw)
                out.add(SettingIssue(IssueKind.CONSTRAINT,
                    "「${seqText(c.seq)}」の並び（$famJp / 禁止の並び「${seqText(n.seq)}」）",
                    "$famJp「${seqText(c.seq)}」の中に禁止の並び「${seqText(n.seq)}」が入っているため両立できません" +
                        "（「${symOf(c.seq[0])}」を置くと、続けて並べれば禁止の並び、並べなければこの$famJp の違反になります）",
                    "${Place.EDIT}${Place.RULE}の「$famJp」か「禁止の並び」を見直すか、どちらかの行を削除してください"))
            }
            val forbidLen = HashMap<String, Int>()
            for (row in state.cons3n) {
                val (sym, n) = singleShiftRun(row) ?: continue
                forbidLen[sym] = minOf(forbidLen[sym] ?: Int.MAX_VALUE, n)
            }
            if (forbidLen.isEmpty()) return
            fun check(famRaw: String, rows: List<C3Row>) {
                val famJp = c3FamilyJp(famRaw)
                for (row in rows) {
                    val (sym, l) = singleShiftRun(row) ?: continue
                    val n = forbidLen[sym] ?: continue
                    if (l < n) continue
                    out.add(SettingIssue(IssueKind.CONSTRAINT,
                        "「$sym」の並び（$famJp ${l}連続 / 禁止の並び ${n}連続）",
                        "「$sym」を${l}連続以上にする$famJp が、「$sym」の${n}連続禁止と両立できません" +
                            "（${l}連続以上には必ず${n}連続が含まれるため、この$famJp は常に未達になります）",
                        "${Place.EDIT}${Place.RULE}の「$famJp」か「禁止の並び」で、「$sym」の連続数を見直すか、どちらかの行を削除してください"))
                }
            }
            check("c3", state.cons3)
            check("c3m", state.cons3m)
        }

        fun c1RuleIssues() {
            // 2b) [監査#8 / Web HF557 A4 の native 移植] 連勤・回数窓制約(cons1)の不能設定
            //   d1>期間: 窓が期間を超え、判定が一度も走らず無言で無効。 d2>d1: 物理的に不可能で全員・全窓が発火し続ける。
            // [3.475.0/論理監査] day1>T の行は 2m（p.c1OverT）が同じ行を「窓の要件」として案内する。ここでも
            //   出すと**同じ1行が名前の違う2件**として数えられていた（3.412.0 の検証は該当行を含まないデータ
            //   だけだった）。2m に一本化し、ここは day2>day1 だけを見る。
            for (c in p.cons1) {
                val sym = symOf(c.shiftIdx)
                if (c.day1 > p.T) {
                    continue
                } else if (c.day2 > c.day1) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "期間の制約「$sym ${c.day1}日で${c.day2}回以上」",
                        "${c.day1}日間に${c.day2}回は入りきらないため、「$sym」を担当する全員が期間中ずっと違反になります",
                        "${Place.EDIT}${Place.RULE}の「期間の制約」で回数を${c.day1}回以下に直すか、この行を削除してください"))
                }
            }

            // 2b-2) [壁/covO-tension 分類] c1 窓制約の充足可否。需要 = 各 canDo 職員 × day2 × floor(T/day1)（disjoint窓の下界）。
            //   [3.364.0 訂正・実データ計測起因] 非休シフトの供給に per-day 上限(need2/need1)の総和を使うのは誤り。
            //   need2/need1 は covO の SOFT 目標(1日あたりの過剰配置しきい値)であって物理上限ではなく、最適化は covO を
            //   払って上限を超えて配置できる。かつ day2<=day1 ガードより「物理供給(担当nCanDo人×T日) >= 需要」が**常に成立**する
            //   ので、非休の c1 窓は原理的に構造的不能にはならない（旧実装は golden の Dﾃ を「構造的に残る」と誤断定していたが、
            //   実データの手作り表は Dﾃ を上限超えの35回配置しており供給31は実上限でないことを実測で確認）。
            //   → 休のみ「S*T−Σ最小work需要」が実在の物理上限＝供給<需要なら真の壁。非休は上限が窓ルールに届かない場合のみ、
            //     c1 充足に過剰配置(covO)が要る旨を「解消不能ではないトレードオフ」として正直に案内する。read-only・スコア不変。
            run {
                var workMinDemand = 0
                // [3.409.22] 旧: need1 直読み＝need2 単独定義の需要を 0 と数え、休の供給を過大評価していた
                //   （＝真の壁を見逃す側。false wall は作らないので実害は軽いが値が不正確だった）。
                //   effectiveDemand はセルごとの真の最小＝過大にはならない（3.76.0「false wall を出さない」と両立）。
                // [3.475.0/論理監査] 担当可能人数を超える需要（埋まらない席＝forcedCovU ぶん）は作業セルを
                //   消費しないので、その分まで差し引くと休の供給を過小評価して false wall を出す。
                //   セルごとに min(需要, 担当可能人数) で数える（forcedCovU と同じ定義）。
                for (k in 0 until p.K) {
                    for (j in 0 until p.T) workMinDemand += minOf(effectiveDemand(p, k, j), placeableFor(p, k, j))
                }
                for (c in p.cons1) {
                    val si = c.shiftIdx
                    // 退化ケース(窓>期間 / 回数>窓)は 2b が別途案内。ここは通常窓のみ。
                    if (c.day1 <= 0 || c.day2 <= 0 || c.day1 > p.T || c.day2 > c.day1) continue
                    val disjoint = p.T / c.day1
                    if (disjoint <= 0) continue
                    val nCanDo = capableCount(si)
                    if (nCanDo == 0) continue   // 担当者ゼロは別の案内対象
                    val demand = nCanDo * c.day2 * disjoint
                    val sym = symOf(si)
                    if (si == p.restIdx) {
                        // 休は「作業に回さないセル数(S*T−最小work需要)」が実在の物理上限＝供給<需要なら真の構造的不能。
                        val supply = p.S * p.T - workMinDemand
                        if (supply < demand) {
                            out.add(SettingIssue(IssueKind.CONSTRAINT, "期間の制約「$sym ${c.day1}日で${c.day2}回以上」",
                                "「$sym」に回せるのは${supply}回分で、必要な${demand}回分（担当${nCanDo}人×${c.day2}回×${disjoint}区間）に${demand - supply}回分足りません。" +
                                    "この期間の制約の違反は構造的に残ります（何度つくっても消えません）",
                                "${Place.EDIT}${Place.NEED}で作業シフトの必要人数を下げて「$sym」に回せる日を増やすか、${Place.RULE}の「期間の制約」で回数を下げるか日数を延ばしてください"))
                        }
                    } else {
                        // 非休は物理供給(担当nCanDo人×T日)>=需要が常に成立＝壁ではない。per-day 上限(need2/need1)の総和が
                        //   窓ルールに届かない場合のみ、c1 充足に過剰配置(covO)が要る旨をトレードオフとして案内。
                        // [3.409.23/監査SANITY-5] 上限が**1日でも未設定**なら、その日は covO が構造的に発火しない
                        //   ＝「1日あたり上限」という前提そのものが成立しない。旧実装は未設定(-1)を
                        //   `coerceAtLeast(0)` で 0 に潰して合算していたため、一部の日だけ need を設定した
                        //   シフトで不足量が過大に出た（実測: 6日中 day0 のみ need1=1 → 「7回ぶんの過剰配置が
                        //   要ります」。実際に上限があるのは1日だけ）。しかも助言が指す罰(covO)がそのシフトには
                        //   存在しないので、従っても何も変わらない。前提が崩れている以上、案内しないのが正しい。
                        var capSum = 0
                        var capKnown = true
                        for (j in 0 until p.T) {
                            val cap = effectiveCap(p, si, j)
                            if (cap < 0) { capKnown = false; break }   // 未設定＝無制限
                            capSum += cap
                        }
                        if (capKnown && capSum < demand) {
                            val short = demand - capSum
                            out.add(SettingIssue(IssueKind.CONSTRAINT, "期間の制約「$sym ${c.day1}日で${c.day2}回以上」",
                                "「$sym」の1日あたりの上限人数の合計（${capSum}回）が、この期間の制約に要る回数（${demand}回）に${short}回分届きません。" +
                                    "満たすには一部の日で上限を超えて入れる（人員過剰）必要があります。解けない設定ではなく、人員過剰を少し出せば解消できます",
                                "${Place.EDIT}${Place.NEED}で「$sym」の上限人数を上げるか、${short}回分の人員過剰を許してください"))
                        }
                    }
                }
            }

            // 2b-3) [壁/ダイヤル分類・個人版/ドッグフーディングで発見、3.262.0で厳密化] 2b-2は全体供給(集計)
            //   のみ判定するため、「集計では担当者が大勢いて足りているのに、特定の1人だけは自分の個人上限
            //   (staffRange上限)のせいで自分自身の窓ルールを満たせない」局面を見逃していた（例: Aｱ担当可能者
            //   は全体で10人いても、ある1人だけAｱ個人上限が低く「14日窓でAｱ≥1」を自分では満たせない）。
            //   [3.262.0] 旧実装は2b-2と同じ非重複窓の粗い下界(day2×floor(T/day1))を使っていたが、これは
            //   スライド窓の真の必要量を過小評価する（実データ検証: 「15日窓4回以上」の粗い下界=8だが、
            //   実際に0違反へ到達するには9〜11日必要な職員が複数おり、粗い下界では「上限8/9で足りている」
            //   と誤って見逃していた＝false negative）。`SmartInitialScheduler.minDaysForFullCompliance`
            //   （0違反を達成する最小日数を厳密に求める）へ置換し、同一シフトの複数規則(例: 休の5日窓＋
            //   15日窓)も**同時充足**の真の必要量として厳密判定。
            run {
                val rulesByShift = LinkedHashMap<Int, MutableList<C1>>()
                for (c in p.cons1) {
                    if (c.shiftIdx !in 0 until p.K || c.day1 <= 0 || c.day2 <= 0 || c.day1 > p.T || c.day2 > c.day1) continue
                    rulesByShift.getOrPut(c.shiftIdx) { ArrayList() }.add(c)
                }
                for ((shiftIdx, rules) in rulesByShift) {
                    // [3.272.0] ConstraintMus.cachedMinDays（同じ純関数のプロセス全域キャッシュ）経由に統一。
                    val minNeeded = ConstraintMus.cachedMinDays(
                        p.T, rules.map { it.day1 to it.day2 },
                    ) ?: continue
                    val sym = symOf(shiftIdx)
                    val ruleDesc = rules.joinToString(" かつ ") { "${it.day1}日で${it.day2}回以上" }
                    for (i in 0 until p.S) {
                        if (!p.canDo(i, shiftIdx)) continue
                        val hi = p.rangeHi[i][shiftIdx]
                        if (hi == Int.MAX_VALUE || hi >= minNeeded) continue
                        val name = nameOf(i)
                        out.add(SettingIssue(IssueKind.RANGE, "${name}さんの「$sym」個人上限と期間の制約の衝突",
                            "期間の制約「$sym $ruleDesc」を同時に満たすには最低${minNeeded}回が必要ですが、" +
                                "${name}さんの「$sym」個人上限は${hi}回です。この人だけではどう配置しても期間の制約を満たせません",
                            "${Place.EDIT}${Place.COUNT}で${name}さんの「$sym」の個人上限を${minNeeded}回以上に上げるか、${Place.RULE}の「期間の制約」で回数を下げてください"))
                    }
                }
            }
        }

        fun unusableRowIssues() {
            // 2c) [監査#5] 担当可能者ゼロの回数制約(cons2) — canDoガード後は事実上無効になるため案内する。
            for (c in p.cons2) {
                val eligible = capableCount(c.shiftIdx)
                if (eligible == 0) {
                    val sym = symOf(c.shiftIdx)
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "個人の合計「$sym 合計${c.count}回以上」",
                        "このシフトを担当できる職員がいないため、この制約は事実上無効です",
                        "${Place.EDIT}${Place.SHIFT}の担当可否でどれかのグループが「$sym」を担当できるようにするか、${Place.RULE}の「個人の合計」でこの行を削除してください"))
                }
            }

            // 2d) [監査#9] 期間より長い連続パターン — パース段階で除外済み（Problem.c3OverT）。理由を案内する。
            for ((fam, seqStr) in p.c3OverT) {
                val famJp = c3FamilyJp(fam)
                val negative = fam == "c3n" || fam == "c3mn"
                out.add(SettingIssue(IssueKind.CONSTRAINT, "$famJp「$seqStr」",
                    if (negative) "並びの長さが期間${p.T}日を超えるため期間内に現れることがなく、この制約は無効です"
                    else "並びの長さが期間${p.T}日を超えるため期間内では満たせず、この制約は無効です",
                    "${Place.EDIT}${Place.RULE}の「$famJp」で並びを${p.T}日以下に短くするか、この行を削除してください"))
            }

            // 2m) [3.412.0/P-04] 期間より長い窓の要件 — 行は解決できるが `MirrorCore.checkC1Family` が
            //   `c.day1 > p.T` で無言に飛ばすため、評価もされず画面にも何も出ない状態だった。
            //   連続パターン(2d)と同じ形で理由を案内する。read-only・評価不変。
            for (row in p.c1OverT) {
                out.add(SettingIssue(IssueKind.CONSTRAINT, "期間の制約「$row」",
                    "日数が期間${p.T}日を超えるため、この決まりは評価されません（今の勤務表では常に無視されます）",
                    "${Place.EDIT}${Place.RULE}の「期間の制約」で日数を${p.T}日以下にするか、この行を削除してください"))
            }

            // 2e) [3.309.0] 存在しないシフト記号を含む連続パターン — パース段階で無言除外されていた。
            //   シフトの改名・削除でこうなる。禁止(c3n)なら HARD 制約が黙って無効化されるため必ず案内する。
            for ((fam, seqStr) in p.c3UnknownShift) {
                val famJp = c3FamilyJp(fam)
                out.add(SettingIssue(IssueKind.CONSTRAINT, "$famJp「$seqStr」",
                    "〈〉で囲んだ記号が今のシフト一覧にないため、この行は評価されていません" +
                        "（シフトを改名・削除するとこうなります）",
                    "${Place.EDIT}${Place.RULE}の「$famJp」でこの行の記号を今あるシフトに直すか、行を削除してください"))
            }

            // 2f) [3.320.0] 3.309.0 は連続パターンだけを直したが、**同じ無言除外が残り6族にもあった**
            //   （窓の要件・個人の合計・群/スキル群のレンジ・群/スキル群ペア禁止）。記号が今の一覧に無い、
            //   または日数・回数が空/非数値の行は `Problem` のパース段階で捨てられ、画面にもログにも
            //   出ないまま評価対象から消えていた。窓の要件は c1(SOFT)、群ペア禁止は実データでも発火する族。
            for ((famJp, rowStr) in p.unresolvedRows) {
                // グループ／スキルグループの族は④、期間の制約・個人の合計・希望の前日に禁止は⑤にある。
                val section = if ("グループ" in famJp) Place.HEAD else Place.RULE
                out.add(SettingIssue(IssueKind.CONSTRAINT, "$famJp「$rowStr」",
                    "この行は評価されていません。〈〉で囲んだ記号が今の一覧にないか、日数・回数が空か数値でない" +
                        "ためです（シフトやグループを改名・削除するとこうなります）",
                    "${Place.EDIT}${section}の「$famJp」で、この行を今ある記号・正しい数値に直すか、行を削除してください"))
            }

            // 2g) [3.603.0/backlog#24] 休(ShiftRole.Rest)がどのシフトにも付与されていない＝
            //   最適化/検査の入口(build()のwarns)でブロックされる設定。ここでは案内の詳細を出す
            //   （旧: 記号"休"の字面一致に失敗すると index0 へ黙ってフォールバックしていたが撤去済み）。
            if (state.shifts.none { it.role == ShiftRole.Rest }) {
                out.add(SettingIssue(IssueKind.CONSTRAINT, "「休」のシフトがありません",
                    "休みとして扱うシフトが無いため、勤務表をつくることも問題がないか調べることもできません" +
                        "（曜日の偏りや休み関連の診断も休を前提に動きます）",
                    "${Place.EDIT}${Place.SHIFT}のシフト種別で休みにするシフトを開き、「休みとして扱う」をオンにしてください"))
            }

            // 2j) [3.349.0/ユーザー確認「最大期間一ヶ月です」] 業務前提は **職員30名以内・期間1か月(31日)以内**。
            //   これまでこの前提は文書（CLAUDE.md）にしかなく、**コードはどこでも強制も確認もしていなかった**
            //   （`dayCount` は盤面の列数から導出するだけ）。前提を超えた入力でも実行はできるが、
            //   64日を境に `C3nBitScan`（c3n のビット走査）と C++ `SaChunk` の bitmask 経路が
            //   スカラー退避へ落ち、探索が目に見えて遅くなる。**止めない**（実行できるものを止めない）。
            //   知らせるだけ＝read-only・スコアリング不変。
            if (state.dayCount > 31) {
                val slow = if (state.dayCount > 64) "。64日を超えると計算がかなり遅くなります" else ""
                out.add(SettingIssue(IssueKind.DEMAND, "対象期間が1か月を超えています",
                    "対象期間が${state.dayCount}日（${safeDayLabel(state.startDate, 0)}〜${safeDayLabel(state.startDate, state.dayCount - 1)}）あります。想定は1か月（31日）以内です$slow",
                    "${Place.EDIT}${Place.MONTH}で月を選び直して1か月以内にするか、月ごとに分けて作成してください"))
            }
            if (state.staffCount > 30) {
                out.add(SettingIssue(IssueKind.DEMAND, "職員数が想定を超えています",
                    "職員が${state.staffCount}名います。想定は30名以内です",
                    "職員を分けて作成するか、この規模で使う場合は計算時間が延びることを見込んでください"))
            }
        }

        fun nonNumericIssues() {
            // 2h) [3.327.0/外部レビュー High4] **捨てられずに fail-open で解釈される**数値。
            //   2f が拾うのは `Problem` がパースに失敗して行ごと捨てたものだけ。一方
            //   `staffRange` の lo/hi・`cons41(s)` の l/u・シフトの必要人数は、非数値でも行は生き残り
            //   **空欄と同じ扱い**になる（staffRange は `toIntOrNull()?.let{}` を素通り＝未設定センチネル
            //   `Int.MIN_VALUE`/`MAX_VALUE` のまま、必要人数は `?: -1`＝要件なし）。
            //   **空欄＝未設定は正しい仕様**なので対象にせず、
            //   「空でないのに数値でない」ものだけを出す（弱い問題を解いて成功扱いになるのを防ぐ）。
            // [3.509.1] 負数も対象（Problem は負数を未設定として捨てる＝空欄と同じ扱いになる）。
            fun badNum(v: String): Boolean = v.isNotBlank() && (v.trim().toIntOrNull() ?: -1) < 0
            for ((key, r) in state.staffRange) {
                if (!badNum(r.lo) && !badNum(r.hi)) continue
                val idx = key.split(",")
                val nm = idx.getOrNull(0)?.toIntOrNull()?.let { state.staff.getOrNull(it)?.name } ?: key
                val sy = idx.getOrNull(1)?.toIntOrNull()?.let { state.shifts.getOrNull(it)?.kigou } ?: ""
                out.add(SettingIssue(IssueKind.CONSTRAINT, "個人の回数「$nm $sy」",
                    "下限「${r.lo}」上限「${r.hi}」に数値でない値または負の値があります。その側は制限なしとして" +
                        "扱われるため、意図より弱い条件で計算されます",
                    "${Place.EDIT}${Place.COUNT}で${nm}さんの「$sy」の下限・上限を数値で入れ直すか、制限しないなら空欄にしてください"))
            }
            for (sh in state.shifts) {
                if (!badNum(sh.need1) && !badNum(sh.need2)) continue
                out.add(SettingIssue(IssueKind.CONSTRAINT, "必要人数「${sh.kigou}」",
                    "最低人数「${sh.need1}」上限人数「${sh.need2}」に数値でない値があります。その側は" +
                        "未設定（要件なし）として扱われます",
                    "${Place.EDIT}${Place.SHIFT}のシフト種別で「${sh.kigou}」の最低人数・上限人数を数値で入れ直すか、設定しないなら空欄にしてください"))
            }
            fun checkRange(famJp: String, fam: String, rows: List<com.magi.app.model.C41Row>) {
                for (c in rows) {
                    if (badNum(c.l) || badNum(c.u)) {
                        out.add(SettingIssue(IssueKind.CONSTRAINT, "$famJp「${c.groupKigou} ${c.shiftKigou}」",
                            "下限「${c.l}」上限「${c.u}」に数値でない値または負の値があります。その側は制限なしとして" +
                                "扱われるため、意図より弱い条件で計算されます",
                            "${Place.EDIT}${Place.HEAD}の「$famJp」でこの行を数値で入れ直すか、制限しないなら空欄にしてください"))
                        continue
                    }
                    // [3.399.0] 下限>上限。engine は `z < l || z > u` で判定するため、l>u だと**どの人数でも
                    //   必ずどちらかが真**＝その群×シフトは**期間の全日が違反**になり、しかも何をしても消えない。
                    //   個人の回数(staffRange)は同じ矛盾を既に検出してワンタップ修正まで出しているのに、
                    //   群/スキル群のレンジだけ取り残されていた（3.327.0 の 2h は「数値でない」しか見ていない）。
                    rangeOrderConflict(c.l, c.u)?.let { (lo, hi) ->
                        out.add(SettingIssue(IssueKind.CONSTRAINT, "$famJp「${c.groupKigou} ${c.shiftKigou}」",
                            "下限$lo > 上限$hi で矛盾しています。この組み合わせは期間の全日が違反になり、" +
                                "勤務表をどう組んでも消えません",
                            "${Place.EDIT}${Place.HEAD}の「$famJp」で下限を上限以下に直してください",
                            action = SettingFixAction.CLAMP_GROUP_RANGE_LO,
                            actionLabel = "下限を${hi}に下げる",
                            newLo = hi.toString(), groupRangeFamily = fam, groupRangeRow = c))
                    }
                }
            }
            checkRange("グループのレンジ", "c41", state.cons41)
            checkRange("スキルグループのレンジ", "c41s", state.cons41s)
            // [3.328.0/外部レビュー] 日別の必要人数と適切回数も同じ穴。とくに needDay は
            //   `needAt` が非数値のとき**シフト既定値へ黙って読み替える**ので、0 になるより性質が悪い
            //   （その日だけ意図と違う人数で計算され、画面には何も出ない）。
            for ((map, jp) in listOf(state.needDay1 to "最低人数", state.needDay2 to "上限人数")) {
                val bad = map.entries.filter { badNum(it.value) }.sortedBy { it.key }
                if (bad.isEmpty()) continue
                val where = bad.take(Guidance.PREVIEW).joinToString("・") { e ->
                    val kj = e.key.split(",")
                    val sy = kj.getOrNull(0)?.toIntOrNull()?.let { state.shifts.getOrNull(it)?.kigou } ?: e.key
                    val d = kj.getOrNull(1)?.toIntOrNull()?.let { safeDayLabel(state.startDate, it) } ?: ""
                    "$sy $d「${e.value}」"
                }
                out.add(SettingIssue(IssueKind.CONSTRAINT, "日別の$jp",
                    "${bad.size}件（$where${if (bad.size > Guidance.PREVIEW) " ほか" else ""}）が数値ではありません。" +
                        "その日は標準の必要人数で計算されます（例外を設定したつもりでも反映されません）",
                    "${Place.EDIT}${Place.NEED}でその日の必要人数を数値で入れ直すか、例外にしないなら${Place.NEED_DAYS}で削除してください"))
            }
            run {
                val bad = ArrayList<String>()
                for ((g, row) in state.groupShiftApt.withIndex()) for ((k, v) in row.withIndex()) {
                    if (!badNum(v)) continue
                    val gk = state.groups.getOrNull(g)?.kigou ?: "#$g"
                    val sk = state.shifts.getOrNull(k)?.kigou ?: "#$k"
                    bad.add("$gk $sk「$v」")
                }
                if (bad.isNotEmpty()) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "適切回数（1人あたりの目標）",
                        "${bad.size}件（${bad.take(Guidance.PREVIEW).joinToString("・")}${if (bad.size > Guidance.PREVIEW) " ほか" else ""}）が" +
                            "数値ではありません。目標なしとして扱われます",
                        "${Place.EDIT}${Place.COUNT}で目標を数値で入れ直すか、目標にしないなら空欄にしてください"))
                }
            }
        }

        fun assignmentIssues() {
            // 2i) [3.327.0/外部レビュー High5] スキル群の割当が範囲外（-1＝未所属でも一覧の index でもない値）。
            //   `Problem` は `skillIdx` を素通しする（native は 3.311.0 で巨大確保だけ防いでいるが、意味論は
            //   検証していない）。範囲外だと `ssk[i]==groupIdx` が常に偽＝その職員がスキル群の制約から
            //   **静かに外れる**。自動で書き換えると意味が変わるので**知らせるだけ**にする。範囲内の値が意図した
            //   所属かどうかは見ない（最初の 1 群を作るときの取り違えは `Ws1Ops.addSkillGroup` が防ぐ）。
            if (state.skillGroups.isNotEmpty()) {
                val bad = state.staff.withIndex().filter { (_, st2) ->
                    st2.skillIdx != -1 && st2.skillIdx !in state.skillGroups.indices
                }
                if (bad.isNotEmpty()) {
                    val names = bad.take(Guidance.NAME_PREVIEW).joinToString("・") { it.value.name.ifBlank { "#${it.index}" } }
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "スキルグループの割当",
                        "${bad.size}名（$names${if (bad.size > Guidance.NAME_PREVIEW) " ほか" else ""}）のスキルグループが今の一覧の範囲外です。" +
                            "この職員はスキルグループのルールから外れて計算されます",
                        "${Place.EDIT}${Place.STAFF}でスキルグループを選び直すか、所属させないなら「(なし)」にしてください"))
                }
            }

            // 2k) [3.410.0/P-06] グループの割当が範囲外。2i（スキル群）と対になる検査で、**こちらだけ
            //   取り残されていた**。`skillIdx` は -1 が正規の「未所属」で範囲外でも安全側に外れるだけだが、
            //   `groupIdx` は `bucket[sgrp[i]]` / `grpCnt[sgrp[i]*K+k]` の添字なので**範囲外だと落ちる**。
            //   そのため `Problem` 側は先頭群へ寄せて動かし続ける（クラッシュさせない）が、寄せた事実は
            //   黙っていると「別の群のルールが静かに掛かる」ので、ここで必ず知らせる。
            if (p.outOfRangeGroupStaff.isNotEmpty()) {
                val bad = p.outOfRangeGroupStaff
                val names = bad.take(Guidance.NAME_PREVIEW).joinToString("・") { i ->
                    state.staff.getOrNull(i)?.name?.ifBlank { "#$i" } ?: "#$i"
                }
                val head = state.groups.firstOrNull()?.kigou?.ifBlank { "先頭のグループ" } ?: "先頭のグループ"
                out.add(SettingIssue(IssueKind.CONSTRAINT, "グループの割当",
                    "${bad.size}名（$names${if (bad.size > Guidance.NAME_PREVIEW) " ほか" else ""}）のグループが今の一覧の範囲外です。" +
                        "計算では「$head」に所属しているものとして扱っています。そのため担当できるシフトが意図と違います",
                    "${Place.EDIT}${Place.STAFF}で名前を押し、グループを選び直してください"))
            }

            // 2l) [3.410.0/W-03] 担当できるシフトが1つも無いグループ。`Ws1Ops.setGroupShift` は全部 OFF に
            //   できるが（検証も拒否もしない）、そうするとその群の職員は `allowedShiftsForStaff` が空＝
            //   `?: restIdx` のフォールバックで**休しか置けなくなる**。必要人数のある日は軒並み covU になるのに、
            //   画面はチェックが外れていることしか示さない。所属者がいるときだけ知らせる（空の群は無害）。
            for (g in state.groups.indices) {
                val row = state.groupShift.getOrNull(g) ?: emptyList()
                if (row.any { it == 1 }) continue
                val members = state.staff.count { it.groupIdx == g }
                if (members == 0) continue
                val gname = state.groups[g].kigou.ifBlank { "#$g" }
                out.add(SettingIssue(IssueKind.CONSTRAINT, "担当できるシフト",
                    "グループ「$gname」（${members}名）は担当できるシフトが1つもありません。この職員は休しか置けず、" +
                        "必要人数のある日はすべて人員不足になります",
                    "${Place.EDIT}${Place.SHIFT}の担当可否で、グループ「$gname」が担当するシフトを選んでください"))
            }
        }

        fun demandCapacityIssues() {
            // 3) 需要 > 担当可能人数（その枠は誰をどう並べても必ず不足）
            //    直し方は「その日の例外」が基本（標準の必要数を下げると他の日まで動く）。全日が同じ上限で不足し、
            //    日別の例外が1つも無いときだけ、1件にまとめて標準を下げる（31 回タップさせない）。
            for (k in 0 until p.K) {
                val sym = symOf(k)
                val short = ArrayList<Triple<Int, Int, Int>>()   // (day, need, capable)
                for (j in 0 until p.T) {
                    val need = effectiveDemand(p, k, j)
                    if (need <= 0) continue
                    val capable = placeableFor(p, k, j)   // [3.507.5] 置ける人数（forcedCovU と同じ定義）
                    if (need > capable) short.add(Triple(j, need, capable))
                }
                if (short.isEmpty()) continue
                val caps = short.map { it.third }.toSet()
                val hasDayException = (0 until p.T).any { j ->
                    "$k,$j".let { key -> state.needDay1[key].isNullOrBlank().not() || state.needDay2[key].isNullOrBlank().not() }
                }
                if (p.T >= 2 && short.size == p.T && caps.size == 1 && !hasDayException) {
                    val capable = caps.first()
                    val need = short.first().second
                    out.add(SettingIssue(IssueKind.DEMAND, "全日 $sym",
                        "必要${need}人ですが担当できるのは${capable}人だけです",
                        "${Place.EDIT}${Place.NEED}で「$sym」の標準の必要人数を${capable}人以下に下げるか、${Place.SHIFT}の担当可否で担当できる職員を増やしてください",
                        action = SettingFixAction.CAP_DEMAND, actionLabel = "必要数を${capable}人に下げる",
                        demandShiftIdx = k, demandCap = capable))
                    continue
                }
                for ((j, need, capable) in short) {
                    out.add(SettingIssue(IssueKind.DEMAND, "${safeDayLabel(state.startDate, j)} $sym",
                        "必要${need}人ですが担当できるのは${capable}人だけです",
                        "${Place.EDIT}${Place.NEED}で「$sym」のこの日の必要人数を${capable}人以下に下げるか、${Place.SHIFT}の担当可否で担当できる職員を増やしてください",
                        action = SettingFixAction.CAP_DEMAND, actionLabel = "必要数を${capable}人に下げる",
                        demandShiftIdx = k, demandCap = capable, demandDayIdx = j))
                }
            }
        }

        fun staffRangeIssues() {
            // 4) 回数レンジ(staffRange)の設定ミス
            for ((key, r) in state.staffRange) {
                val parts = key.split(',')
                val i = parts.getOrNull(0)?.toIntOrNull()
                val k = parts.getOrNull(1)?.toIntOrNull()
                val lo = r.lo.trim().toIntOrNull()
                val name = i?.let { state.staff.getOrNull(it)?.name } ?: "#$i"
                val sym = k?.let { state.shifts.getOrNull(it)?.kigou } ?: "$k"
                if (i == null || k == null || i !in 0 until p.S || k !in 0 until p.K) {
                    out.add(SettingIssue(IssueKind.RANGE, "個人の回数「$key」", "対象職員/シフトが範囲外です", "${Place.EDIT}${Place.COUNT}で正しい職員・シフトに付け直してください"))
                    continue
                }
                rangeOrderConflict(r.lo, r.hi)?.let { (cLo, cHi) ->
                    out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」回数", "下限$cLo > 上限$cHi で矛盾しています", "${Place.EDIT}${Place.COUNT}で${name}さんの「$sym」の下限を上限以下に直してください",
                        action = SettingFixAction.CLAMP_RANGE_LO, actionLabel = "下限を${cHi}に下げる", rangeKey = key, newLo = cHi.toString()))
                }
                if (lo != null && lo > 0 && !p.canDo(i, k)) {
                    val gsym = state.groups.getOrNull(p.sgrp[i])?.kigou ?: "?"
                    out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」回数", "担当できないシフトに下限${lo}が設定されています",
                        "${Place.EDIT}${Place.COUNT}で下限を0にするか、${Place.SHIFT}の担当可否でグループ「$gsym」が「$sym」を担当できるようにしてください",
                        action = SettingFixAction.ZERO_RANGE_LO, actionLabel = "下限を0にする", rangeKey = key, newLo = "0"))
                }
                if (lo != null && lo > p.T) {
                    out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」回数", "下限${lo}が期間日数(${p.T}日)を超えています", "${Place.EDIT}${Place.COUNT}で${name}さんの「$sym」の下限を${p.T}以下に直してください",
                        action = SettingFixAction.CLAMP_RANGE_LO, actionLabel = "下限を${p.T}に下げる", rangeKey = key, newLo = p.T.toString()))
                }
            }

            // 5) 1人の各シフト下限の合計が期間日数を超える（割り当て不能）
            for (i in 0 until p.S) {
                var sumLo = 0
                val parts = ArrayList<String>()
                for (k in 0 until p.K) {
                    val lo = p.rangeLo[i][k]
                    // [3.475.0/論理監査] チェッカーの low は canDo 必須（担当できないシフトの下限は評価上ゼロ＝
                    //   検査4が別途案内する）。合算に含めると「担当可の下限を下げろ」と誤った指示になる。
                    //   restCapacity と同じ canDo ガードに揃える。
                    if (lo != Int.MIN_VALUE && lo > 0 && p.canDo(i, k)) { sumLo += lo; parts.add("${symOf(k)} ${lo}回") }
                }
                if (sumLo > p.T) {
                    val name = nameOf(i)
                    out.add(SettingIssue(IssueKind.RANGE, "$name の回数下限の合計",
                        "各シフトの下限の合計が${sumLo}回（${parts.joinToString("・")}）で、期間日数(${p.T}日)を超えています",
                        "${Place.EDIT}${Place.COUNT}で${name}さんのどれかのシフトの下限を下げ、合計を${p.T}以下にしてください"))
                }
            }
        }

        fun seatIssues() {
            // 6) [事前診断] シフト単位の構造的な過拘束（席数 vs 下限/上限の合計）。実行前に「何をしても無理」を提示し、
            //    無駄な最適化(数分)を避ける。誤検知を避けるため、明確に矛盾する2ケースのみ（読み取り専用・データ不変）。
            for (k in 0 until p.K) {
                var seatsLo = 0; var seatsHi = 0; var hasDemand = false
                // [3.475.0/論理監査] 必要人数が未定義の日は「席0」ではなく「上限なし（罰の無い日）」。
                //   その日を 0 席として seatsHi に合算すると、実際には自由に置けるのに「過剰配置か下限割れが
                //   必ず出ます」と断定する false positive になる（3.409.23 が 2b-2 に入れた capKnown と同じ穴）。
                //   1日でも未定義なら seatsHi は上限として使わない（seatsLo は下界なのでそのまま）。
                var capKnown = true
                for (j in 0 until p.T) {
                    // [3.409.22] 旧: `need1<0 → continue` で need2 単独定義の日を丸ごと落としていた
                    //   （hasDemand も立たず、そのシフトの検査自体が走らなかった）。実効値へ委譲する。
                    if (!needDefined(p, k, j)) { capKnown = false; continue }   // need 未設定の日は対象外
                    hasDemand = true
                    seatsLo += maxOf(effectiveDemand(p, k, j), 0)
                    seatsHi += maxOf(effectiveCap(p, k, j), 0)
                }
                // [3.316.0] 休は need に依存しない実質上限（restCapacity）で判定するので、必要人数が1日も
                //   設定されていなくても検査する（3.301.1 で適切回数の検査に同じ変更を入れたのと同じ理由）。
                if (!hasDemand && k != p.restIdx) continue
                val sym = symOf(k)
                var capable = 0; var loSum = 0; var capSum = 0; var allCapped = true
                for (i in 0 until p.S) {
                    if (!p.canDo(i, k)) continue
                    capable++
                    val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
                    if (lo != Int.MIN_VALUE && lo > 0) loSum += lo
                    if (hi != Int.MAX_VALUE) capSum += hi else allCapped = false
                }
                // A) 下限の合計 > それを受け止められる上限 → 全員の下限を満たすと過剰配置/下限割れが不可避。
                // [3.316.0] 休だけ比較対象を restCapacity にする。旧実装は休も必要人数の合計(seatsHi)と
                //   比べており、休に need1=0 が明示設定された実データ（real/user）では seatsHi=0 に対し
                //   下限合計80 で**必ず誤警告**が出ていた（休に「1日に何人休んでよいか」の座席は無い）。
                //   3.235.0 で適切回数(6-C)には同じ理由で restCapacity 比較を入れたが、この検査は取り残していた。
                val loCapacity = if (k == p.restIdx) restCapacity(p) else seatsHi
                if ((k == p.restIdx || capKnown) && loSum > loCapacity) {
                    out.add(SettingIssue(IssueKind.DEMAND, "「$sym」の回数下限の合計",
                        if (k == p.restIdx)
                            "担当${capable}人の下限の合計が${loSum}回ですが、他シフトの個人下限を差し引いた「$sym」の" +
                                "最大可能日数の合計は${loCapacity}回しかありません。全員の下限は同時に満たせず、下限割れが必ず出ます"
                        else
                            "担当${capable}人の下限の合計が${loSum}回ですが、必要数の合計は${loCapacity}回しかありません。全員の下限は同時に満たせず、人員過剰か下限割れが必ず出ます",
                        if (k == p.restIdx) "${Place.EDIT}${Place.COUNT}で「$sym」の個人下限を下げるか、他シフトの個人下限を見直してください"
                        else "${Place.EDIT}${Place.COUNT}で「$sym」の個人下限を下げるか、${Place.NEED}で「$sym」の必要人数を増やしてください"))
                }
                // B) 全担当者に上限があり、上限の合計 < 必要数。
                //   [3.406.0] 旧文言は「人員不足(covU)が不可避」と断定していたが、**個人上限は SOFT(high)で
                //   超過できる**ため covU は不可避ではない。実機ログ(2026-08-19)がそれを反証している——
                //   Cｵ は 需要30 vs 上限計24 で本検査が発火したのに、結果は covU=0・high=6（＝ちょうど 30−24）。
                //   証明できるのは**和の下界**だけ: 全員が上限を守れば配置は capSum 以下なので covU ≥ 差、
                //   上限を1回破るごとに covU が1つ high に置き換わる ⇒ **covU + high ≥ seatsLo − capSum**。
                //   3.354.0 で apt+high について同じ形の下界を立てたのと同じ扱いにする。
                if (capable > 0 && allCapped && capSum < seatsLo) {
                    val gap = seatsLo - capSum
                    out.add(SettingIssue(IssueKind.DEMAND, "「$sym」の必要人数",
                        "必要数の合計は${seatsLo}回ですが、担当${capable}人の上限の合計は${capSum}回しかありません。" +
                            "個人上限を守る限り${gap}回ぶんは埋まりません（実際には人員不足と上限超過が" +
                            "合わせて${gap}回ぶん必ず残ります。どちらに出るかは他の条件との兼ね合いで決まります）",
                        "${Place.EDIT}${Place.COUNT}で「$sym」の個人上限を上げるか、${Place.SHIFT}の担当可否で担当者を増やすか、${Place.NEED}で必要人数を下げてください"))
                }
            }
            // C) 適切回数(apt=職員のレパートリー目標)の合計 > それを受け止められる上限 → 全員の目標を満たすと
            //    目標割れ(aptLow)か過剰配置(covO/aptHigh)が必ず出る。レパートリーと被覆が両立しない設定ズレ。
            //    休(restIdx)は「1日に何人休んでよいか」という座席上限を持たないため、必要数の合計ではなく
            //    「各職員が他シフトの個人下限を満たしたうえで最大何日休めるか」の合計と比較する。
            //    [3.301.0] 判定は aptBalances() に集約した。設定画面の「目標」カードが同じ関数を読むため、
            //    「診断は警告を出すのに設定画面は何も言わない」というズレが構造的に起きない。
            for (b in aptBalances(state, p)) {
                if (!b.overloaded) continue
                out.add(SettingIssue(IssueKind.DEMAND, "「${b.kigou}」の適切回数の合計",
                    if (b.isRest)
                        "担当者の適切回数（1人あたりの目標）の合計が${b.aptSum}回ですが、他シフトの個人下限を差し引いた「${b.kigou}」の" +
                            "最大可能日数の合計は${b.capacity}回しかありません。全員の目標は同時に満たせず、目標割れか過剰配置が必ず出ます"
                    else
                        "担当者の適切回数（1人あたりの目標）の合計が${b.aptSum}回ですが、必要数の合計は${b.capacity}回しかありません。" +
                            "全員の目標は同時に満たせず、目標割れか人員過剰が必ず出ます",
                    if (b.isRest) "${Place.EDIT}${Place.COUNT}で「${b.kigou}」の適切回数を下げるか、他シフトの個人下限を見直してください"
                    else "${Place.EDIT}${Place.COUNT}で「${b.kigou}」の適切回数を下げるか、${Place.NEED}で「${b.kigou}」の必要人数を増やしてください"))
            }
        }

        fun forcedCountIssues() {
            // 6b) [事前診断/幻のapt目標] 担当レパートリーから強制される最低回数 > apt目標 → 目標は構造的に達成不能。
            //    全日はいずれかの担当可シフトで埋まるため、シフト k の回数には
            //      count(k) >= T − Σ_{k'≠k, 担当可} min(上限(k'), T)
            //    の下界が成立（他シフトの上限合計では期間を埋めきれない分が k に必ず回る）。この強制下限が
            //    apt目標を超えるなら、超過(aptHigh)は何をどう最適化しても残る＝データ側の目標を直すのが正道。
            //    例: 担当={休,B4,有}・休10-10固定・有1-1固定・31日 → B4は最低20回。目標1は幻のaptHigh違反。
            //    上限未設定の他シフトが1つでもあれば下界は0以下＝発火しない（誤検知ゼロの保守的判定・読み取り専用）。
            for (i in 0 until p.S) {
                val name = nameOf(i)
                for (k in 0 until p.K) {
                    // [3.508.0] 設定した目標(aptRaw)で判定する。実効目標(apt)は到達下限へ切り上げ済みなので
                    //   違反としては出ないが、設定が届かないことは言い続ける（直すのはデータ側）。
                    val t = p.aptRaw[i][k]
                    if (t < 0 || !p.canDo(i, k)) continue
                    val otherHiSum = otherShiftCapSum(p, i, k)
                    val forcedMin = p.T - otherHiSum
                    if (forcedMin > t) {
                        val sym = symOf(k)
                        out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」適切回数",
                            "担当できるシフトの構成上、他の担当シフトの個人上限（合計${otherHiSum}回）を守る限り「$sym」は最低${forcedMin}回になります（${p.T}日を埋めきれないぶんが必ず回ってくる）。" +
                                "設定の適切回数${t}回には届かないため、計算では${p.apt[i][k]}回を目標として扱っています（差${forcedMin - t}回は違反には出ません）",
                            "${Place.EDIT}${Place.COUNT}で「$sym」の適切回数を${forcedMin}回以上にするか空欄にする、または他シフトの担当・上限を見直してください"))
                    }
                }
            }

            // 6d) [3.373.0/実機ログ起因・希望で固定された回数 > apt目標] 希望どおりに置かれるセルは動かせない
            //    （希望を破る代金 pref(HARD) は apt(SOFT) の利得では絶対に釣り合わない＝`wishLocked` は探索・研磨の
            //    全パスが尊重する）。よってシフト k について「担当可能な希望が W 件」あれば count(k) >= W が
            //    どの解でも成立し、W が apt目標 t を超えるなら超過(aptHigh)は W−t 回ぶん必ず残る。
            //    [発見の経緯] 実機ログ(2026-08-15)で 大島愛 が 休17回・目標10 のまま動かず、apt が停滞していた。
            //    同型構造を合成して測ったところ、休の希望を 0件→17件 と増やすと AptPolish の到達点が
            //    休10(apt 0・採用5手) → 休17(apt 14・採用0手) へ単調に悪化し、**17件で実機の観測と完全に一致**した
            //    ＝最適化の不具合ではなく希望ロックが正しく効いている。しかし当時どの診断もその理由を述べず
            //    （6b は担当レパートリー由来の下限のみ・検査9 は個人上限との矛盾のみを見る）、利用者には
            //    「直せない apt 違反」が理由不明のまま残っていた。読み取り専用・データは変更しない。
            for (i in 0 until p.S) {
                val name = nameOf(i)
                for (k in 0 until p.K) {
                    val t = p.aptRaw[i][k]   // [3.508.0] 6b と同じく設定した目標で判定
                    if (t < 0 || !p.canDo(i, k)) continue
                    val days = wishedDays(i, k)
                    val wished = days.size
                    if (wished > t) {
                        val sym = symOf(k)
                        out.add(SettingIssue(IssueKind.RANGE, "$name の「$sym」適切回数と希望",
                            "「$sym」の希望が${wished}件（${daysText(days)}）あり、適切回数の目標${t}回を超えています。希望どおりに配置する限り" +
                                "「$sym」は必ず${wished}回以上になります。計算では${p.apt[i][k]}回を目標として扱うため差${wished - t}回は違反には出ませんが、設定の目標には届きません",
                            "${Place.EDIT}${Place.COUNT}で「$sym」の適切回数を${wished}回以上にするか、${Place.WISH}で${name}さんと相談して「$sym」の希望を${wished - t}件減らしてください"))
                    }
                }
            }

            // 6c) [事前診断/幻のhigh超過+代用要員提示・grilling確定=美幸・上條・大島の実例を踏まえ実装]
            //    6bと同じ「担当レパートリーから強制される最低回数」ロジックを staffRange 上限(hi、個人上限)
            //    にも適用。担当できるシフトの構成上、あるシフトの回数が個人上限を必ず上回ってしまう（他の
            //    担当シフトの上限合計だけでは全日を埋めきれない＝残りが必ずこのシフトに回る）場合、その
            //    職員をこのシフトの担当から外し、代わりに担当できる他の職員（代用要員候補）に置き換える
            //    ことを提案する。データは変更しない（HF77準拠、実際の担当変更は業務担当者が判断）。
            //    他シフトに上限未設定が1つでもあれば下界0以下＝発火しない（6bと同じ保守的判定・誤検知ゼロ）。
            for (i in 0 until p.S) {
                val name = nameOf(i)
                for (k in 0 until p.K) {
                    val hi = p.rangeHi[i][k]
                    if (hi == Int.MAX_VALUE || !p.canDo(i, k)) continue
                    val otherHiSum = otherShiftCapSum(p, i, k)
                    val forcedMin = p.T - otherHiSum
                    if (forcedMin > hi) {
                        val sym = symOf(k)
                        val substitutes = (0 until p.S).filter { it != i && p.mayPlace(it, k) }   // [3.507.5] 置ける人だけ
                            .map { state.staff.getOrNull(it)?.name ?: "#$it" }
                        val subText = if (substitutes.isEmpty()) "代用できる他の担当者がいません"
                            else "代用要員候補: ${substitutes.joinToString("・")}"
                        out.add(SettingIssue(IssueKind.RANGE, "${name}さんの「$sym」上限と担当構成の衝突",
                            "担当できるシフトの構成上、「$sym」は最低${forcedMin}回になります（他の担当シフトの上限合計${otherHiSum}回では${p.T}日を埋めきれません）が、${name}さんの「$sym」上限は${hi}回です。この人が担当を続ける限り上限超過は必ず出ます。$subText",
                            "${Place.EDIT}${Place.STAFF}で所属グループを変えて${name}さんを「$sym」の担当から外し代用要員に置き換えるか、${Place.COUNT}で上限を${forcedMin}回以上に上げてください"))
                    }
                }
            }

            // 6e) [grilling確定・希望件数>個人上限] 6dのapt版と同型。希望どおりに置かれるセルは
            //    Level Zero(wishLocked)で絶対に動かせないため、希望件数が個人上限を超えていれば
            //    上限超過(high)は解消できない（3.521.0、詳細は docs/history/3.4xx.md）。読み取り専用。
            for (i in 0 until p.S) {
                val name = nameOf(i)
                for (k in 0 until p.K) {
                    val hi = p.rangeHi[i][k]
                    if (hi == Int.MAX_VALUE || !p.canDo(i, k)) continue
                    val days = wishedDays(i, k)
                    val wished = days.size
                    if (wished > hi && hi == 0) {
                        // 上限 0 は意図した「入れない指定」（3.507.0）＝設定ミスと呼ばない。
                        out.add(SettingIssue(IssueKind.RANGE, "${name}さんの「${symOf(k)}」$ZERO_CAP_LABEL",
                            "$ZERO_CAP_LABEL のシフトに希望が${wished}件（${daysText(days)}）載っています。希望どおり入るので要調整に数えます",
                            "${Place.EDIT}${Place.WISH}で${name}さんと希望を確かめてください", neutral = true, zeroCap = true))
                    } else if (wished > hi) {
                        val sym = symOf(k)
                        out.add(SettingIssue(IssueKind.RANGE, "${name}さんの「$sym」個人上限と希望の衝突",
                            "「$sym」の希望が${wished}件（${daysText(days)}）あり、個人上限${hi}回を超えています。希望どおりに配置する限り" +
                                "「$sym」は必ず${wished}回以上になるため、上限超過は解消できません",
                            "${Place.EDIT}${Place.COUNT}で${name}さんの「$sym」の個人上限を${wished}回以上に上げるか、${Place.WISH}で${name}さんと相談して「$sym」の希望を${wished - hi}件減らしてください"))
                    }
                }
            }
        }

        fun forcedCovUIssues() {
            // 7) [事前診断/配布不可] ある日に「そのシフトを担当できる人数」より必要人数が多い＝どう割り当てても
            //    人員不足(covU=HARD)が確定＝配布不可。最適化の hardFloor と同じ forcedCovU で検出（誤検知ゼロ）。
            for (fc in forcedCovU(state, p)) {
                val days = (0 until p.T).filter { j -> p.covUCell(fc.shiftIndex, j, placeableFor(p, fc.shiftIndex, j)) > 0 }
                out.add(SettingIssue(IssueKind.DEMAND, "「${fc.shiftSymbol}」の担当者不足（${Guidance.FORCED_SHORT_MARK}）",
                    "${fc.cells}日（${daysText(days)}）で、「${fc.shiftSymbol}」を担当できる人数より必要人数が多く、「${fc.shiftSymbol}」の人員不足が合計${fc.amount}人必ず残ります" +
                        (if (zeroCapInShortfall(p, fc)) "。$ZERO_CAP_SHORTFALL_NOTE" else ""),
                    "${Place.EDIT}${Place.SHIFT}の担当可否で「${fc.shiftSymbol}」を担当できる職員を増やすか、${Place.NEED}でその日の「${fc.shiftSymbol}」の必要人数を下げてください"))
            }
        }

        fun duplicateKeyIssues() {
            // 8) [事前診断/重複定義・レビュー指摘P1] 氏名(空白無視)・シフト/グループ/スキル群の記号の重複を警告。
            //    重複があると制約評価・CSV取込とも「最初の1件」に解決される(firstWinsMap で統一済)が、2件が
            //    同一視されること自体は利用者に見えないため、定義の一意化を促す(read-only・非ブロック＝既存データは開ける)。
            run {
                fun dups(items: List<String>): List<String> =
                    items.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
                for (d in dups(state.staff.map { nameMatchKey(it.name) })) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "職員名の重複「$d」",
                        "同名（空白を除き一致）の職員が複数います。制約とCSV取込は最初の1人に解決され、2人目以降は区別できません",
                        "${Place.EDIT}${Place.STAFF}で名前を押し、氏名を一意にしてください（例: 姓名の間や末尾に識別子を付ける）"))
                }
                for (d in dups(state.shifts.map { it.kigou.trim() })) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "シフト記号の重複「$d」",
                        "同じ記号のシフトが複数あります。制約とCSV取込は最初の1件に解決され、2件目以降は参照されません",
                        "${Place.EDIT}${Place.SHIFT}のシフト種別で記号を一意にしてください"))
                }
                for (d in dups(state.groups.map { it.kigou.trim() })) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "グループ記号の重複「$d」",
                        "同じ記号のグループが複数あります。制約とCSV取込は最初の1件に解決されます",
                        "${Place.EDIT}${Place.SHIFT}のグループで記号を一意にしてください"))
                }
                for (d in dups(state.skillGroups.map { it.kigou.trim() })) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "スキルグループ記号の重複「$d」",
                        "同じ記号のスキルグループが複数あります。制約とCSV取込は最初の1件に解決されます",
                        "${Place.EDIT}${Place.SKILL}で記号を一意にしてください"))
                }
            }
        }

        fun musIssues() {
            // 9) [Constraint IR + MUS / 3.272.0] 矛盾の「最小説明」。希望（wishLocked）が絡む証明可能な
            //    矛盾の組合せを ConstraintMus（厳密DP・鳩の巣・二部マッチングの健全な証明）で検出し、
            //    「この◯件は同時に成立しません→どれか1件を緩めると解消」まで提示する。既存の手彫り検査
            //    （2b-3/6b/6c/検査3）はいずれも希望を扱わないため、**コアに希望を含む矛盾のみ**を出す＝
            //    重複ゼロ。read-only・スコアリング不変。発火＝真に矛盾（健全・誤検知ゼロの設計）。
            run {
                fun sym(k: Int): String = symOf(k)
                fun staffName(i: Int): String = nameOf(i)
                fun itemLabel(it: ConstraintMus.Item): String = when (it) {
                    is ConstraintMus.WishPin ->
                        "${if (it.manual) "手動固定" else "希望"}「${staffName(it.staff)} ${safeDayLabel(state.startDate, it.day)}=${sym(it.shift)}」"
                    is ConstraintMus.RangeCap -> "個人上限「${sym(it.shift)}を最大${it.hi}回」"
                    is ConstraintMus.RangeFloor -> "個人下限「${sym(it.shift)}を最低${it.lo}回」"
                    is ConstraintMus.WindowRule -> "期間の制約「${sym(it.shift)} ${it.windowDays}日で${it.minCount}回以上」"
                    is ConstraintMus.DayNeed -> "必要人数「${sym(it.shift)}に${it.need}人」"
                }
                fun relaxHint(it: ConstraintMus.Item): String = when (it) {
                    is ConstraintMus.WishPin ->
                        "${staffName(it.staff)}さんの${safeDayLabel(state.startDate, it.day)}の${if (it.manual) "固定を外す" else "希望を調整する"}"
                    is ConstraintMus.RangeCap -> "「${sym(it.shift)}」の個人上限を上げる"
                    is ConstraintMus.RangeFloor -> "「${sym(it.shift)}」の個人下限を下げる"
                    is ConstraintMus.WindowRule -> "期間の制約「${sym(it.shift)} ${it.windowDays}日で${it.minCount}回以上」の回数を下げる"
                    is ConstraintMus.DayNeed -> "「${sym(it.shift)}」の必要人数を下げる"
                }
                fun relaxPlace(it: ConstraintMus.Item): String = when (it) {
                    is ConstraintMus.WishPin -> if (it.manual) Place.PIN else Place.WISH
                    is ConstraintMus.RangeCap, is ConstraintMus.RangeFloor -> Place.COUNT
                    is ConstraintMus.WindowRule -> Place.RULE
                    is ConstraintMus.DayNeed -> Place.NEED
                }
                fun hasWish(core: List<ConstraintMus.Item>) = core.any { it is ConstraintMus.WishPin }
                for (sc in ConstraintMus.analyzeStaffConflicts(p).filter { hasWish(it.core) }.sortedBy { it.core.size }.take(Guidance.MUS_TOP)) {
                    val name = staffName(sc.staff)
                    val labels = sc.core.joinToString(" ・ ") { itemLabel(it) }
                    val hints = sc.core.take(2).joinToString(" / ") { "${relaxPlace(it)}で${relaxHint(it)}" }
                    out.add(SettingIssue(IssueKind.WISH, "${name}さんの希望と条件の組合せ",
                        "次の${sc.core.size}件は同時に成立しません: $labels",
                        "${Place.TAB}で、いずれか1件を緩めてください（例: $hints）"))
                }
                // 日別の証明（ConstraintMus の canServe）はコアで固定されていない人を mayPlace で数える＝上限 0 の人を前提として名指しする。
                fun capZeroNote(dc: ConstraintMus.DayConflict): String {
                    val pinned = dc.core.filterIsInstance<ConstraintMus.WishPin>().map { it.staff }.toSet()
                    val parts = dc.core.filterIsInstance<ConstraintMus.DayNeed>().map { it.shift }.distinct().mapNotNull { k ->
                        val names = (0 until p.S).filter { it !in pinned && p.canDo(it, k) && !p.mayPlace(it, k) }
                        if (names.isEmpty()) null else "${names.joinToString("・") { staffName(it) }}（${sym(k)}）"
                    }
                    return if (parts.isEmpty()) "" else "。個人上限が0のため置けない人: ${parts.joinToString("、")}"
                }
                for (dc in ConstraintMus.analyzeDayConflicts(p).filter { hasWish(it.core) }.sortedBy { it.core.size }.take(Guidance.MUS_TOP)) {
                    val labels = dc.core.joinToString(" ・ ") { itemLabel(it) }
                    val wishHint = dc.core.firstOrNull { it is ConstraintMus.WishPin }?.let { relaxHint(it) }
                    out.add(SettingIssue(IssueKind.WISH, "${safeDayLabel(state.startDate, dc.day)} の必要人数と固定希望の衝突",
                        "固定された希望の組合せでは、この日の必要人数を満たせません。次の${dc.core.size}件は同時に成立しません: $labels" + capZeroNote(dc),
                        "${Place.EDIT}${Place.WISH}で本人と相談してこの日の希望を1件調整するか、${Place.NEED}でこの日の必要人数を下げてください" + (wishHint?.let { "（例: $it）" } ?: "")))
                }
            }
        }

        fun softOverflowIssue() {
            // [監査#7] SOFT 桁溢れ（辞書式崩壊）: soft 合計がスコア上限 1,000,000 に接近/超過すると、
            //   HARD 1件(=1,000,000) と soft が桁で干渉し「必須違反ゼロ最優先」が崩れる。初期解の soft で概算警告。
            run {
                val soft = Evaluator(p).fullEvalParts(normalizeSchedule(state.schedule.toIntArray2D(), p))[1]
                if (soft >= Guidance.SOFT_OVERFLOW) {
                    out.add(SettingIssue(IssueKind.CONSTRAINT, "要調整の合計点が大きすぎます（${soft}）",
                        "要調整の合計点が上限の 1,000,000 に近く、必須違反を0件にすることを最優先する計算が崩れるおそれがあります",
                        "${Place.EDIT}${Place.RULE}の解けない決まり（日数より回数が多い期間の制約など）や、${Place.HEAD}の多数のグループペア禁止・広すぎる範囲の決まりを見直して、要調整を減らしてください"))
                }
            }
        }

        /** 直すべき度合いが高い順。設定の見直しの一覧は先頭 SETTING_ISSUE_PREVIEW 件を先に出す（残りは「すべて表示」）ため最重要のデータ起因を上位へ。sortedBy は安定＝同順は挿入順。 */
        fun sorted(): List<SettingIssue> {
            return out.sortedBy { iss ->
                when {
                    iss.where.contains(Guidance.FORCED_SHORT_MARK) -> 0   // covU 確定（最優先）
                    iss.kind == IssueKind.WISH -> 1        // 実現不能希望
                    iss.kind == IssueKind.DEMAND -> 2      // 過拘束（下限/上限/適切回数 vs 必要数）
                    iss.kind == IssueKind.RANGE -> 3       // 範囲の矛盾
                    else -> 4                              // 制約・SOFT桁溢れ ほか
                }
            }
        }
    }

    private fun c3FamilyJp(fam: String): String = when (fam) {
        "c3" -> "守るとよい並び"
        "c3n" -> "禁止の並び"
        "c3m" -> "推奨の並び"
        "c3mn" -> "回避の並び"
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
        // [2026-09-02, 外部レビュー#73] buildと同じ理由でcachedProblemへ統一（挙動不変、重複構築を省く）。
        val p = cachedProblem(state)
        return DebugBuilder(state, p, normalizeSchedule(schedule, p), report).run()
    }

    /** [3.507.5/レビュー第6段] 違反詳細ログを節ごとのメソッドに分けた入れ物。各メソッドは `out` に行を足すだけで、文言と順序は分割前と同じ。 */
    private class DebugBuilder(val state: MagiState, val p: Problem, val s: Array<IntArray>, val report: ViolationReport) {
        val out = ArrayList<String>()
        // [スパム対策] 各違反家族の詳細列挙の上限。1パターン把握には十分な件数に絞り、長大化を防ぐ
        //   （以前は 12〜15。c1/c3m など大量家族の1行が極端に伸びていた）。総数は「(N件)」で常に保持。
        val DETAIL_CAP = 8
        fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: k.toString()
        fun nm(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun day(j: Int) = safeDayLabel(state.startDate, j)

        // [3.282.0/新領域ログ監査] 違反詳細ヘッダの件数は「最重クラスで解決済みのセル位置数」で、
        //   breakdown の fire 数とは意味が異なる（c1=窓ごと計上だがmarkはrun先頭のみ・c3n=1 fireでも
        //   パターン全セルをmark・重い族に同一セルを奪われた軽い族は位置ごと消える等）。実機ログで
        //   「違反詳細 c1(11件)」vs「UnifiedCheck c1=12」の食い違いとして混乱を生んでいたため、
        //   fires(breakdown)を併記し両者が異なるときは「件数F・場所N箇所」と明示する。表示のみ・スコア不変。
        fun emit(byFam: Map<String, MutableList<String>>, cap: Int, fires: Map<String, Int>? = null) {
            for ((fam, items) in byFam) {
                val shown = items.take(cap).joinToString(" ; ")
                val more = if (items.size > cap) " …他${items.size - cap}件" else ""
                val f = fires?.get(fam)
                val head = if (f != null && f != items.size) "件数${f}・場所${items.size}箇所" else "${items.size}件"
                out.add("[D] 違反詳細 $fam($head): $shown$more")
            }
        }

        fun run(): List<String> {
            hardFloorLine(); supplyDemandLines(); boundsLines(); coverageLines(); groupCountLines(); countLines(); cellLines()
            perStaffAggregateLines(); c1BreakdownLine(); weeklyBreakdownLine()
            if (out.isEmpty()) out.add("[D] 違反詳細: 制約違反はありません")
            return out
        }

        fun hardFloorLine() {
            // [構造HARD下限] データ起因で解消不能な必須違反(covU)の下限。最適化の hardFloor と同値。
            //   >0 なら「配布不可はデータ起因＝最適化は残りをSOFT研磨する」と判断できる（読み取り専用）。
            run {
                val forced = forcedCovU(state, p)
                val floor = forced.sumOf { it.amount }
                if (floor > 0) out.add("[W] 構造HARD下限: 担当者不足で covU=$floor が解消不能（配布不可はデータ起因）: " +
                    forced.joinToString(" / ") { "${it.shiftSymbol} ${it.cells}日 不足${it.amount}" })
                else out.add("[I] 構造HARD下限: 0（担当者数の観点では各シフトが需要を満たせる。希望/禁止連続による構造的な人員不足は別途 CoverageDiag/設定ミス を参照）")
            }
        }

        fun supplyDemandLines() {
            // 0) 需給サマリ: シフトごとに「日次需要」と「個人下限/上限・適切回数(クランプ後)の供給圧力・現状配置」を
            //    対比し、過剰(covO=日数オーバー)/不足(covU)の構造的要因を一目で示す。読み取り専用（重み・データ不変）。
            //    例: Dﾃ 需要31 < 適切回数計35 → 各人をその回数へ近づける圧力が需要を超え、過剰配置(1日2人)が出る。
            //    注: 下限/上限/適切回数の「計」は設定済み職員のみの合計。未設定者がいると実効上限は無制限なので、
            //    上限計<需要でも不足とは限らない（不足の構造判定は全員に上限がある場合に限定する）。
            run {
                val cnt = countMatrix(p, s)
                for (k in 0 until p.K) {
                    var demand = 0
                    // [3.409.22] 同上（need2 単独定義の需要を落とすと需給行が過小に出る）。
                    for (j in 0 until p.T) demand += effectiveDemand(p, k, j)
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
                    // [3.274.0 監査で修正] 実際の過不足は**日次 covOCell/covUCell の合計**（source of truth）で示す。
                    //   旧実装は月間の `現状 − 需要` を covO/covU とラベルしていたが、毎日需要のあるシフト(Dﾃ 等)
                    //   でしか両者は一致せず、稀にしか需要のないシフト(B4 等: need1=0/need2=1)では「月間の過剰配置数」を
                    //   covO件数と誤表示していた（実機ログ「B4 過剰6(covO)」だが実covO=1）。日次 cov の合計へ統一。
                    var covUreal = 0; var covOreal = 0
                    for (j in 0 until p.T) {
                        var g = 0; for (i in 0 until p.S) if (s[i][j] == k) g++
                        covUreal += p.covUCell(k, j, g)
                        covOreal += p.covOCell(k, j, g)
                    }
                    if (covUreal > 0) notes.add("現状${cur}(需要${demand})→不足${covUreal}(covU)")
                    if (covOreal > 0) notes.add("現状${cur}(需要${demand})→過剰${covOreal}(covO)")
                    // 構造要因(過剰): 各人が下限/適切回数まで埋める圧力(=確実に埋まる量)の合計が、
                    //   **covO を払わずに置ける上限**を超過。
                    // [3.372.0/実機ログ起因] 比較先を need1 合計(demand)から seatsHi へ是正した。旧実装は
                    //   demand と比べていたため、2世代需要(use2)が有効で need2>need1 のデータでは
                    //   「置いても罰の無い枠」を無視して圧力を過大報告していた（実機 2026-08-15 の B4:
                    //   需要2 に対し「供給圧力7(適切回数)>需要2」と出るが、need2 の枠内なら7人置いても
                    //   covO は増えない＝構造的な矛盾は無い）。設定ミス検査6-C は同じ状況で正しく沈黙して
                    //   おり、**同じ問い「目標は席に収まるか」に2つの診断が違う容量定義で違う答えを出して
                    //   いた**。6-C と同じ seatsHi に揃える（緩い側が正しかった）。
                    var seatsHi = 0
                    var capKnown = true   // [3.475.0] 未定義の日は上限なし＝検査6/aptBalances と同じゲート
                    for (j in 0 until p.T) {
                        if (!needDefined(p, k, j)) { capKnown = false; continue }   // [3.409.22] need2 単独定義も席として数える
                        seatsHi += maxOf(effectiveCap(p, k, j), 0)
                    }
                    val pull = maxOf(loSum, aptSum)
                    val pullSrc = if (aptSum >= loSum) "適切回数" else "下限"
                    if (capKnown && seatsHi > 0 && pull > seatsHi) notes.add("供給圧力${pull}(${pullSrc})>置ける上限${seatsHi}")
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
        }

        fun boundsLines() {
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
                        // [3.475.0/論理監査] チェッカーの high は canDo 非依存（担当不可シフトに手で置かれた
                        //   セルも上限超過として数える）。旧: ここで担当不可を丸ごと飛ばしていたため、同じログ内で
                        //   UnifiedCheck が high=1 なのに 0b は「上限超過0名」と出る矛盾があった。low だけ canDo 必須。
                        val canDo = p.canDo(i, k)
                        val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]; val n = cnt[i][k]
                        if (canDo && lo != Int.MIN_VALUE && lo != 0) { hasBound = true; if (n < lo) lows.add("${nm(i)} $n<$lo") }
                        if (hi != Int.MAX_VALUE) {
                            hasBound = true
                            // [代用要員提示/grilling確定=美幸・上條・大島の実例] 上限超過している職員に、
                            //   このシフトを担当できる他の職員(代用要員候補)の人数を併記する。担当を外し
                            //   代用要員へ置き換えることで解消できる可能性を示す（読取専用・データ変更なし）。
                            if (n > hi) {
                                val subCount = (0 until p.S).count { it != i && p.mayPlace(it, k) }   // [3.507.5] 置ける人だけ
                                highs.add("${nm(i)} $n>$hi(代用可${subCount}名)")
                            }
                        }
                    }
                    if (!hasBound) continue
                    fun part(label: String, xs: List<String>) =
                        if (xs.isEmpty()) "${label}0名" else "${label}${xs.size}名(${xs.take(8).joinToString(" ")}${if (xs.size > 8) " …他${xs.size - 8}件" else ""})"
                    val tag = if (lows.isEmpty() && highs.isEmpty()) "上下OK" else "上下注意"
                    out.add("[D] $tag ${sym(k)}: ${part("下限割れ", lows)} / ${part("上限超過", highs)}")
                }
            }
        }

        fun coverageLines() {
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
                    // [3.409.22] 旧: 生の need1/need2 を出すため need2 単独定義セルで「必要-1~2」と
                    //   表示していた（c41/c41s を上で除外した理由と同型の誤表示）。実効値で出す。
                    val lo = effectiveDemand(p, k, j); val hi = effectiveCap(p, k, j)
                    val needStr = if (hi > lo) "$lo~$hi" else "$lo"
                    byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }.add("${day(j)} ${sym(k)} 必要$needStr/現状${cov[j][k]}")
                }
                // [3.380.0/実機ログ起因] **この呼出だけ `fires` を渡していなかった**＝3.282.0 が
                //   「件数(breakdown)と場所(セル数)は別物」と明示するために入れた仕組みの取り残し。
                //   covO は1枠が最大4人ぶん超過しうるので両者が大きく食い違い、実機ログでは
                //   `UnifiedCheck covO=23` / `CoverageDiag 合計23 — 14枠` に対して
                //   **`違反詳細 covO(14件)`** と、**場所数を件数のように**出していた（他の族は
                //   `件数23・場所14箇所` と正しく書き分けている）。同じ report の中で数字が食い違う。
                emit(byFam, DETAIL_CAP, report.breakdown)
            }
        }

        fun groupCountLines() {
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
        }

        fun countLines() {
            // 2) 回数: 回数/下限/上限（countViolations は i,k キー）
            //   [3.353.0] 旧実装は `countViolations`（1セル=最重1クラス）だけを見ていたため、軽い族が重い族と
            //   同じ (職員,シフト) に重なると診断から**丸ごと消えて**いた（実機ログ: 内訳 c2=1 なのに詳細行が
            //   無い／apt=29 に対し表示は7箇所ぶん＝残り3単位は low の裏に隠れていた）。3.111.0 が cellFamilies で
            //   セル空間に対して解いたのと同じ形で、回数空間の全クラス（countFamilies）を列挙する。
            //   ヘッダも 3.282.0 と同じく breakdown と突き合わせ、件数と場所数が違うときは両方出す。
            if (report.countViolations.isNotEmpty()) {
                val cnt = countMatrix(p, s)
                val byFam = LinkedHashMap<String, MutableList<String>>()
                val pairs = if (report.countFamilies.isNotEmpty()) {
                    report.countFamilies.entries.flatMap { (k, list) -> list.map { k to it } }
                } else {
                    report.countViolations.entries.map { it.key to it.value }
                }
                for ((key, cls) in pairs) {
                    val parts = key.split(','); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val k = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    if (i !in 0 until p.S || k !in 0 until p.K) continue
                    val lo = p.rangeLo[i][k].takeIf { it != Int.MIN_VALUE }
                    val hi = p.rangeHi[i][k].takeIf { it != Int.MAX_VALUE }
                    // [実機ログ起因] aptLow/aptHigh は「目標(クランプ後)との偏差」が発火理由なのに従来は staffRange の
                    //   下限/上限しか出ず（例: 回数4 下限4 上限5 → なぜ違反?）、読者が原因を特定できなかった。目標を併記。
                    val apt = if (cls == "vio-aptLow" || cls == "vio-aptHigh") p.apt[i][k].takeIf { it >= 0 } else null
                    byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }
                        .add("${nm(i)} ${sym(k)} 回数${cnt[i][k]}" + (apt?.let { " 目標$it" } ?: "") + (lo?.let { " 下限$it" } ?: "") + (hi?.let { " 上限$it" } ?: ""))
                }
                // 族名が breakdown のキーと一致するもの(low/high/c2)だけ突き合わせる。aptLow/aptHigh は
                //   breakdown に個別キーが無く実体は apt（同じ重み・3.243.0）＝両方へ同じ 29 を出すと二重に見える。
                //   apt は下の専用行で「合計と場所数」を1度だけ示す。
                emit(byFam, DETAIL_CAP, report.breakdown)
                val aptFires = report.breakdown["apt"] ?: 0
                if (aptFires > 0) {
                    val lo = byFam["aptLow"]?.size ?: 0
                    val hi = byFam["aptHigh"]?.size ?: 0
                    out.add(
                        "[D] 違反詳細 apt(件数$aptFires・場所${lo + hi}箇所): 目標割れ${lo}箇所 + 目標超過${hi}箇所" +
                            "（件数=各行の|回数−目標|の合計＝1箇所で複数件になる）",
                    )
                }
            }
        }

        fun cellLines() {
            // 3) セル違反: 誰の・何日・どのシフト（violations は i,j キー）
            if (report.violations.isNotEmpty()) {
                val byFam = LinkedHashMap<String, MutableList<String>>()
                for ((key, cls) in report.violations) {
                    val parts = key.split(','); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    if (i !in 0 until p.S || j !in 0 until p.T) continue
                    byFam.getOrPut(cls.removePrefix("vio-")) { ArrayList() }.add("${nm(i)} ${day(j)}=${sym(s[i][j])}")
                }
                emit(byFam, DETAIL_CAP, report.breakdown)
            }
        }

        fun perStaffAggregateLines() {
            // 3.4) [3.355.0/ログ強化] DETAIL_CAP で切れる大きなセル族は「…他58件」で終わり、**誰に集中して
            //   いるか**が読めなかった（実機ログ: c3 77件・場所66箇所のうち8箇所しか見えない）。checker が出した
            //   場所（cellFamilies）をそのまま職員別に数え直すだけ＝規則の再実装をしないのでドリフトしない。
            run {
                val perFam = LinkedHashMap<String, HashMap<Int, Int>>()
                for ((key, list) in report.cellFamilies) {
                    val i = key.substringBefore(',').toIntOrNull() ?: continue
                    if (i !in 0 until p.S) continue
                    for (cls in list) perFam.getOrPut(cls.removePrefix("vio-")) { HashMap() }.merge(i, 1) { a, b -> a + b }
                }
                for ((fam, byStaff) in perFam) {
                    if (fam == "c1") continue                          // c1 は下の「職員×窓ルール別」がより詳しい
                    if (byStaff.values.sum() <= DETAIL_CAP) continue   // 全件が上に出ているなら冗長
                    val txt = byStaff.entries.sortedByDescending { it.value }
                        .joinToString(" / ") { "${nm(it.key)} ${it.value}箇所" }
                    out.add("[D] $fam 集約（職員別・場所数の全件）: $txt")
                }
            }
        }

        fun c1BreakdownLine() {
            // 3.5) [c1族の職員×窓ルール別件数] 「違反詳細 c1(N件)」はDETAIL_CAP=8で打ち切られ、特定職員が
            //   どの窓ルールで何件かは埋もれる（例: N件中8件しか見えず職員別内訳が分からない）。全件を
            //   職員×ルール別に再集計し、打ち切りなしの1行サマリとして追加する。読取専用（重み・データ不変）。
            //   [3.282.0/新領域ログ監査] 旧実装は mark と同じ `!prevViol`（違反ラン先頭のみ）で数えており、
            //   ラン長>1 のとき breakdown c1（=違反窓ごと計上）と食い違う第3の計数意味論になっていた
            //   （3.227.0 の意図は「breakdown と突合できる全件件数」）。checker の inc と同一の
            //   「違反窓ごと」計上へ是正＝本行の合計は常に UnifiedCheck の c1 と一致する。
            if ((report.breakdown["c1"] ?: 0) > 0) {
                val perStaffRule = LinkedHashMap<Int, LinkedHashMap<String, Int>>()
                for (c in p.cons1) {
                    val ruleLabel = "${sym(c.shiftIdx)}(${c.day1}日窓≥${c.day2})"
                    for (i in 0 until p.S) {
                        if (!p.canDo(i, c.shiftIdx)) continue
                        var j = 0
                        while (j <= p.T - c.day1) {
                            var z = 0
                            for (l in 0 until c.day1) if (s[i][j + l] == c.shiftIdx) z++
                            if (z < c.day2) perStaffRule.getOrPut(i) { LinkedHashMap() }.merge(ruleLabel, 1, Int::plus)
                            j++
                        }
                    }
                }
                if (perStaffRule.isNotEmpty()) {
                    val lines = perStaffRule.entries.joinToString(" / ") { (i, rules) ->
                        "${nm(i)} " + rules.entries.joinToString(", ") { (label, cnt) -> "$label${cnt}件" }
                    }
                    out.add("[D] c1内訳（職員×窓ルール別件数・全件）: $lines")
                }
            }
        }

        fun weeklyBreakdownLine() {
            // 3.6) [3.355.0/ログ強化] weekly は実機で最大の族（合計307中156）なのに内訳が一切無く、
            //   「まだ狙える weekly 156件」としか読めなかった。**回数が7の倍数でないぶんは配置をどう変えても
            //   消せない**（目標=round(回数/7) なので余りが必ず偏差として残る）。その構造床と、曜日の寄せ方で
            //   減らせる残りを分けて示す。床は `weeklyFloorOfCount` の総和＝checker と同じ目標値から導出。
            if ((report.breakdown["weekly"] ?: 0) > 0) {
                val cntW = countMatrix(p, s)
                var floor = 0
                val worst = ArrayList<Triple<Int, Int, Int>>()   // (staff, shift, いま減らせる余地)
                for (i in 0 until p.S) for (k in 0 until p.K) {
                    val c = cntW[i][k]
                    if (c <= 0) continue
                    floor += weeklyFloorOfCount(c)
                }
                for (loc in report.distLocations["weekly"].orEmpty()) {
                    val i = loc.getOrNull(0) ?: continue; val k = loc.getOrNull(1) ?: continue
                    val dev = loc.getOrNull(2) ?: continue
                    val room = dev - weeklyFloorOfCount(cntW.getOrNull(i)?.getOrNull(k) ?: 0)
                    if (room > 0) worst.add(Triple(i, k, room))
                }
                val total = report.breakdown["weekly"] ?: 0
                val head = "[D] weekly内訳: 合計${total}件 = 構造床${minOf(floor, total)}件(回数が7の倍数でない＝配置では消せない)" +
                    " + 曜日の寄せ方で減らせる${(total - floor).coerceAtLeast(0)}件"
                val topTxt = worst.sortedByDescending { it.third }.take(DETAIL_CAP)
                    .joinToString(" ; ") { (i, k, room) -> "${nm(i)} ${sym(k)} 余地${room}" }
                out.add(if (topTxt.isEmpty()) head else "$head / 余地の大きい順: $topTxt")
            }
        }
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
            val need = effectiveDemand(p, k, j)   // [3.409.22] 検査3 と同じ穴（need2 単独定義の見落とし）
            if (need <= 0) continue
            val capable = placeableFor(p, k, j)
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
            val key = c3SeqKey(r.pattern)
            if (key.isBlank()) continue
            if (!seen.add(key)) out.add("$name:$key")
        }
    }
}

/**
 * [3.409.22] この (シフト,日) に**被覆の要件が定義されているか**。need1/need2 のどちらか片方でも
 * 設定されていれば真（P2 単独定義セルも `covUCell`/`covOCell` は正しく評価する＝3.173.0 の規約）。
 * 値そのものは下の2関数が source of truth へ委譲するので、ここは「定義の有無」だけを見る。
 */
/** [3.507.5] その日にシフト k を実際に置ける人数＝最適化器が置ける（`mayPlace`）職員＋その日のセルがそのシフトに固定（希望・手動）された職員。
 *  3.507.0 で個人上限 0 の職員は最適化器が置かなくなったので、「担当できる人数」を canDo で数えると人員不足の必然を見落とす。
 *  手動固定は最適化器が守る（`hf66DataHardening`）＝上限 0 のシフトでも席を埋める。 */
private fun placeableFor(p: Problem, k: Int, j: Int): Int =
    (0 until p.S).count { i -> p.mayPlace(i, k) || (p.wishLocked(i, j) && p.lockTo(i, j) == k) }

private fun needDefined(p: Problem, k: Int, j: Int): Boolean =
    p.need1[k][j] >= 0 || (p.use2 && p.need2[k][j] >= 0)

/**
 * [3.409.22] 実効需要＝**誰も配置しないときの不足量**。`covUCell(k,j,0)` は両方定義なら
 * min(need1,need2)・片方定義ならその値・未定義なら 0 を返すので、これがそのまま「この枠が最低
 * 何人を求めるか」になる（3.391.0 の `isBalanceable` と同じ手）。旧実装は `need1` を直読みして
 * `<=0 なら対象外` としており、**need2 単独定義の需要を丸ごと見落としていた**＝評価器は covU を
 * 計上するのに診断だけ沈黙し、利用者には「設定上は問題なし」と見えていた。
 */
private fun effectiveDemand(p: Problem, k: Int, j: Int): Int = p.covUCell(k, j, 0)

/**
 * [3.409.22] 実効上限＝**covO が出はじめない最大の配置人数**。`covOCell` は got がこの値を超えた
 * ときだけ正になる（両方定義なら max・片方定義ならその値）。need1/need2 の分岐をここで再実装せず
 * source of truth へ委譲する。要件が未定義なら -1（席の概念が無い）。
 */
private fun effectiveCap(p: Problem, k: Int, j: Int): Int {
    if (!needDefined(p, k, j)) return -1
    var h = 0
    while (h < p.S && p.covOCell(k, j, h + 1) == 0) h++
    return h
}

/** [3.410.0/F-02] 旧: `offset` を検証せず `plusDays(offset)` していたため、壊れたキー（例 day=-1）が
 *  **前月末日のラベル**として表示され、実在する別の日を指しているように見えた。負の offset は
 *  日付に写さず、そのまま「N日」形式で出す（例外でなく異常値なので、落とさず正直に出す）。 */
private fun safeDayLabel(startDate: String, offset: Int): String = try {
    require(offset >= 0)
    val d = java.time.LocalDate.parse(startDate).plusDays(offset.toLong())
    val wd = "月火水木金土日"[d.dayOfWeek.value - 1]
    "${d.monthValue}/${d.dayOfMonth}($wd)"
} catch (_: Exception) {
    "${offset + 1}日"
}

/** 連続パターン行の本体＝**最初の空白まで**（`Problem.resolveC3` と同じ。詰めない）。重複検出・削除・禁止の並びの照合はこれを共有する。 */
fun c3SeqBody(pattern: List<String>): List<String> {
    val end = pattern.indexOfFirst { it.isBlank() }
    return if (end >= 0) pattern.subList(0, end) else pattern
}

fun c3SeqKey(pattern: List<String>): String = c3SeqBody(pattern).joinToString("→")
