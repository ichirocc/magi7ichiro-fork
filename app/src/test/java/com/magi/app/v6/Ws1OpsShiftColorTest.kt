package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 表示色 `shiftColors` は記号がキー＝改名は付け替え、削除は取り除く（色が既定へ戻る・孤児キーが残る・
 *  同じ記号で作り直したシフトが黙って引き継ぐ、を防ぐ）。 */
class Ws1OpsShiftColorTest {
    private fun load(): MagiState =
        StateParser.parse(javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText())!!

    private fun grid(st: MagiState) = st.schedule.map { it.toIntArray() }.toTypedArray()

    private fun rename(st: MagiState, from: String, to: String): MagiState {
        val k = st.shifts.indexOfFirst { it.kigou == from }
        val sh = st.shifts[k]
        return Ws1Ops.editShift(st, k, sh.name, to, sh.need1, sh.need2, sh.role == com.magi.app.model.ShiftRole.Rest)
    }

    @Test fun renameMovesTheCustomColour() {
        val st = load()
        val c = st.shiftColors.getValue("Dﾃ")
        val after = rename(st, "Dﾃ", "D")
        assertEquals(c, after.shiftColors["D"])
        assertFalse("旧記号の孤児キーを残さない", "Dﾃ" in after.shiftColors)
        assertEquals("他のシフトの色は不変", st.shiftColors - "Dﾃ", after.shiftColors - "D")
    }

    @Test fun renameDropsAnOrphanColourUnderTheNewSymbol() {
        val st = load().let { it.copy(shiftColors = it.shiftColors - "Pﾅ" + ("X" to "#123456")) }
        val after = rename(st, "Pﾅ", "X")
        assertNull("色の無いシフトが孤児の色を引き継がない", after.shiftColors["X"])
    }

    @Test fun unchangedEditKeepsTheStateEqual() {
        val st = load()
        assertEquals("何も変えない編集は同じ状態（画面の no-op 判定が効く）", st, rename(st, "Dﾃ", "Dﾃ"))
    }

    @Test fun removeShiftDropsItsColour() {
        val st = load()
        val k = st.shifts.indexOfFirst { it.kigou == "A4" }
        val after = Ws1Ops.removeShift(st, grid(st), k).state
        assertFalse("A4" in after.shiftColors)
        assertEquals(st.shiftColors - "A4", after.shiftColors)
    }
}
