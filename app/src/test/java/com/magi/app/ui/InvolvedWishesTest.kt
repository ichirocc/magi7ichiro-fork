package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [思考誘導S3] 必須違反に関わる希望の列挙: pref はそのセル、c3w は翌日の希望、c3n は希望セルに掛かるときだけ。 */
class InvolvedWishesTest {
    @Test fun listsWishesInvolvedInHardViolations() {
        val ui = UiState(
            staffNames = listOf("山田", "佐藤"),
            wishes = mapOf("1,4" to 0),
            violationCellFamilies = mapOf(
                "0,2" to listOf("vio-pref"),
                "1,3" to listOf("vio-c3w"),
                "1,4" to listOf("vio-c3n"),
                "0,5" to listOf("vio-c3n"),      // 希望でないセルの禁止の並び＝対象外
                "0,6" to listOf("vio-c1"),
            ),
        )
        val got = involvedWishes(ui).map { Triple(it.name, it.day, it.reason) }
        assertEquals(listOf(
            Triple("山田", 2, "希望の勤務になっていません"),
            Triple("佐藤", 4, "前日（4日）に置けない勤務が入っています"),
            Triple("佐藤", 4, "希望が禁止の並びに掛かっています"),
        ), got)
    }

    @Test fun oneCellWithPrefAndC3wListsBothWishes() {
        // 同じセルに希望違反と希望前日の禁止が重なる＝そのセルの希望と翌日の希望の両方が関わる。
        val ui = UiState(staffNames = listOf("山田"), violationCellFamilies = mapOf("0,2" to listOf("vio-pref", "vio-c3w")))
        assertEquals(listOf(2 to "希望の勤務になっていません", 3 to "前日（3日）に置けない勤務が入っています"),
            involvedWishes(ui).map { it.day to it.reason })
    }
}
