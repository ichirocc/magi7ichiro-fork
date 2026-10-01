package com.magi.app.v6

import com.magi.app.model.ManualPin
import com.magi.app.model.StateParser
import com.magi.app.model.pinAt
import com.magi.app.model.withPinsFollowingBoard
import com.magi.app.ui.PIN_HARD_HINT
import com.magi.app.ui.PIN_NOT_CANDO_HINT
import com.magi.app.ui.pinRegisterHint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSV 取込で盤面が置き換わったとき、手動固定は取り込んだ値へ追従する（手の編集と同じ規約）。 */
class CsvPinFollowTest {
    private val json = """
        {
          "startDate": "2026-07-01", "endDate": "2026-07-03",
          "shifts": [{"name":"日勤","kigou":"日","need1":"0","need2":""},{"name":"夜勤","kigou":"夜","need1":"0","need2":""},{"name":"休み","kigou":"休","need1":"0","need2":""}],
          "groups": [{"name":"A","kigou":"A"}],
          "staff": [{"name":"山田","groupIdx":0,"skillIdx":0},{"name":"鈴木","groupIdx":0,"skillIdx":0}],
          "schedule": [[2,1,2],[2,2,2]]
        }
    """.trimIndent()

    private fun st() = StateParser.parse(json)!!.copy(manualPins = listOf(ManualPin(0, 1, 1), ManualPin(1, 2, 2)))
    private fun board(vararg r: IntArray) = arrayOf(*r)

    @Test fun pinFollowsTheImportedValueAndUnchangedCellKeepsItsPin() {
        val old = board(intArrayOf(2, 1, 2), intArrayOf(2, 2, 2))
        val new = board(intArrayOf(2, 0, 2), intArrayOf(2, 2, 2))
        val (ns, n) = st().withPinsFollowingBoard(old, new)
        assertEquals(1, n)
        assertEquals(0, ns.pinAt(0, 1)!!.shift)
        assertEquals(2, ns.pinAt(1, 2)!!.shift)
    }

    @Test fun pinIsDroppedWhenTheImportedValueIsMinusOneOrOutOfRange() {
        val old = board(intArrayOf(2, 1, 2), intArrayOf(2, 2, 2))
        val (a, na) = st().withPinsFollowingBoard(old, board(intArrayOf(2, -1, 2), intArrayOf(2, 2, 2)))
        assertEquals(1, na); assertNull(a.pinAt(0, 1)); assertEquals(2, a.pinAt(1, 2)!!.shift)
        val (b, nb) = st().withPinsFollowingBoard(old, board(intArrayOf(2, 1, 2), intArrayOf(2, 2, 9)))
        assertEquals(1, nb); assertEquals(1, b.pinAt(0, 1)!!.shift); assertNull(b.pinAt(1, 2))
    }

    @Test fun noPinsOrNoChangeIsANoOp() {
        val old = board(intArrayOf(2, 1, 2), intArrayOf(2, 2, 2))
        val bare = st().copy(manualPins = emptyList())
        assertSame(bare, bare.withPinsFollowingBoard(old, board(intArrayOf(0, 0, 0), intArrayOf(0, 0, 0))).first)
        val s = st()
        val (same, n) = s.withPinsFollowingBoard(old, old)
        assertEquals(0, n); assertSame(s, same)
    }

    @Test fun pinThenCsvOverlayThenOptimizeKeepsTheCsvValue() = runBlocking {
        val s0 = st()
        val base = board(intArrayOf(2, 1, 2), intArrayOf(2, 2, 2))
        val res = ScheduleCsvBridge.parse("山田,休,日,休\n", s0, base)
        assertEquals(0, res.schedule[0][1])
        val (followed, n) = s0.withPinsFollowingBoard(base, res.schedule)
        assertEquals(1, n)
        val out = V6FinalPort.handleOptimize(followed.withSchedule(res.schedule), res.schedule.copy2D(), secondsRaw = 1, workers = 1,
            requestedAlgorithm = V6Algorithm.V5, allowImpossible = true, seed = 3L)
        assertEquals("取り込んだ値のまま", 0, out.schedule[0][1])
        // 追従しないと、旧い固定（夜）へ戻される
        val stale = V6FinalPort.handleOptimize(s0.withSchedule(res.schedule), res.schedule.copy2D(), secondsRaw = 1, workers = 1,
            requestedAlgorithm = V6Algorithm.V5, allowImpossible = true, seed = 3L)
        assertEquals(1, stale.schedule[0][1])
    }

    @Test fun pinRegisterHintOnlyInformsAndNeverClaimsImpossibility() {
        assertEquals("", pinRegisterHint(true, emptyList()))
        assertEquals("", pinRegisterHint(true, listOf("vio-c3mn", "vio-covO")))
        assertEquals(PIN_HARD_HINT, pinRegisterHint(true, listOf("vio-c3mn", "vio-c3n")))
        assertEquals(PIN_HARD_HINT, pinRegisterHint(true, listOf("vio-pref")))
        assertEquals(PIN_NOT_CANDO_HINT, pinRegisterHint(false, listOf("vio-groupViol")))
        assertEquals(PIN_NOT_CANDO_HINT, pinRegisterHint(false, emptyList()))
        assertTrue(!PIN_HARD_HINT.contains("直せません") && !PIN_NOT_CANDO_HINT.contains("直せません"))
    }
}
