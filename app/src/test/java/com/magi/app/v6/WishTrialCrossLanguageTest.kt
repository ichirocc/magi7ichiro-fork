package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [S5 T8] 希望取り消し試算の言語跨ぎ契約。`wish_trial_expected.txt` は C#（-MAGI_PC `MagiEngine.Tests/Fixtures`）と同一の複製。
 * 盤面は保存値のまま、各 fixture の wishLocked 希望をキー順に先頭 3 件。
 */
class WishTrialCrossLanguageTest {
    private val fixtures = listOf("sample_state_v6.json", "blocked_covu_state.json")

    private fun actualLines(): List<String> = fixtures.flatMap { f ->
        val st = StateParser.parse(javaClass.getResource("/$f")!!.readText())
        val sched = st.schedule.toIntArray2D()
        val ctl = (WishTrial.control(st, sched) as WishTrial.ControlOutcome).control
        val keys = WishTrial.lockedWishKeys(st).sortedWith(compareBy({ it.split(",")[0].trim().toInt() }, { it.split(",")[1].trim().toInt() })).take(3)
        listOf("$f|control -> ${ctl.h0},${ctl.rk}") + keys.map { k ->
            val (i, j) = k.split(",").map { it.trim().toInt() }
            val r = WishTrial.trial(st, sched, i, j, ctl) as WishTrial.Result
            "$f|$i,$j -> ${r.h0},${r.hx},${r.rk},${r.rr},${r.a},${r.att},${r.aPrime},${r.b},${r.pKeep},${r.pCancel}"
        }
    }

    @Test
    fun wishTrialMatchesTheSharedCrossLanguageExpectation() {
        val expected = javaClass.getResource("/wish_trial_expected.txt")!!.readText().lines()
            .map { it.trim() }.filter { !it.startsWith("#") && it.contains(" -> ") }
        assertEquals(expected.joinToString("\n"), actualLines().joinToString("\n"))
    }
}
