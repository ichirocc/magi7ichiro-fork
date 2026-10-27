package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-10-27: iOS のリマインダー（配布前の確認の並び）・天気（日ごとの印）・時計（作成の輪）の純粋な部分。 */
class IosPatternsTest {
    @Test fun progressRingUsesTheSameFrameAsTheRemainingTime() {
        // 予算 60 秒＋後処理 10＋玉突き 10＝80 秒の枠。
        assertEquals(0f, runProgressFraction(60, 0L), 1e-6f)
        assertEquals(0.5f, runProgressFraction(60, 40_000L), 1e-6f)
        assertEquals(1f, runProgressFraction(60, 999_000L), 1e-6f)
    }

    @Test fun dayMarksShowShortageAndHardCellsPerDay() {
        val ui = UiState(days = 3, startDate = "2026-10-01",
            needViolations = mapOf("2,1" to "vio-covU", "3,2" to "vio-covO"),
            violationCellFamilies = mapOf("0,2" to listOf("vio-c3w"), "4,2" to listOf("vio-c1")))
        val m = dayStripMarks(ui)
        assertEquals(listOf(false, true, false), m.map { it.short })
        assertEquals(listOf(0, 0, 1), m.map { it.hardCells })   // c1（要調整）は数えない・人員過剰は不足ではない
        assertEquals("1", m[0].dom); assertEquals("木", m[0].dow)
        assertEquals("問題なし 1日 ・ 見ておく日 2日（押すとその日へ）\n$DAY_STRIP_LEGEND", dayStripSummary(m))
        assertEquals("2日 金曜 人員不足", dayMarkSpoken(m[1]))
        assertEquals("3日すべて 問題なし", dayStripSummary(dayStripMarks(UiState(days = 3, startDate = "2026-10-01"))))
    }

    @Test fun preDistributionPutsOpenItemsFirst() {
        val rows = listOf(PreDistRow("評価", "済", true), PreDistRow("必須", "2件", false), PreDistRow("相談", "未", null), PreDistRow("保存", "済", true))
        assertEquals(listOf("必須", "相談", "評価", "保存"), preDistributionOrder(rows).map { it.label })
    }

    @Test fun highlightSaysBeforeAndAfterByUnit() {
        assertEquals("必須違反 1件・3人 → 1件 ・ 要調整 30件 → 27件", runDeltaText(mapOf("c3w" to 1, "covU" to 3, "c1" to 30), mapOf("c3w" to 1, "c1" to 27)))
        assertEquals(null, runDeltaText(mapOf("c1" to 3), mapOf("c1" to 3)))
    }
}
