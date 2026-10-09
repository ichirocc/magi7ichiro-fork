package com.magi.app.ui

import com.magi.app.v6.FixSuggestion
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [3.645.0/仕様 5.3・UX-05] 「相談してから決める」判断。対象（だれの・どの日の・何）と検討内容を持ち、セッション内だけ
 * （state 非保存＝見直し候補と同じ）。出力も判定も止めない＝確認・承認を必須にする範囲は職場の運用で決める（ユーザー決定 2026-10-09）。
 * 対象は氏名と実日付でも持つ＝職員の削除・並び替えや月の移動のあとに別の人・別の日を開かない（3.646.0、外部レビュー B01/B02）。
 * [staff]/[day] は積んだときの位置（氏名・日付が一致するときの近道）。
 */
data class ConsultItem(
    val subject: String, val note: String, val staff: Int? = null, val day: Int? = null,
    val staffName: String? = null, val date: String? = null,
    val chain: ChainTarget? = null,
)

/** 複数人の入れ替えの相談が指す枠。日付とシフト記号で持つ＝月の移動・シフトの並び替えのあとも今の勤務表で案を探し直せる。
 *  枠を持たない案（ホーム・分析の「この手を使う」から）は [suggestion] を持ち、一覧をもう一度出す（当てるときの照合は適用の門が行う）。 */
data class ChainTarget(val date: String?, val shiftSymbol: String?, val label: String, val suggestion: FixSuggestion? = null)

internal const val CONSULT_BUTTON = "相談してから決める"
internal const val CONSULT_DONE = "相談中"
internal const val CONSULT_ADDED = "相談中に追加しました"
internal const val CONSULT_DUPLICATE = "すでに相談中にあります"
internal const val CONSULT_OPEN = "開く"
internal const val CONSULT_RESUME = "案を見る"

/** ホームの主カードの 1 行。0 件なら出さない。 */
internal fun consultLine(n: Int): String? = if (n > 0) "未確認事項 $n 件（相談中。下の一覧で確認してから配ってください）" else null

internal fun isoDate(startDate: String, day: Int): String? =
    runCatching { LocalDate.parse(startDate).plusDays(day.toLong()).toString() }.getOrNull()

/** [date] が今の期間の何日目か（期間の外・読めない日付は null）。 */
internal fun dayIndexOf(startDate: String, date: String, days: Int): Int? = runCatching {
    ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(date)).toInt().takeIf { it in 0 until days }
}.getOrNull()

