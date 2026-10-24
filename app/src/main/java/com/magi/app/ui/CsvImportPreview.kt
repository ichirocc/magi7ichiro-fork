package com.magi.app.ui

import com.magi.app.v6.ScheduleRunResult

/** 勤務表 CSV（重ね合わせ）を反映する前の確認（ユーザー提示のスマホ設計案「読み込み内容を確認」）。 */
internal const val CSV_PREVIEW_TITLE = "この内容で取り込みますか？"
internal const val CSV_PREVIEW_CONFIRM = "取り込む"
internal const val CSV_PREVIEW_CANCEL = "取り込まない"

/** 確認の行（確認ダイアログは 3 点まで＝`CONFIRM_MAX_POINTS`）: 取り込む人数と変わるセル／必須違反の前後／注意（あれば 1 行にまとめる）。 */
internal fun csvImportPreviewLines(res: ScheduleRunResult, total: Int, base: Array<IntArray>, hardNow: Int): List<String> {
    val changed = base.indices.sumOf { i -> base[i].indices.count { j -> res.schedule.getOrNull(i)?.getOrNull(j)?.let { it != base[i][j] } == true } }
    val skipped = total - res.matched
    val notes = listOfNotNull(
        if (skipped > 0) "${skipped}名は氏名が合わずスキップ" else null,
        if (res.unknownCells > 0) "読めない記号 ${res.unknownCells}セル（${res.unknownSymbols.joinToString("・")}）は取り込まない" else null,
        if (res.headerDateMismatches > 0) "見出しの日付が今の期間と${res.headerDateMismatches}列ズレている（列の位置で取り込む）" else null,
        if (res.ambiguousNames.isNotEmpty()) "同じ名前の職員が複数いる${res.ambiguousNames.joinToString("・") { "${it}さん" }}の行は取り込まない" else null,
        if (res.duplicateRowNames.isNotEmpty()) "同じ職員の行が複数ある（後の行で上書き）: ${res.duplicateRowNames.joinToString("・")}" else null,
    )
    return listOfNotNull(
        "${res.matched}/${total}名の勤務を取り込みます（変わるセル ${changed}）",
        "必須違反：$hardNow → ${res.report.hard}",
        notes.takeIf { it.isNotEmpty() }?.joinToString("／", prefix = "⚠ "),
    )
}
