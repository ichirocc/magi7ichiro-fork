package com.magi.app.model

import com.magi.app.v6.Ws1Ops
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 不変の職員 ID（2026-10-13 ユーザー決定）: 読込で欠け・重複・0 以下に振り、保存で書き、構造編集で保つ。 */
class StaffIdTest {

    private fun json(staff: String) = """
        {
          "startDate": "2026-07-01", "endDate": "2026-07-02",
          "shifts": [{"name":"日勤","kigou":"日","need1":"1","need2":""},{"name":"休み","kigou":"休","need1":"0","need2":""}],
          "groups": [{"name":"A","kigou":"A"}],
          "staff": [$staff],
          "schedule": [[0,1],[1,0],[0,0],[1,1]]
        }
    """.trimIndent()

    private val legacy = json("""{"name":"田中","groupIdx":0},{"name":"鈴木","groupIdx":0},{"name":"田中","groupIdx":0},{"name":"佐藤","groupIdx":0}""")

    private fun sched(st: MagiState) = st.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun legacyDataGetsIdsInReadingOrder() {
        assertEquals(listOf(1, 2, 3, 4), StateParser.parse(legacy).staff.map { it.id })
    }

    @Test fun missingDuplicateAndNonPositiveIdsGetMaxPlusOne() {
        val st = StateParser.parse(json("""{"id":7,"name":"a","groupIdx":0},{"id":7,"name":"b","groupIdx":0},{"id":0,"name":"c","groupIdx":0},{"name":"d","groupIdx":0},{"id":-3,"name":"e","groupIdx":0},{"id":2,"name":"f","groupIdx":0}"""))
        assertEquals(listOf(7, 8, 9, 10, 11, 2), st.staff.map { it.id })
    }

    /** 最大値が Int の上限でも負へ回り込まず、使われていない最小の正の ID を振る。 */
    @Test fun idsNeverWrapNegativeAtIntMax() {
        val st = StateParser.parse(json("""{"id":2147483647,"name":"a","groupIdx":0},{"name":"b","groupIdx":0},{"id":1,"name":"c","groupIdx":0},{"name":"d","groupIdx":0}"""))
        assertEquals(listOf(Int.MAX_VALUE, 2, 1, 3), st.staff.map { it.id })
        assertEquals(4, nextStaffId(st.staff))
    }

    @Test fun serializeRoundTripsIds() {
        val st = StateParser.parse(json("""{"id":5,"name":"a","groupIdx":0},{"id":2,"name":"b","groupIdx":0},{"name":"c","groupIdx":0},{"id":9,"name":"d","groupIdx":0}"""))
        assertEquals(listOf(5, 2, 10, 9), st.staff.map { it.id })
        val back = StateParser.parse(StateParser.serialize(st, sched(st)))
        assertEquals(st.staff, back.staff)
        assertEquals(10, JSONObject(StateParser.serialize(st, sched(st))).getJSONArray("staff").getJSONObject(2).getInt("id"))
    }

    @Test fun scheduleOnlyAndConstraintSavesWriteTheAssignedIds() {
        val st = StateParser.parse(legacy)
        val a = StateParser.exportCurrent(legacy, st, sched(st), structureEdited = false, constraintsEdited = false)!!
        val b = StateParser.exportCurrent(legacy, st, sched(st), structureEdited = false, constraintsEdited = true)!!
        for (out in listOf(a, b)) {
            val arr = JSONObject(out).getJSONArray("staff")
            assertEquals(listOf(1, 2, 3, 4), (0 until arr.length()).map { arr.getJSONObject(it).getInt("id") })
            assertEquals(st.staff, StateParser.parse(out).staff)
        }
    }

    @Test fun structureEditsKeepIdsAndAddingTakesMaxPlusOne() {
        val st = StateParser.parse(json("""{"id":3,"name":"a","groupIdx":0},{"id":8,"name":"b","groupIdx":0},{"id":5,"name":"c","groupIdx":0},{"id":1,"name":"d","groupIdx":0}"""))
        val added = Ws1Ops.addStaff(st, sched(st), "e", 0)
        assertEquals(listOf(3, 8, 5, 1, 9), added.state.staff.map { it.id })
        val moved = Ws1Ops.moveStaff(added.state, added.schedule, 0, +1)
        assertEquals(listOf(8, 3, 5, 1, 9), moved.state.staff.map { it.id })
        val renamed = Ws1Ops.editStaff(moved.state, 1, "a2", 0)
        assertEquals(3, renamed.staff[1].id)
        val removed = Ws1Ops.removeStaff(renamed, moved.schedule, 0)
        assertEquals(listOf(3, 5, 1, 9), removed.state.staff.map { it.id })
        val again = Ws1Ops.addStaff(removed.state, removed.schedule, "f", 0)
        assertEquals("次は今の最大＋1", 10, again.state.staff.last().id)
    }

    @Test fun removingAGroupKeepsIds() {
        val st = StateParser.parse(legacy).let { it.copy(groups = it.groups + Group("B", "B"), groupShift = it.groupShift + listOf(listOf(1, 1))) }
        val moved = st.copy(staff = st.staff.mapIndexed { i, s -> if (i == 1) s.copy(groupIdx = 1) else s })
        assertEquals(listOf(1, 2, 3, 4), Ws1Ops.removeGroup(moved, 0).staff.map { it.id })
    }
}
