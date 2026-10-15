package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationIdentity
import com.magi.app.v6.ViolationKey
import com.magi.app.v6.ViolationReport

/** 付随の変更 1 つと、その理由（null＝外しても重い族が増えない＝理由を言えない）。[outOfFrameHard]＝範囲の外の必須違反を直すための変更。
 *  [incidental]＝主の変更が生んだ違反の埋め合わせでなく、前からあった別の違反を直すだけの変更（ついでに直る＝必要な変更ではない）。 */
data class ExtraReason(val ops: List<FixCell>, val reason: String?, val outOfFrameHard: Boolean = false, val incidental: Boolean = false)

/** 案の説明: 入り口の範囲の中の主の変更、付随の変更と理由、ついでに直る範囲の外の必須違反。 */
data class FixExplanation(val primary: List<FixCell>, val extras: List<ExtraReason>, val incidental: List<String>) {
    /** 付随の変更がすべて主の変更の埋め合わせ（[ExtraReason.incidental] は必要な変更に数えない）。 */
    val allExplained: Boolean get() = extras.all { it.reason != null && !it.incidental }
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

/** 違反の鍵が入り口の範囲の中か（個人の回数＝その職員、日の人員＝その日／まとめて見る日々）。 */
internal fun keyInFrame(t: FixTarget, k: ViolationKey): Boolean {
    val staff = if (k.space == ViolationKey.Space.NEED) null else k.a
    val day = if (k.space == ViolationKey.Space.COUNT) null else k.b
    return when {
        t.staff != null -> staff == t.staff
        t.multi -> day != null && day in t.days
        else -> day != null && day == t.day
    }
}

/**
 * 付随の変更を 1 つずつ外して正式な検査で数え直す（3.687.0/R03: 場所と因果で判定）。初め・主の変更だけ・全部の 3 つを数え、
 * 外すと戻る違反の鍵（族＋職員/日/枠）が「主の変更だけ」で初めて生まれた鍵なら必要な埋め合わせ、前からあった別の違反なら
 * ついでに直る（必要な変更ではない）。範囲の外の必須違反かは族の名前でなく鍵の場所で決める。外したときの検査は 1 案あたり 3 回まで
 * （付随の変更が 4 つ以上なら 3 つ目以降をまとめて外す）。
 */
internal fun explainExtras(state: MagiState, s: Array<IntArray>, target: FixTarget, sug: FixSuggestion,
                           base: ViolationReport = UnifiedViolationChecker.check(state, s)): FixExplanation {
    val board = s.map { it.toList() }
    val (primary, extras) = splitPrimary(target, board, sug.ops)
    val names = state.staff.map { it.name }
    val syms = state.shifts.map { it.kigou }
    fun apply(ops: List<FixCell>) = Array(s.size) { s[it].copyOf() }.also { w -> for (op in ops) if (op.staff in w.indices && op.day in w[op.staff].indices) w[op.staff][op.day] = op.toShift }
    val full = UnifiedViolationChecker.check(state, apply(sug.ops))
    val primaryOnly = UnifiedViolationChecker.check(state, apply(primary))
    val baseKeys = ViolationIdentity.keys(base)
    val introduced = ViolationIdentity.keys(primaryOnly) - baseKeys
    val fullKeys = ViolationIdentity.keys(full)
    val groups: List<List<FixCell>> = if (extras.size <= 3) extras.map { listOf(it) } else extras.take(2).map { listOf(it) } + listOf(extras.drop(2))
    val incidentalNotes = ArrayList<String>()
    val reasons = groups.map { g ->
        val without = UnifiedViolationChecker.check(state, apply(sug.ops - g.toSet()))
        val back = ViolationIdentity.keys(without) - fullKeys
        val comp = back.filter { it in introduced }
        val w: (ViolationKey) -> Double = { MirrorKeys.weights[it.family] ?: 0.0 }
        if (comp.isNotEmpty()) {
            val top = comp.maxBy(w)
            val n = comp.count { it.family == top.family }
            return@map ExtraReason(g, "入れないと${violationKeyPlace(top, names, syms, state.startDate)}の${breakdownLabels[top.family] ?: top.family}が" +
                "${n}${unitOf(top.family)}戻るため（主の変更で生まれる分の埋め合わせ）")
        }
        if (back.isNotEmpty()) {
            // 前からあった違反を直すだけ＝必要な変更ではない。範囲の外の必須違反なら「範囲の外を直すための変更」。
            back.filter { it.hard }.forEach { incidentalNotes += violationKeyLabel(it, names, syms, state.startDate) }
            val top = back.maxBy(w)
            return@map ExtraReason(g, "入れると${violationKeyLabel(top, names, syms, state.startDate)}もついでに直るため（なくても対象は直ります）",
                outOfFrameHard = back.any { it.hard && !keyInFrame(target, it) }, incidental = true)
        }
        // 鍵が増えない（同じ場所で量だけ増える回数の族など）＝族の量で見る。主の変更で増えた族なら埋め合わせ。
        val top = weightedDelta(full, without).filter { it.value > 0 }.maxByOrNull { it.value }?.key
            ?: return@map ExtraReason(g, null)
        val n = (without.breakdown[top] ?: 0) - (full.breakdown[top] ?: 0)
        val grewByPrimary = (primaryOnly.breakdown[top] ?: 0) > (base.breakdown[top] ?: 0)
        val where = when (top) {
            "low", "high", "apt", "c2", "weekly" -> extraStaffWhere(g, names)
            "covU", "covO" -> g.map { it.day }.distinct().joinToString("・") { DayText.short(state.startDate, it) } + "の" + (if (g.map { it.day }.distinct().size > 1) "合計 " else "")
            else -> ""
        }
        ExtraReason(g, "入れないと${where}${breakdownLabels[top] ?: top}が${n}${unitOf(top)}増えるため", incidental = !grewByPrimary)
    }
    val incidental = (ViolationIdentity.diff(base, full).resolved.filter { it.hard && !keyInFrame(target, it) }
        .map { violationKeyLabel(it, names, syms, state.startDate) } + incidentalNotes).distinct()
    return FixExplanation(primary, reasons, incidental)
}

/** [3.687.0/R04] 付随の変更（まとめた組を含む）の職員の言い方。1 人ならその人、複数なら「A さん・B さんの合計」（先頭の人に合計を寄せない）。 */
internal fun extraStaffWhere(g: List<FixCell>, names: List<String>): String {
    val who = g.map { it.staff }.distinct().map { "${names.getOrNull(it) ?: UNKNOWN_LABEL}さん" }
    return if (who.size == 1) "${who[0]}の" else who.joinToString("・") + "の合計 "
}

/** 「合わせて変える勤務：金沢さん 12/31 休 → Dﾃ」と「　理由：…」の 2 行（理由が無ければ「理由を確かめられません」）。 */
internal fun extraReasonLines(e: FixExplanation, schedule: List<List<Int>>, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): List<String> =
    e.extras.flatMap { x ->
        val what = x.ops.joinToString("・") { op ->
            "${staffNames.getOrNull(op.staff) ?: UNKNOWN_LABEL}さん ${DayText.short(startDate, op.day)} " +
                "${shiftSymbols.getOrNull(schedule.getOrNull(op.staff)?.getOrNull(op.day) ?: -1) ?: "—"} → ${shiftSymbols.getOrNull(op.toShift) ?: UNKNOWN_LABEL}"
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
/** 下書きの確認の一文。[hard]＝置き換える前の必須違反（まだ数えていなければ null＝件数を言わない）。 */
internal fun draftConfirmText(hard: String?): String =
    if (hard == null) "今の勤務表を下書きで置き換えます。元に戻せます" else "今の勤務表（必須 $hard）を下書きで置き換えます。元に戻せます"
internal const val DRAFT_UNDO_NOTE = "「元に戻す」で前の勤務表に戻せます"
