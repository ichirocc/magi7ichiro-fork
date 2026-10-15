package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.FixApplyGate
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.betterReport

/** 入り口の範囲の中の 1 セルの変更（Tier 1）。適用できなければ [reason] に門の拒否理由を持つ。 */
internal data class EntryMove(val sug: FixSuggestion, val applicable: Boolean, val reason: String?)

/** 入り口の範囲の中の候補の評価回数の上限（セルのシートの 3 秒に収める）。 */
internal const val ENTRY_MOVE_EVALS = 60

/**
 * 入り口の範囲の中で 1 セルだけを変える候補を作り、適用の門（[FixApplyGate]）で 1 つずつ評価する。
 * 日の人員＝その日の 1 人のセル、個人の回数＝その人の 1 セル。過剰は外す側（休を先に試す）、不足は入れる側。
 * セルごとに最も良い置き換え先を 1 つ残す（通るものがあれば通るものを優先）。まとめて見る対象は作らない。
 */
internal fun entryMoves(state: MagiState, s: Array<IntArray>, target: FixTarget, rest: Int?, maxEvals: Int = ENTRY_MOVE_EVALS): List<EntryMove> {
    if (target.multi) return emptyList()
    val k = target.shift
    val nK = state.shifts.size
    val cells: List<Pair<Int, Int>> = when {
        target.staff != null -> s.getOrNull(target.staff)?.indices?.map { target.staff to it }.orEmpty()
        target.day != null -> s.indices.map { it to target.day }
        else -> emptyList()
    }.filter { (i, j) -> j in s[i].indices && (if (target.under) s[i][j] != k else s[i][j] == k) }
    val board = s.map { it.toList() }
    var evals = 0
    val out = ArrayList<EntryMove>()
    for ((i, j) in cells) {
        val tos = if (target.under) listOf(k) else (listOfNotNull(rest?.takeIf { it in 0 until nK && it != k }) + (0 until nK).filter { it != k && it != rest })
        var best: Pair<FixApplyGate.Outcome, FixCell>? = null
        for (to in tos) {
            if (evals >= maxEvals) break
            evals++
            val op = FixCell(i, j, to)
            val r = FixApplyGate.apply(state, s, listOf(op))
            val cur = best
            best = when {
                cur == null -> r to op
                r is FixApplyGate.Outcome.Applied && cur.first !is FixApplyGate.Outcome.Applied -> r to op
                r is FixApplyGate.Outcome.Applied && betterReport(r.after, (cur.first as FixApplyGate.Outcome.Applied).after) -> r to op
                else -> cur
            }
            // 休で通れば十分（過剰を外す 1 セルは休がいちばん他を崩しにくい）。
            if (r is FixApplyGate.Outcome.Applied && to == rest) break
        }
        val (r, op) = best ?: break
        if (!target.effect(board, listOf(op)).fixes) continue
        val after: ViolationReport? = when (r) { is FixApplyGate.Outcome.Applied -> r.after; is FixApplyGate.Outcome.Rejected -> r.after }
        val name = state.staff.getOrNull(i)?.name ?: "#$i"
        val label = "${name} ${DayText.short(state.startDate, j)} ${state.shifts.getOrNull(s[i][j])?.kigou ?: "—"}→${state.shifts.getOrNull(op.toShift)?.kigou ?: "${op.toShift}"}"
        val diff = after?.let { a -> (r.before.breakdown.keys + a.breakdown.keys).map { f -> f to (a.breakdown[f] ?: 0) - (r.before.breakdown[f] ?: 0) }.filter { it.second != 0 }.sortedBy { it.second } }.orEmpty()
        val sug = FixSuggestion(FixKind.CHANGE, listOf(op), label, (after?.hard ?: r.before.hard) - r.before.hard, ((after?.total ?: r.before.total) - r.before.total), diff)
        out += if (r is FixApplyGate.Outcome.Applied) EntryMove(sug, true, null) else EntryMove(sug, false, (r as FixApplyGate.Outcome.Rejected).reason)
        if (evals >= maxEvals) break
    }
    return out
}

/** 案の段: T1＝範囲の中の 1 セル、T2＝付随の変更がすべて理由つき、MOVE_ONLY＝期間全体の過不足が減らない、T3＝そのほか（別の問題）。 */
enum class FixTier { T1, T2, MOVE_ONLY, T3 }

data class TieredFix(val sug: FixSuggestion, val tier: FixTier, val explain: FixExplanation? = null)

