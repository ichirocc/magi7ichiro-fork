package com.magi.app.ui

import com.magi.app.v6.FixSuggestion

/**
 * [3.645.0/仕様 5.3・UX-05] 「相談してから決める」判断。対象（だれの・どの日の・何）と検討内容を持ち、セッション内だけ
 * （state 非保存＝見直し候補と同じ）。出力も判定も止めない＝確認・承認を必須にする範囲は職場の運用で決める（ユーザー決定 2026-10-09）。
 */
data class ConsultItem(val subject: String, val note: String, val staff: Int? = null, val day: Int? = null)

internal const val CONSULT_BUTTON = "相談してから決める"
internal const val CONSULT_DONE = "相談中"
internal const val CONSULT_ADDED = "相談中に追加しました"
internal const val CONSULT_DUPLICATE = "すでに相談中にあります"

/** ホームの主カードの 1 行。0 件なら出さない。 */
internal fun consultLine(n: Int): String? = if (n > 0) "未確認事項 $n 件（相談中。下の一覧で確認してから配ってください）" else null

/** ぶつかっている希望の行から: 希望を取り消すか勤務を変えるかの判断。 */
internal fun consultWish(name: String, dayLabel: String, symbol: String?, reason: String, staff: Int, day: Int) =
    ConsultItem("$name $dayLabel の希望" + (symbol?.let { "「$it」" } ?: ""), "取り消すか勤務を変えるか: $reason", staff, day)

/** なおし方（1 人を入れる）の枠から: だれを入れるかの判断。 */
internal fun consultShortage(dayLabel: String, symbol: String, names: List<String>) = ConsultItem(
    "$dayLabel の「$symbol」の人員不足",
    if (names.isEmpty()) "入れる人を相談" else "だれかを入れる: " + names.take(5).joinToString("・") + (if (names.size > 5) " ほか${names.size - 5}人" else ""),
)

/** 複数人の入替の一覧から: 変わる人と勤務。 */
internal fun consultChain(p: ChainFixPreview) = ConsultItem(p.suggestion.label, p.changes.joinToString("／") + "（${p.hardLine}）")

/** 直す 1 手から。 */
internal fun consultFix(s: FixSuggestion) = ConsultItem(s.label, fixImpactLines(s).let { (h, c) -> h + (c?.let { "・$it" } ?: "") })

/** つくる前の確認の行から: 残る項目を希望か設定のどちらで解くか。 */
internal fun consultPreRun(row: PreRunRow) = ConsultItem(row.text, "何度つくっても残る項目。希望か設定のどちらを変えるかを相談", row.staff, row.day)

/** すでに一覧にあるか（ボタンを「相談中」にして形で返す＝シートの下では Snackbar が見えない）。 */
internal fun isConsulted(list: List<ConsultItem>, item: ConsultItem): Boolean = list.any { it.subject == item.subject && it.note == item.note }

/** 同じ対象・内容は 2 度積まない（null＝重複）。 */
internal fun consultAdd(list: List<ConsultItem>, item: ConsultItem): List<ConsultItem>? = if (isConsulted(list, item)) null else list + item
