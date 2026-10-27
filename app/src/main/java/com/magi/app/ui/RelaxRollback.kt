package com.magi.app.ui

/**
 * [S6] 確定の巻き戻し用の控え（`docs/s6_relax_trial.md` §15）。relaxAndApply が書き換える画面の欄・結果盤面・Undo/Redo の段・他の案を
 * 書き換えの前に写し、事後確認の不一致・保存失敗で [restore] がそのまま戻す。[S]＝Undo の段、[R]＝結果盤面、[A]＝他の案の控え。
 */
data class RelaxRollback<S, R, A>(
    val ui: UiState,
    val undo: List<S>,
    val redo: List<S>,
    val resultSchedule: R?,
    val alts: A,
) {
    /** いまの [cur] に、控えた欄だけを戻す（実行中の表示・保存状態など巻き戻しと関係のない欄は今のまま）。 */
    fun restore(cur: UiState): UiState = cur.copy(
        hasResult = ui.hasResult, engineRan = ui.engineRan, ranBeforeEdit = ui.ranBeforeEdit, relaxedBoard = ui.relaxedBoard,
        structureEdited = ui.structureEdited, runSummary = ui.runSummary, runDelta = ui.runDelta, stopSummary = ui.stopSummary, schedule = ui.schedule,
        canUndo = ui.canUndo, canRedo = ui.canRedo, editRev = ui.editRev, messageIsError = ui.messageIsError,
        fixSuggestions = ui.fixSuggestions, fixTiers = ui.fixTiers, fixSearched = ui.fixSearched, fixDoneKey = ui.fixDoneKey,
        fixFailedKey = ui.fixFailedKey, stalledHardFamilies = ui.stalledHardFamilies, alternatives = ui.alternatives,
        alternativeApplied = ui.alternativeApplied,
    )

    /** 段の控えを [undoStack]・[redoStack] へ書き戻す（30 段を超えて落とした最古の段も戻る）。 */
    fun restoreStacks(undoStack: ArrayDeque<S>, redoStack: ArrayDeque<S>) {
        undoStack.clear(); undoStack.addAll(undo)
        redoStack.clear(); redoStack.addAll(redo)
    }
}