private fun shortDate(date: String): String =
    runCatching { LocalDate.parse(date).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrDefault(date)

/** 相談の職員を今の一覧で探す。氏名があればそれで（積んだときの位置が同じ氏名なら近道）、無ければ位置だけ。 */
internal fun consultStaff(c: ConsultItem, staffNames: List<String>): Int? {
    val name = c.staffName ?: return c.staff?.takeIf { it in staffNames.indices }
    val at = c.staff
    if (at != null && staffNames.getOrNull(at) == name) return at
    return staffNames.indexOf(name).takeIf { it >= 0 }
}

/** 相談の日を今の期間で探す。日付があればそれで、無ければ日番号だけ。 */
internal fun consultDay(c: ConsultItem, startDate: String, days: Int): Int? {
    val date = c.date ?: return c.day?.takeIf { it in 0 until days }
    return dayIndexOf(startDate, date, days)
}

/** 相談の対象セル（職員, 日）。氏名が今の一覧になければ null、日付が今の期間の外なら null＝「開く」を出さない。 */
internal fun consultCell(c: ConsultItem, startDate: String, staffNames: List<String>, days: Int): Pair<Int, Int>? {
    val i = consultStaff(c, staffNames) ?: return null
    val j = consultDay(c, startDate, days) ?: return null
    return i to j
}

/** 入れ替えの相談の枠（日, シフト）。日付が期間の外・記号が今のシフトに無ければ null。 */
internal fun consultChainTarget(t: ChainTarget, startDate: String, shiftSymbols: List<String>, days: Int): Pair<Int, Int>? {
    val j = t.date?.let { dayIndexOf(startDate, it, days) } ?: return null
    val k = t.shiftSymbol?.let { sym -> shiftSymbols.indexOf(sym).takeIf { it >= 0 } } ?: return null
    return j to k
}

/** 入れ替えの相談を今の勤務表で見直せるか（枠を引き直せる、または案そのものを持つ）。 */
internal fun consultChainResumable(t: ChainTarget, startDate: String, shiftSymbols: List<String>, days: Int): Boolean =
    consultChainTarget(t, startDate, shiftSymbols, days) != null || t.suggestion != null

/** 「開く」「案を見る」を出せない理由（対象をもともと持たない相談は null＝何も出さない）。 */
internal fun consultTargetNote(c: ConsultItem, startDate: String, staffNames: List<String>, shiftSymbols: List<String>, days: Int): String? {
    c.chain?.let { t ->
        if (consultChainResumable(t, startDate, shiftSymbols, days)) return null
        return "いまの期間・シフトにない枠です（${t.date?.let { shortDate(it) } ?: "?"} の「${t.shiftSymbol ?: "?"}」）"
    }
    if (c.staff == null && c.staffName == null && c.day == null && c.date == null) return null
    if (consultCell(c, startDate, staffNames, days) != null) return null
    if (consultStaff(c, staffNames) == null) return "いまの職員一覧にいません" + (c.staffName?.let { "（$it）" } ?: "")
    return "いまの期間にない日です" + (c.date?.let { "（${shortDate(it)}）" } ?: "")
}

/** ぶつかっている希望の行から: 希望を取り消すか勤務を変えるかの判断。 */
internal fun consultWish(name: String, dayLabel: String, symbol: String?, reason: String, staff: Int, day: Int, date: String? = null) =
    ConsultItem("$name $dayLabel の希望" + (symbol?.let { "「$it」" } ?: ""), "取り消すか勤務を変えるか: $reason", staff, day, staffName = name, date = date)

/** なおし方（1 人を入れる）の枠から: だれを入れるかの判断。 */
internal fun consultShortage(dayLabel: String, symbol: String, names: List<String>) = ConsultItem(
    "$dayLabel の「$symbol」の人員不足",
    if (names.isEmpty()) "入れる人を相談" else "だれかを入れる: " + names.take(5).joinToString("・") + (if (names.size > 5) " ほか${names.size - 5}人" else ""),
)

/** 複数人の入れ替えの一覧から: 変わる人と勤務。枠を持てば、あとで今の勤務表で案を探し直せる。 */
internal fun consultChain(p: ChainFixPreview) = ConsultItem(p.suggestion.label, p.changes.joinToString("／") + "（${p.hardLine}）", chain = p.target)

/** 直す 1 手から。 */
internal fun consultFix(s: FixSuggestion) = ConsultItem(s.label, fixImpactLines(s).let { (h, c) -> h + (c?.let { "・$it" } ?: "") })

/** つくる前の確認の行から: 残る項目を希望か設定のどちらで解くか。 */
internal fun consultPreRun(row: PreRunRow, staffName: String? = null, date: String? = null) =
    ConsultItem(row.text, "何度つくっても残る項目。希望か設定のどちらを変えるかを相談", row.staff, row.day, staffName = staffName, date = date)

/** すでに一覧にあるか（ボタンを「相談中」にして形で返す＝シートの下では Snackbar が見えない）。 */
internal fun isConsulted(list: List<ConsultItem>, item: ConsultItem): Boolean = list.any { it.subject == item.subject && it.note == item.note }

/** 同じ対象・内容は 2 度積まない（null＝重複）。 */
internal fun consultAdd(list: List<ConsultItem>, item: ConsultItem): List<ConsultItem>? = if (isConsulted(list, item)) null else list + item
