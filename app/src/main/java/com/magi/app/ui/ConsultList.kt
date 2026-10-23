package com.magi.app.ui

import com.magi.app.v6.FixSuggestion
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [3.645.0/仕様 5.3・UX-05] 「相談してから決める」判断。対象（だれの・どの日の・何）と検討内容を持ち、セッション内だけ
 * （state 非保存＝見直し候補と同じ）。出力も判定も止めない＝確認・承認を必須にする範囲は職場の運用で決める（ユーザー決定 2026-10-09）。
 * 対象は氏名と実日付でも持つ＝職員の削除・並び替えや月の移動のあとに別の人・別の日を開かない（3.646.0、外部レビュー B01/B02）。
 * [staff]/[day] は積んだときの位置（氏名・日付が一致するときの近道）。[rosterKey] は積んだときの職員の並び（[rosterKeyOf]、0＝不明）＝
 * 同じ名前の職員が複数いるとき、並びが変わっていなければ位置を信じる（3.650.0）。
 * [staffId] は積んだときの職員 ID（[com.magi.app.model.Staff.id]）＝あれば対象はこれだけで引く（並び替え・改名・同名でも元の人。
 * 見つからなければ開かない）。氏名・[rosterKey] での引き直しは ID の無い相談だけの後備え。
 * [staffGone] は名簿の変更で ID が一度消えた印＝あとで同じ番号が別の人に振られても結ばない（削除した人が最大の ID だと次の追加で再び使われる）。
 */
data class ConsultItem(
    val subject: String, val note: String, val staff: Int? = null, val day: Int? = null,
    val staffName: String? = null, val date: String? = null,
    val chain: ChainTarget? = null,
    /** 保留（仕様 §4.2「保留する」）＝相談に回さず未解決のまま置く。相談と同じく違反は減らさない。 */
    val held: Boolean = false,
    val rosterKey: Int = 0,
    val staffId: Int? = null,
    val staffGone: Boolean = false,
    /** 関係者の回答（違反セルの半自動修正仕様 §4・§7、3.665.0 利用者決定「セッション内で回答を記録」）。了承でも違反は減らさない。 */
    val answer: ConsultAnswer = ConsultAnswer.PENDING,
)

/** 相談の回答。記録だけ＝了承・不可のどちらでも勤務表は変えない（当てるのは利用者の操作）。 */
enum class ConsultAnswer(val label: String) { PENDING("回答待ち"), AGREED("了承"), DECLINED("不可") }

/** 押すたびに 回答待ち→了承→不可→回答待ち。 */
internal fun ConsultAnswer.next(): ConsultAnswer = ConsultAnswer.entries[(ordinal + 1) % ConsultAnswer.entries.size]

/**
 * いま有効な回答。入れ替えの相談は、回答のあとで勤務表か設定が変わると案の中身が変わる＝以前の回答を流用せず「回答待ち」に戻す
 * （仕様 §7「相談後に案の内容が変更: 以前の回答を自動的に流用しない」）。希望・1 セルの相談は対象が変わらないので記録どおり。
 */
internal fun consultAnswerOf(c: ConsultItem, boardKey: Long, stateKey: Long): ConsultAnswer {
    val t = c.chain ?: return c.answer
    return if (t.boardKey == 0L || t.boardKey != boardKey || t.stateKey != stateKey) ConsultAnswer.PENDING else c.answer
}

/** 回答の 1 行。記録と有効な回答が違えば（案が変わった）その理由を添える。 */
internal fun consultAnswerLine(c: ConsultItem, effective: ConsultAnswer): String =
    "回答: ${effective.label}" + (if (effective != c.answer) "（回答のあとで勤務表か設定が変わり案の中身が変わるため、以前の「${c.answer.label}」は使いません）" else "")

/** 複数人の入れ替えの相談が指す枠。日付とシフト記号で持つ＝月の移動・シフトの並び替えのあとも今の勤務表で案を探し直せる。
 *  枠を持たない案（ホーム・分析の「この手を使う」から）は [suggestion] と、その案を出したときの盤面と設定の指紋 [boardKey]/[stateKey]
 *  を持つ（3.650.0）。指紋が今と違えば案は当てない＝並び替えのあとに同じ番号の別の人へ当てない。 */
