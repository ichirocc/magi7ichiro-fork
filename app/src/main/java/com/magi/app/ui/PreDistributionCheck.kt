package com.magi.app.ui

/**
 * 配布前確認（判断支援 UX 仕様 §10）の行。計算が終わったこと・必須違反 0 件・相談の完了・保存は別の状態＝
 * どれか 1 つを根拠に「配れる」とは言わず、行ごとに今の状態を並べて最後の判断はマネージャーに残す。
 */
internal data class PreDistRow(val label: String, val value: String, val ok: Boolean?)

internal fun preDistributionRows(ui: UiState, csvLine: String?): List<PreDistRow> {
    val period = if (ui.startDate.isNotBlank() && ui.days > 0) DayText.range(ui.startDate, 0, ui.days - 1) else "期間未設定"
    val evaluated = ui.hasResult && !ui.running && ui.v6 != null
    val hardFams = com.magi.app.v6.MirrorKeys.hard.mapNotNull { k -> ui.breakdown[k]?.takeIf { it > 0 }?.let { k to it } }
    val hardText = if (ui.bestHard == 0L) "0件" else "${ui.bestHard}件（" + hardFams.joinToString("・") { "${breakdownLabels[it.first] ?: it.first} ${it.second}件" } + "）"
    val soft = ui.breakdown.filter { (k, v) -> v > 0 && k !in com.magi.app.v6.MirrorKeys.hard }
        .entries.sortedByDescending { it.value }
    val softText = if (soft.isEmpty()) "なし" else soft.take(3).joinToString("・") { "${breakdownLabels[it.key] ?: it.key} ${it.value}件" } +
        (if (soft.size > 3) " ほか${soft.size - 3}種類" else "")
    val consults = ui.consults.size
    return listOf(
        PreDistRow("対象", "$period の採用した勤務表", null),
        PreDistRow("評価", if (evaluated) "最新の勤務表を評価済み" else "まだ評価していません（問題がないか調べてください）", evaluated),
        PreDistRow("必須違反", hardText, ui.bestHard == 0L),
        PreDistRow("要調整", softText, null),
        PreDistRow("相談", if (consults == 0) "未完了の相談はありません" else "未完了 ${consults}件（ホームの「相談中」）", consults == 0),
        PreDistRow("保存", csvLine ?: "まだ勤務表 CSV に保存していません", csvLine?.startsWith("この内容で") == true),
    )
}

/** 配布前確認の注意書き。必須違反 0 件だけでは、相談の完了や法令への適合までは示さない（仕様 §10）。 */
internal const val PRE_DIST_NOTE = "必須違反 0 件は、登録した必須条件を満たしたことだけを示します。相談の完了や法令への適合は含みません。配るかどうかはあなたが決めてください。"
