package com.magi.app.ui

import com.magi.app.model.MagiState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 対象の月を移すときに「引き継ぐもの」と「消えるもの」（3.643.0、`Ws1Ops.resizeDays` と同じ規則で先に数える）。 */
data class MonthMovePlan(
    val year: Int, val month: Int, val days: Int,
    val carriedWishes: Int, val carriedNeedExceptions: Int, val carriedPins: Int,
    val droppedExtWishDays: Int, val droppedExtWishes: Int, val droppedPins: Int, val droppedNeedExceptions: Int,
) {
    /** 何も引き継がず何も消えないときだけ確認を省く。 */
    val needsConfirm: Boolean get() = carriedWishes + carriedNeedExceptions + carriedPins + droppedExtWishDays + droppedPins + droppedNeedExceptions > 0
}

internal fun monthMovePlan(state: MagiState, year: Int, month: Int): MonthMovePlan {
    val first = LocalDate.of(year, month, 1)
    val t = first.lengthOfMonth()
    fun dayOf(key: String) = key.split(",").getOrNull(1)?.toIntOrNull() ?: -1
    val needKeys = state.needDay1.keys + state.needDay2.keys
    var extDaysDropped = 0; var extDropped = 0
    for (e in state.extWishes) {
        val kept = e.days.count { d -> runCatching { ChronoUnit.DAYS.between(first, LocalDate.parse(d)).toInt() }.getOrNull()?.let { it in 0 until t } == true }
        extDaysDropped += e.days.size - kept
        if (kept == 0 && e.days.isNotEmpty()) extDropped++
    }
    return MonthMovePlan(
        year = year, month = month, days = t,
        carriedWishes = state.wishes.keys.count { dayOf(it) in 0 until t },
        carriedNeedExceptions = needKeys.count { dayOf(it) in 0 until t },
        carriedPins = state.manualPins.count { it.day in 0 until t },
        droppedExtWishDays = extDaysDropped, droppedExtWishes = extDropped,
        droppedPins = state.manualPins.count { it.day !in 0 until t },
        droppedNeedExceptions = needKeys.count { dayOf(it) !in 0 until t },
    )
}

/** 確認ダイアログの本文。引き継ぐ側は「同じ日番号に残る」と明記する（前月 5 日の希望は翌月 5 日に載る）。 */
internal fun monthMoveLines(p: MonthMovePlan): List<String> {
    val carry = ArrayList<String>()
    carry.add("勤務表の中身（同じ日番号に残ります）")
    if (p.carriedWishes > 0) carry.add("通常希望 ${p.carriedWishes} 件（同じ日番号に残ります＝前の月の希望です）")
    if (p.carriedNeedExceptions > 0) carry.add("必要人数の例外 ${p.carriedNeedExceptions} 件（同じ日番号）")
    if (p.carriedPins > 0) carry.add("手動固定 ${p.carriedPins} 件（同じ日番号）")
    val drop = ArrayList<String>()
    if (p.droppedExtWishDays > 0) drop.add("期間の外の拡張希望 ${p.droppedExtWishDays} 日分（日付で持つため${if (p.droppedExtWishes > 0) "・${p.droppedExtWishes} 件は丸ごと" else ""}）")
    if (p.droppedPins > 0) drop.add("期間の外の手動固定 ${p.droppedPins} 件")
    if (p.droppedNeedExceptions > 0) drop.add("期間の外の必要人数の例外 ${p.droppedNeedExceptions} 件")
    return listOf("引き継ぐ: " + carry.joinToString("・")) + (if (drop.isEmpty()) emptyList() else listOf("消える: " + drop.joinToString("・")))
}