data class ChainTarget(
    val date: String?, val shiftSymbol: String?, val label: String, val suggestion: FixSuggestion? = null,
    val boardKey: Long = 0L, val stateKey: Long = 0L,
    /** 案を出したときに見ていた問題（[MagiViewModel.applyFixSuggestion] がその数が減るかを確かめる）。null＝全体の研磨の案。 */
    val fixTarget: FixTarget? = null,
    val globalPolish: Boolean = false,
)

internal const val CONSULT_BUTTON = "相談事項に登録"
internal const val CONSULT_DONE = "相談中"
/** 「相談中」の札の隣。積んだ相談を取り消す（済にはしない＝記録に残さない）。 */
internal const val CONSULT_UNDO_BUTTON = "取り消す"
internal const val CONSULT_HOLD_BUTTON = "保留する"
internal const val CONSULT_HELD = "保留中"
internal const val CONSULT_ADDED = "ホームの「相談中」に登録しました（記録だけで、本人への連絡や了承にはなりません）"
internal const val CONSULT_DUPLICATE = "この内容は、すでにホームの「相談中」に登録されています"
internal const val CONSULT_OPEN = "開く"
internal const val CONSULT_RESUME = "案を見る"
internal const val CONSULT_STALE = "登録後に勤務表か設定が変わったため、この案は当てられません。「変更案を探す」で確かめ直してください"

/** ホームの主カードの 1 行。0 件なら出さない。 */
internal fun consultLine(n: Int): String? = if (n > 0) "相談中 ${n}件" else null

/** 未解決の相談＝保留か、回答待ち（了承・不可の回答があれば数えない）。 */
internal fun consultUnresolved(c: ConsultItem): Boolean = c.held || c.answer == ConsultAnswer.PENDING

/** 相談中と保留を分けて言う（未解決だけ数える。どちらも違反は減らさない）。 */
internal fun consultLine(items: List<ConsultItem>): String? {
    val open0 = items.filter(::consultUnresolved)
    if (open0.isEmpty()) return null
    val held = open0.count { it.held }
    return "相談中 ${open0.size}件" + if (held > 0) "（保留 $held）" else ""
}

internal fun isoDate(startDate: String, day: Int): String? =
    runCatching { LocalDate.parse(startDate).plusDays(day.toLong()).toString() }.getOrNull()

/** [date] が今の期間の何日目か（期間の外・読めない日付は null）。 */
internal fun dayIndexOf(startDate: String, date: String, days: Int): Int? = runCatching {
    ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(date)).toInt().takeIf { it in 0 until days }
}.getOrNull()