/** 段に分けた案と、適用できない Tier 1 の理由の行。[key] は依頼の鍵（別の依頼の結果を出さない）。 */
data class FixTierView(val key: String, val items: List<TieredFix>, val blocked: List<String>) {
    fun of(t: FixTier): List<TieredFix> = items.filter { it.tier == t }
    /** 主の一覧（T1・T2）に出す案があるか。 */
    val hasMain: Boolean get() = items.any { it.tier == FixTier.T1 || it.tier == FixTier.T2 }
}

/** 適用できない Tier 1 の 1 行（ボタンは出さない＝利用者決定 U2）。 */
internal fun blockedEntryLine(m: EntryMove): String = "${m.sug.label} は適用できません：${m.reason ?: "理由を確かめられません"}"

/**
 * 案を段に分ける（並びは Tier 1 → T2 → 移すだけ → T3、各段の中は元の順）。範囲の中の 1 セルの案（入り口の候補と、
 * 探索が出した付随の変更の無い 1 セルの案）は T1。対象の数が減らない案は T3。日の人員の対象で期間全体の過不足が減らない案は移すだけ。
 * 付随の変更がすべて理由つきで、範囲の外の必須違反を直すための変更を含まない案は T2、そうでなければ T3（利用者決定 U3）。
 * 説明（チェッカー）は [explainCap] 件まで。
 */
internal fun classifyTiers(
    state: MagiState, s: Array<IntArray>, target: FixTarget, entry: List<EntryMove>, list: List<FixSuggestion>,
    needLimits: ((Int, Int) -> Pair<Int, Int>?)?, explainCap: Int = 8,
): FixTierView {
    val board = s.map { it.toList() }
    val base = com.magi.app.v6.UnifiedViolationChecker.check(state, s)
    val seen = HashSet<List<FixCell>>()
    val items = ArrayList<TieredFix>()
    for (m in entry) if (m.applicable && seen.add(m.sug.ops)) items += TieredFix(m.sug, FixTier.T1)
    val g0 = if (target.day != null && !target.multi) shiftPeriodGap(board, target.shift, target.under, needLimits) else null
    var explained = 0
    for (sug in list) {
        if (!seen.add(sug.ops)) continue
        if (!target.effect(board, sug.ops).fixes) { items += TieredFix(sug, FixTier.T3); continue }
        val (primary, extras) = splitPrimary(target, board, sug.ops)
        if (primary.isEmpty()) { items += TieredFix(sug, FixTier.T3); continue }
        if (extras.isEmpty() && primary.size == 1) { items += TieredFix(sug, FixTier.T1); continue }
        val ex = if (extras.isNotEmpty() && explained < explainCap) { explained++; explainExtras(state, s, target, sug, base) } else null
        val moveOnly = g0 != null && (shiftPeriodGap(boardAfter(board, sug.ops), target.shift, target.under, needLimits) ?: g0) >= g0
        val tier = when {
            moveOnly -> FixTier.MOVE_ONLY
            extras.isEmpty() -> FixTier.T2
            ex != null && ex.allExplained && !ex.mainlyOutOfFrameHard -> FixTier.T2
            else -> FixTier.T3
        }
        items += TieredFix(sug, tier, ex)
    }
    return FixTierView("", items.sortedBy { it.tier.ordinal }, entry.filter { !it.applicable }.take(3).map(::blockedEntryLine))
}

/** 移すだけの案の見出し（利用者決定 U1: 主の一覧から畳む）。 */
internal fun moveOnlyHeading(sym: String, under: Boolean, n: Int): String =
    "${sym}の${if (under) "不足" else "過剰"}は減りません（別の日へ移すだけの案 ${n}件）"

/** Tier 1 の見出し（日の人員＝この日だけ、個人の回数＝この人の 1 か所だけ）。 */
internal fun tier1Heading(t: FixTarget): String = if (t.staff != null) "この人の勤務を1つだけ変える案" else "この日の勤務を1つだけ変える案"

internal const val TIER2_HEADING = "崩れた分も合わせて整える案"

internal const val MOVE_ONLY_NOTE = "見ている日は直りますが、同じ勤務の別の日の過不足が増えます"

/** 移すだけの案の確認の画面の目的（「解消する」と言わない）。 */
internal fun moveOnlyPurpose(label: String, t: FixTarget): String = "${label}の${t.gapWord}を別の日へ移す"
