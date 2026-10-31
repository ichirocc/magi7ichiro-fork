package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RerunDraftTest {
    private fun state() = StateParser.parse(javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText())!!

    @Test
    fun variantsDifferFromTheBaseDraftAndFromEachOther() {
        val st = state()
        val base = SmartInitialScheduler.generate(st).schedule
        val v1 = V6FinalPort.rerunDraft(st, base, 1)
        val v2 = V6FinalPort.rerunDraft(st, base, 2)
        assertTrue("変化 1 は元の下書きと違う", RoleDiversityHelpers.scheduleDistance(base, v1) > 0)
        assertTrue("変化 2 は元の下書きと違う", RoleDiversityHelpers.scheduleDistance(base, v2) > 0)
        assertNotEquals("回ごとに違う下書き", v1.map { it.toList() }, v2.map { it.toList() })
        assertEquals("同じ回は同じ下書き（再現できる）", v1.map { it.toList() }, V6FinalPort.rerunDraft(st, base, 1).map { it.toList() })
    }

    @Test
    fun variantsKeepWishesAndExtBans() {
        val st = state()
        val p = Problem(st)
        val base = SmartInitialScheduler.generate(st).schedule
        for (v in 1..6) {
            val d = V6FinalPort.rerunDraft(st, base, v)
            for (i in 0 until p.S) for (j in 0 until p.T) if (p.wishLocked(i, j)) assertEquals("変化 $v は希望 ($i,$j) を崩さない", base[i][j], d[i][j])
            assertTrue("変化 $v は拡張希望の禁止に置かない", p.keepsExtBan(base, d))
        }
    }
}
