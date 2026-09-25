package com.magi.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunMarkerTest {
    @Test fun s5MarkerNamesCancelledWish() {
        val m = RunMarker.format(1L, "fg", 60, 4, "SA", RunMarker.S5(2, 4, "夜", "佐藤"))
        val s = RunMarker.parseS5(m)!!
        assertEquals(2, s.staff); assertEquals(4, s.day)
        assertEquals("前回の最適化は完了前に中断されました。入力は自動保存済みです。もう一度実行できます。（希望（佐藤 5日 夜）は取り消したまま保存されています）",
            RunMarker.interruptedInfo(m))
    }

    @Test fun oldMarkerKeepsTodaysText() {
        val old = """{"startedAt":1,"mode":"bg","budgetSec":60,"workers":4,"algorithm":"SA"}"""
        assertNull(RunMarker.parseS5(old))
        assertEquals("", RunMarker.s5Suffix(old))
        assertEquals("前回のバックグラウンド最適化は完了前に中断されました。入力は自動保存済みです。もう一度実行できます。", RunMarker.interruptedInfo(old))
        assertEquals(RunMarker.format(1L, "fg", 60, 4, "SA").contains("s5"), false)
    }

    @Test fun brokenMarkerFallsBack() {
        assertEquals("前回の最適化は完了前に中断されました。入力は自動保存済みです。", RunMarker.interruptedInfo("{"))
        assertEquals("", RunMarker.s5Suffix("""{"mode":"fg","s5":{"day":1}}"""))
    }
}
