package com.magi.app.ui

/**
 * 配布前確認（判断支援 UX 仕様 §10）の行。計算が終わったこと・必須違反 0 件・相談の完了・保存は別の状態＝
 * どれか 1 つを根拠に「配れる」とは言わず、行ごとに今の状態を並べて最後の判断はマネージャーに残す。
 */
internal data class PreDistRow(val label: String, val value: String, val ok: Boolean?, val primary: Boolean = false)

/** 題の下の 1 行（3.704.0 P-1: 対象期間は行から外して題の下へ）。 */
internal fun preDistributionPeriodLine(ui: UiState): String =
    "対象期間 " + (if (ui.startDate.isNotBlank() && ui.days > 0) DayText.range(ui.startDate, 0, ui.days - 1) else "期間未設定") + "・現在採用している勤務表"

internal fun preDistributionRows(ui: UiState, csvLine: String?): List<PreDistRow> {
    val evaluated = ui.hasResult && !ui.running && ui.v6 != null
    val hardFams = com.magi.app.v6.MirrorKeys.hard.mapNotNull { k -> ui.breakdown[k]?.takeIf { it > 0 }?.let { k to it } }
    val hardText = if (ui.bestHard == 0L) "0件" else "${hardText(ui.breakdown, ui.bestHard)}（" +
        hardFams.joinToString("・") { "${breakdownLabels[it.first] ?: it.first} ${it.second}${unitOf(it.first)}" } + "）"
    // 作成の結果カードと同じ並び（重み×数の大きい順）。
    val softText = softSummary(ui.breakdown)
    val open = ui.consults.filter(::consultUnresolved)
    val consults = open.size
    val held = open.count { it.held }
    // 上は必須違反・相談・保存の 3 点と、評価が済んでいないときの評価の行。残りは「詳しく」へ（[preDistributionTop]）。
    return listOf(
        PreDistRow("評価", if (evaluated) "最新の勤務表を評価済み" else "まだ評価していません（問題がないか調べてください）", evaluated, primary = !evaluated),
        PreDistRow("必須違反", hardText, ui.bestHard == 0L, primary = true),
        PreDistRow("要調整", softText, null),
        PreDistRow("相談", if (consults == 0) "「相談中」に未解決の項目はありません" else "未完了 ${consults}件" + (if (held > 0) "（うち保留 ${held}件）" else "") + "（ホームの「相談中」）", consults == 0, primary = true),
        PreDistRow("保存", csvLine ?: "まだ勤務表CSVに保存していません", csvLine?.startsWith("この内容で") == true, primary = true),
    )
}

internal fun preDistributionTop(rows: List<PreDistRow>): List<PreDistRow> = rows.filter { it.primary }
internal fun preDistributionDetails(rows: List<PreDistRow>): List<PreDistRow> = rows.filterNot { it.primary }

/** 配布前の確認の脚注（3.665.0 の月の境目を 1 行で）。 */
internal const val PRE_DIST_EDGE_FOOT = "前月・翌月をまたぐ並びは未評価"

/** 評価できていない範囲（違反セルの半自動修正仕様 §7・§8、3.665.0 利用者決定「未評価と明示するだけ」）。前月・翌月の勤務はデータに無い。 */
internal const val MONTH_EDGE_NOTE = "前月末・翌月初をまたぐ並びは評価していません。今月の中だけで判定します"

/** 変更した日が月の初め・終わりの [maxLen]−1 日以内か（並びの長さ [maxLen] の窓が月の外へはみ出しうる）。 */
internal fun monthEdgeTouched(days: List<Int>, totalDays: Int, maxLen: Int): Boolean =
    maxLen >= 2 && days.any { it < maxLen - 1 || it > totalDays - maxLen }

/** 配布前確認の注意書き。必須違反 0 件だけでは、相談の完了や法令への適合までは示さない（仕様 §10）。 */
internal const val PRE_DIST_NOTE = "必須違反 0 件は、登録した必須条件を満たしたことだけを示します。相談の完了や法令への適合は含みません。相談事項や職場の確認手続きを確かめたうえで、配布をご判断ください。"
