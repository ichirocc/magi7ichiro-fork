package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationIdentity
import com.magi.app.v6.ViolationReport

/** 付随の変更 1 つと、その理由（null＝外しても重い族が増えない＝理由を言えない）。[outOfFrameHard]＝範囲の外の必須違反を直すための変更。 */
internal data class ExtraReason(val ops: List<FixCell>, val reason: String?, val outOfFrameHard: Boolean = false)

/** 案の説明: 入り口の範囲の中の主の変更、付随の変更と理由、ついでに直る範囲の外の必須違反。 */
internal data class FixExplanation(val primary: List<FixCell>, val extras: List<ExtraReason>, val incidental: List<String>) {
    val allExplained: Boolean get() = extras.all { it.reason != null }
    val mainlyOutOfFrameHard: Boolean get() = extras.any { it.outOfFrameHard }
}

/** 入り口の範囲の中で、対象の数を正しい向きへ動かす変更か（日の人員＝その日のその勤務、個人の回数＝その人のその勤務）。 */
internal fun isPrimaryOp(t: FixTarget, schedule: List<List<Int>>, op: FixCell): Boolean {
    val cur = schedule.getOrNull(op.staff)?.getOrNull(op.day) ?: return false
    val inFrame = when {
        t.staff != null -> op.staff == t.staff
        t.multi -> op.day in t.days
        else -> op.day == t.day
    }
    if (!inFrame) return false
    return if (t.under) cur != t.shift && op.toShift == t.shift else cur == t.shift && op.toShift != t.shift
}

/** 主の変更と付随の変更に分ける。 */
internal fun splitPrimary(t: FixTarget, schedule: List<List<Int>>, ops: List<FixCell>): Pair<List<FixCell>, List<FixCell>> =
    ops.partition { isPrimaryOp(t, schedule, it) }

/** 族の重みつきの差（後 − 前）。正＝後のほうが重い。 */
private fun weightedDelta(before: ViolationReport, after: ViolationReport): Map<String, Double> =
    (before.breakdown.keys + after.breakdown.keys).associateWith { k ->
        ((after.breakdown[k] ?: 0) - (before.breakdown[k] ?: 0)) * (MirrorKeys.weights[k] ?: 0.0)
    }

/**
 * 付随の変更を 1 つずつ外して正式な検査で数え直し、外すと最も重く増える族を理由にする（チェッカーの呼び出しは 1 案あたり 3 回まで。
 * 付随の変更が 4 つ以上なら 3 つ目以降をまとめて外す）。理由の族が範囲の外の必須違反なら、その変更は範囲の外を直すためのもの。
 */
internal fun explainExtras(state: MagiState, s: Array<IntArray>, target: FixTarget, sug: FixSuggestion,
                           base: ViolationReport = UnifiedViolationChecker.check(state, s)): FixExplanation {
    val board = s.map { it.toList() }
    val (primary, extras) = splitPrimary(target, board, sug.ops)
    val names = state.staff.map { it.name }
    val syms = state.shifts.map { it.kigou }
    fun apply(ops: List<FixCell>) = Array(s.size) { s[it].copyOf() }.also { w -> for (op in ops) if (op.staff in w.indices && op.day in w[op.staff].indices) w[op.staff][op.day] = op.toShift }
    val full = UnifiedViolationChecker.check(state, apply(sug.ops))
    val groups: List<List<FixCell>> = if (extras.size <= 3) extras.map { listOf(it) } else extras.take(2).map { listOf(it) } + listOf(extras.drop(2))
    val targetFams = fixTargetFamilies(target)
    val reasons = groups.map { g ->
        val without = UnifiedViolationChecker.check(state, apply(sug.ops - g.toSet()))
        val top = weightedDelta(full, without).filter { it.value > 0 }.maxByOrNull { it.value }?.key
            ?: return@map ExtraReason(g, null)
        val n = (without.breakdown[top] ?: 0) - (full.breakdown[top] ?: 0)
        val op = g.first()
        val where = when (top) {
            "low", "high", "apt", "c2", "weekly" -> "${names.getOrNull(op.staff) ?: "#${op.staff}"}さんの"
            "covU", "covO" -> "${DayText.short(state.startDate, op.day)}の"
            else -> ""
        }
        ExtraReason(g, "入れないと${where}${breakdownLabels[top] ?: top}が${n}${unitOf(top)}増えるため", top in MirrorKeys.hard && top !in targetFams)
    }
    val incidental = ViolationIdentity.diff(base, full).resolved.filter { it.hard && it.family !in targetFams }
        .map { violationKeyLabel(it, names, syms, state.startDate) }
    return FixExplanation(primary, reasons, incidental)
}

/** 「合わせて変える勤務：金沢さん 12/31 休 → Dﾃ」と「　理由：…」の 2 行（理由が無ければ「理由を確かめられません」）。 */
internal fun extraReasonLines(e: FixExplanation, schedule: List<List<Int>>, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): List<String> =
    e.extras.flatMap { x ->
        val what = x.ops.joinToString("・") { op ->
            "${staffNames.getOrNull(op.staff) ?: "#${op.staff}"}さん ${DayText.short(startDate, op.day)} " +
                "${shiftSymbols.getOrNull(schedule.getOrNull(op.staff)?.getOrNull(op.day) ?: -1) ?: "—"} → ${shiftSymbols.getOrNull(op.toShift) ?: "${op.toShift}"}"
        }
        listOf("合わせて変える勤務：$what", "　理由：${x.reason ?: "理由を確かめられません"}")
    } + e.incidental.map { "ついでに直る：$it" }

/** 必須違反が残っているのに案が必須を変えないときの添え書き（重い要調整が減るだけなら件数が同じことも言う）。 */
internal fun hardUnchangedLines(s: FixSuggestion, hardRemains: Boolean): List<String> {
    if (!hardRemains || s.deltaHard != 0) return emptyList()
    val weighted = s.diff.sumOf { (k, d) -> d * (MirrorKeys.weights[k] ?: 0.0) }
    return listOf("必須違反は変わりません（要調整だけが変わります）") +
        (if (s.deltaTotal == 0 && weighted < 0) listOf("件数は同じで、重い要調整が減ります") else emptyList())
}

/** 下書きで今の勤務表を置き換える前の確認（[hard]＝今の必須違反、単位つき）。 */
internal fun draftConfirmText(hard: String): String = "今の勤務表（必須 $hard）を下書きで置き換えます。元に戻せます"
internal const val DRAFT_UNDO_NOTE = "元に戻すで前の勤務表に戻せます"
