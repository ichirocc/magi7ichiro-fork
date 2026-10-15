package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.toIntArray2D

/** セルの印と一文（重さ・重い順の族・状態の 1 行）。セルのシートと同じ判定から作る。 */
data class CellMark(val severity: CellSeverity, val families: List<String>, val oneLine: String, val cause: String = "")

/**
 * 表示の写し（統合仕様「印と一文を 1 か所で」）。検査結果を反映するたびに ViewModel が 1 回だけ作り、
 * 上部の札・巡回の件数・セルの状態の 1 行はここを読む＝画面ごとに数え直して食い違わない。セッション内のみ（JSON に保存しない）。
 * [rev] が今の [cellSheetRev] と違えば古い＝読む側は従来の計算へ戻る。
 */
data class DisplaySnapshot(
    val rev: CellSheetRev,
    val bestHard: Long,
    val breakdown: Map<String, Int>,
    val hardText: String,
    val softText: String,
    val cellMarks: Map<Pair<Int, Int>, CellMark>,
    val tourItems: List<TourItem>,
    val covUPeople: Int,
    val nextAction: String?,
) {
    fun freshFor(ui: UiState): Boolean = rev == cellSheetRev(ui) && bestHard == ui.bestHard && breakdown == ui.breakdown
    fun cellMark(i: Int, j: Int): CellMark = cellMarks[i to j] ?: NO_MARK
}

private val NO_MARK = CellMark(CellSeverity.NONE, emptyList(), "違反なし")

/** セルのシートと同じ族（表示クラス＋期間の制約の区間＋当日・回数、足りない側を除く）。 */
internal fun cellMarkFamilies(ui: UiState, i: Int, j: Int, c1Marks: Set<String>): List<String> {
    val current = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
    val needCls = if (current >= 0) ui.needFamilies[VioKey.need(current, j)].orEmpty() else emptyList()
    val countCls = if (current >= 0) ui.countFamilies[VioKey.count(i, current)].orEmpty() else emptyList()
    val shortSide = shortSideFamilies(needCls, countCls)
    val inC1 = ui.c1Shortages.any { it.staff == i && j in it.from..it.to }
    return cellStatusFamilies(sheetCellClasses(displayCellClasses(ui, VioKey.cell(i, j), c1Marks), inC1), needCls, countCls)
        .filter { it !in shortSide }
}

internal fun buildDisplaySnapshot(state: MagiState, ui: UiState): DisplaySnapshot {
    val s = ui.schedule.toIntArray2D()
    val empty = ui.schedule.isEmpty()
    val p = if (empty) null else cachedProblem(state)
    val c1Marks = c1DisplayMarks(ui)
    val marks = HashMap<Pair<Int, Int>, CellMark>()
    if (p != null) for (i in s.indices) for (j in s[i].indices) {
        val fams = cellMarkFamilies(ui, i, j, c1Marks)
        if (fams.isEmpty()) continue
        val st = cellStatusLine(state, p, s, i, j, fams)
        marks[i to j] = CellMark(st.severity, fams, st.text, st.cause)
    }
    val tour = if (p == null) emptyList() else hardViolationItems(state, p, s, ui.violationCellFamilies)
    val covU = ui.breakdown["covU"] ?: 0
    return DisplaySnapshot(
        rev = cellSheetRev(ui), bestHard = ui.bestHard, breakdown = ui.breakdown,
        hardText = hardText(ui.breakdown, ui.bestHard),
        softText = unitTotals(ui.breakdown, MirrorKeys.soft) ?: "なし",
        cellMarks = marks, tourItems = tour, covUPeople = covU,
        nextAction = tour.firstOrNull()?.let { tourHeading(tour, 0) } ?: tourCovULine(covU),
    )
}

/** 上部の札・一覧の必須違反の言い方。写しが今の検査に追いついていればそれを、でなければ同じ式で数える。 */
internal fun displayHardText(ui: UiState): String =
    ui.display?.takeIf { it.freshFor(ui) }?.hardText ?: hardText(ui.breakdown, ui.bestHard)