private fun shortDate(date: String): String =
    runCatching { LocalDate.parse(date).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrDefault(date)

/** 職員の並びの指紋（同じ名前の職員を位置で区別してよいかの判定に使う。0 は「不明」に取っておく）。 */
internal fun rosterKeyOf(staffNames: List<String>): Int = staffNames.hashCode().let { if (it == 0) 1 else it }

/** 相談の職員を今の一覧で探す。職員 ID があればそれだけで（今の一覧に無ければ null＝別の人に結ばない）。
 *  ID が無ければ氏名で（積んだときの位置が同じ氏名なら近道）、氏名も無ければ位置だけ。
 *  同じ名前が複数いれば氏名では区別できない＝積んだときと並びが同じときだけ位置を信じ、それ以外は開かない（3.650.0）。 */
internal fun consultStaff(c: ConsultItem, staffNames: List<String>, staffIds: List<Int> = emptyList()): Int? {
    if (c.staffGone) return null
    if (c.staffId != null && staffIds.isNotEmpty()) return staffIds.indexOf(c.staffId).takeIf { it >= 0 }
    val name = c.staffName ?: return c.staff?.takeIf { it in staffNames.indices }
    val at = c.staff
    if (staffNames.count { it == name } > 1) {
        return at?.takeIf { c.rosterKey != 0 && c.rosterKey == rosterKeyOf(staffNames) && staffNames.getOrNull(it) == name }
    }
    if (at != null && staffNames.getOrNull(at) == name) return at
    return staffNames.indexOf(name).takeIf { it >= 0 }
}

/** 相談の日を今の期間で探す。日付があればそれで、無ければ日番号だけ。 */
internal fun consultDay(c: ConsultItem, startDate: String, days: Int): Int? {
    val date = c.date ?: return c.day?.takeIf { it in 0 until days }
    return dayIndexOf(startDate, date, days)
}

/** 相談の対象セル（職員, 日）。氏名が今の一覧になければ null、日付が今の期間の外なら null＝「開く」を出さない。 */
internal fun consultCell(c: ConsultItem, startDate: String, staffNames: List<String>, days: Int, staffIds: List<Int> = emptyList()): Pair<Int, Int>? {
    val i = consultStaff(c, staffNames, staffIds) ?: return null
    val j = consultDay(c, startDate, days) ?: return null
    return i to j
}

/** 入れ替えの相談の枠（日, シフト）。日付が期間の外・記号が今のシフトに無ければ null。 */
internal fun consultChainTarget(t: ChainTarget, startDate: String, shiftSymbols: List<String>, days: Int): Pair<Int, Int>? {
    val j = t.date?.let { dayIndexOf(startDate, it, days) } ?: return null
    val k = t.shiftSymbol?.let { sym -> shiftSymbols.indexOf(sym).takeIf { it >= 0 } } ?: return null
    return j to k
}

/** 積むときに対象の職員 ID を付ける（位置と氏名が今の一覧と合うときだけ。ID の無いデータ＝0 は付けない）。 */
internal fun consultStampStaffId(c: ConsultItem, staffNames: List<String>, staffIds: List<Int>): ConsultItem {
    val at = c.staff ?: return c
    if (c.staffId != null || c.chain != null) return c
    if (c.staffName != null && staffNames.getOrNull(at) != c.staffName) return c
    val id = staffIds.getOrNull(at)?.takeIf { it > 0 } ?: return c
    return c.copy(staffId = id)
}

/**
 * 名簿が変わったら相談の表示用の氏名と位置を今に合わせる。ID を持つ相談は ID で引き、見つかれば今の氏名・位置へ
 * （削除で見つからなければ [ConsultItem.staffGone]＝以後は開かず、元の氏名で案内する）。
 * ID の無い相談だけ [3.651.0] の改名の後備え: 人数が同じで 1 か所だけ名前が変わった＝改名とみなし、改名前の名簿で引けた相談を
 * 同じ位置の今の名前と今の名簿の指紋に書き換える（並び替えは 2 か所以上、追加・削除は人数が変わる＝氏名で引き直す）。
 */
internal fun followRenameInConsults(items: List<ConsultItem>, before: List<String>, after: List<String>, afterIds: List<Int> = emptyList()): List<ConsultItem> {
    if (items.isEmpty()) return items
    val renamed = before.size == after.size && before.indices.count { before[it] != after[it] } == 1
    val roster = rosterKeyOf(after)
    return items.map { c ->
        if (c.staffGone) c
        else if (c.staffId != null && afterIds.isNotEmpty()) {
            val at = afterIds.indexOf(c.staffId)
            when {
                at < 0 -> c.copy(staffGone = true)
                c.staff == at && c.staffName == after[at] -> c
                else -> c.copy(staffName = after[at], staff = at)
            }
        } else if (renamed) {
            val at = if (c.staffName == null) null else consultStaff(c, before)
            if (at == null) c else c.copy(staffName = after[at], staff = at, rosterKey = roster)
        } else c
    }
}

/** 積んだ案が今の勤務表・設定で出したものと違うか（指紋が無い・違う＝当てずに探し直しを促す）。枠を持つ相談は探し直すので関係しない。 */
internal fun consultChainStale(t: ChainTarget, boardKey: Long, stateKey: Long): Boolean =
    t.suggestion != null && (t.boardKey == 0L || t.boardKey != boardKey || t.stateKey != stateKey)

/** 入れ替えの相談を今の勤務表で見直せるか（枠を引き直せる、または案そのものを持つ）。 */
internal fun consultChainResumable(t: ChainTarget, startDate: String, shiftSymbols: List<String>, days: Int): Boolean =
    consultChainTarget(t, startDate, shiftSymbols, days) != null || t.suggestion != null

/** 「開く」「案を見る」を出せない理由（対象をもともと持たない相談は null＝何も出さない）。 */
internal fun consultTargetNote(c: ConsultItem, startDate: String, staffNames: List<String>, shiftSymbols: List<String>, days: Int, staffIds: List<Int> = emptyList()): String? {
    c.chain?.let { t ->
        if (consultChainResumable(t, startDate, shiftSymbols, days)) return null
        return "いまの期間・シフトにない枠なので、案を見られません（${t.date?.let { shortDate(it) } ?: "?"} の「${t.shiftSymbol ?: "?"}」）"
    }
    if (c.staff == null && c.staffName == null && c.day == null && c.date == null) return null
    if (consultCell(c, startDate, staffNames, days, staffIds) != null) return null
    if (consultStaff(c, staffNames, staffIds) == null) {
        val name = c.staffName
        val byId = c.staffGone || (c.staffId != null && staffIds.isNotEmpty())
        if (!byId && name != null && staffNames.count { it == name } > 1) return "同じ名前の職員が複数いて、どの人か決められないので開けません（${name}さん）"
        return "いまの職員一覧にいないので開けません" + (name?.let { "（${it}さん）" } ?: "")
    }
    return "いまの期間にない日なので開けません" + (c.date?.let { "（${shortDate(it)}）" } ?: "")
}

/** ぶつかっている希望の行から: 希望を調整するか勤務を変えるかの判断。 */
internal fun consultWish(name: String, dayLabel: String, symbol: String?, reason: String, staff: Int, day: Int, date: String? = null) =
    ConsultItem("$name $dayLabel の希望" + (symbol?.let { "「$it」" } ?: ""), "希望を調整するか勤務を変えるか: $reason", staff, day, staffName = name, date = date)

/** なおし方（1 人を入れる）の枠から: だれを入れるかの判断。 */
internal fun consultShortage(dayLabel: String, symbol: String, names: List<String>) = ConsultItem(
    "$dayLabel の「$symbol」の人員不足",
    if (names.isEmpty()) "入れる人を相談" else "だれかを入れる: " + names.take(5).joinToString("・") + (if (names.size > 5) " ほか${names.size - 5}人" else ""),
)

/** 複数人の入れ替えの一覧から: 変わる人と勤務。枠を持てば、あとで今の勤務表で案を探し直せる。 */
internal fun consultChain(p: ChainFixPreview) = ConsultItem(p.suggestion.label, p.changes.joinToString("／") + "（${p.hardLine}）", chain = p.target?.copy(fixTarget = p.fixTarget, globalPolish = p.globalPolish))

/** 直す 1 手から。 */
internal fun consultFix(s: FixSuggestion) = ConsultItem(s.label, fixImpactLines(s).let { (h, c) -> h + (c?.let { "・$it" } ?: "") })

/** つくる前の確認の行から: 残る項目を希望か設定のどちらで解くか。 */
internal fun consultPreRun(row: PreRunRow, staffName: String? = null, date: String? = null) =
    ConsultItem(row.text, "何度つくっても残る項目。希望か設定のどちらを変えるかを相談", row.staff, row.day, staffName = staffName, date = date)

/** すでに一覧にあるか（ボタンを「相談中」にして形で返す＝シートの下では Snackbar が見えない）。 */
internal fun isConsulted(list: List<ConsultItem>, item: ConsultItem): Boolean = list.any { it.subject == item.subject && it.note == item.note }

/** 積んだ同じ項目（相談中か保留かを札に出す）。 */
internal fun consultMatch(list: List<ConsultItem>, item: ConsultItem): ConsultItem? = list.firstOrNull { it.subject == item.subject && it.note == item.note }

/** 同じ対象・内容は 2 度積まない（null＝重複）。 */
internal fun consultAdd(list: List<ConsultItem>, item: ConsultItem): List<ConsultItem>? = if (isConsulted(list, item)) null else list + item
