package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 制約CSVのグループ回数・スキルグループ回数の行も、個人レンジと同じく「空欄か 0 以上の整数、下限≤上限」だけを受理する。 */
class GroupRangeCsvValidationTest {
    private fun load(): MagiState {
        val json = javaClass.getResourceAsStream("/golden_state.json")!!.bufferedReader().readText()
        return StateParser.parse(json)!!.copy(skillGroups = listOf(Group("S", "S")))
    }

    private val kinds = listOf("群回数" to "グループのレンジ", "スキル群回数" to "スキルグループのレンジ")

    private fun parse(st: MagiState, kind: String, cells: String): ComponentImport {
        val g = if (kind == "群回数") st.groups[0].kigou else st.skillGroups[0].kigou
        return ConstraintsCsvIO.parse("$kind,$g,${st.shifts[3].kigou},$cells", st)!!
    }

    @Test fun invalidBoundsAreRejectedWithSamples() {
        val st = load()
        for ((kind, label) in kinds) {
            for (cells in listOf("abc,xyz", "abc,", ",xyz", "1,xyz", "1.5,2", "99999999999,", "-1,-1", "-1,", ",-2", "5,2")) {
                val r = parse(st, kind, cells)
                assertEquals("$kind $cells", 1, r.rejected)
                assertEquals("$kind $cells", 0, r.accepted)
                assertTrue("$kind $cells", r.samples.isNotEmpty())
                assertTrue("$kind $cells: ${r.samples}", r.samples[0].startsWith("${label}「"))
            }
        }
    }

    @Test fun validBoundsAreAccepted() {
        val st = load()
        for ((kind, _) in kinds) {
            for (cells in listOf(",3", "2,", "0,0", "3,3", "2,5", "１,２", " 1 , 2 ")) {
                val r = parse(st, kind, cells)
                assertEquals("$kind $cells", 0, r.rejected)
                assertEquals("$kind $cells", 1, r.accepted)
            }
            assertEquals("$kind 両方空は従来どおり拒否", 1, parse(st, kind, ",").rejected)
        }
    }

    @Test fun rejectedRowKeepsPreviousConstraintsUntouched() {
        val st = load()
        val r = parse(st, "群回数", "abc,xyz")
        assertEquals(1, r.rejected)
        assertEquals(0, r.accepted)
    }
}
