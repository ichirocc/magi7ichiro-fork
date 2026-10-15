package com.magi.app.v6

import com.magi.app.model.MagiState

/** 改善提案（`FixSuggestion.ops`）を本盤面へ反映する直前の安全ゲート。提案は計算時の盤面に対する差分なので、仮盤面へ適用して
 *  正式チェッカーで再評価し、辞書式で改善し厳密ピン・希望固定を崩さず必須違反を新たに生まないときだけ適用後の盤面を返す
 *  （入力の盤面は変えない）。必須の判定は [newHardFamilyViolation]（族の件数）と [ViolationIdentity.newHard]（職員・日・枠の同一性）＝
 *  [introducesHardViolation] と同値。 */
object FixApplyGate {
    sealed class Outcome {
        abstract val before: ViolationReport
        data class Applied(val schedule: Array<IntArray>, override val before: ViolationReport, val after: ViolationReport) : Outcome()
        data class Rejected(val reason: String, override val before: ViolationReport, val after: ViolationReport?) : Outcome()
    }

    fun apply(state: MagiState, schedule: Array<IntArray>, ops: List<FixCell>): Outcome =
        apply(state, schedule, ops, Problem(state), UnifiedViolationChecker.check(state, schedule))

    /** 同じ盤面の候補を続けて評価する呼び手向け＝[p] と [before] は `state`/`schedule` から作ったものを渡す（結果は上と同じ）。 */
    fun apply(state: MagiState, schedule: Array<IntArray>, ops: List<FixCell>, p: Problem, before: ViolationReport): Outcome {
        if (ops.isEmpty()) return Outcome.Rejected("変更がありません", before, null)
        val work = schedule.copy2D()
        for (op in ops) {
            if (op.staff !in work.indices || op.day !in work[op.staff].indices || op.toShift !in 0 until p.K)
                return Outcome.Rejected("提案の範囲が今の勤務表と合いません", before, null)
            if (p.pinned(op.staff, op.day) && p.pin[op.staff][op.day] != op.toShift)
                return Outcome.Rejected("手動固定のセルを変える提案です", before, null)
            if (p.wishLocked(op.staff, op.day) && p.lockTo(op.staff, op.day) != op.toShift)
                return Outcome.Rejected("希望で固定されたセルを変える提案です", before, null)
            if (op.toShift != schedule[op.staff][op.day] && p.extBanned(op.staff, op.day, op.toShift))
                return Outcome.Rejected("拡張希望で禁止された勤務を置く提案です", before, null)
            work[op.staff][op.day] = op.toShift
        }
        val after = UnifiedViolationChecker.check(state, work)
        if (!betterReport(after, before)) return Outcome.Rejected("今の勤務表では改善になりません", before, after)
        if (exactPinRegression(p, schedule, work)) return Outcome.Rejected("回数固定（下限＝上限）を崩す提案です", before, after)
        newHardFamilyViolation(before, after)?.let { fam -> return Outcome.Rejected("別の必須条件（${famLabel(fam)}）が新たに崩れる提案です", before, after) }
        // 件数が減っても、別の職員・日・枠に必須違反が新たに生まれる手は通さない（違反の同一性、3.663.0）。
        val born = ViolationIdentity.newHard(before, after)
        if (born.isNotEmpty()) return Outcome.Rejected("別の職員・日・枠に必須違反（${born.map { famLabel(it.family) }.distinct().joinToString("・")}）が新たに ${born.size} 件生まれる提案です", before, after)
        return Outcome.Applied(work, before, after)
    }

    /** 拒否理由の族名（画面に英字の記号を出さない）。 */
    internal fun famLabel(fam: String): String = when (fam) {
        "covU" -> "人員不足"; "c3n" -> "禁止の並び"; "c3w" -> "希望の前日の禁止"; "pref" -> "希望と違う勤務"
        "extWish" -> "拡張希望の違反"; "groupViol" -> "担当外シフト"; else -> "必須条件"
    }
}
