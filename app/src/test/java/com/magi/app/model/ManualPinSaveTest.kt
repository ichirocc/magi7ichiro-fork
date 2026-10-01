package com.magi.app.model

import com.magi.app.v6.withSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

/** [外部レビュー P1] 構造・制約を編集せずに手動固定だけ付け外ししても、保存（exportCurrent）に今の固定が載る。 */
class ManualPinSaveTest {

    private fun json(pins: String) = """
        {
          "startDate": "2026-07-01", "endDate": "2026-07-02",
          "shifts": [{"name":"日勤","kigou":"日","need1":"1","need2":""},{"name":"休み","kigou":"休","need1":"0","need2":""}],
          "groups": [{"name":"A","kigou":"A"}],
          "staff": [{"name":"田中","groupIdx":0,"skillIdx":0},{"name":"鈴木","groupIdx":0,"skillIdx":0}],
          "schedule": [[0,1],[1,0]]$pins
        }
    """.trimIndent()

    private fun saveAndReload(orig: String, st: MagiState, sched: Array<IntArray>): MagiState =
        StateParser.parse(StateParser.exportCurrent(orig, st, sched, structureEdited = false, constraintsEdited = false)!!)

    @Test
    fun addedPinSurvivesScheduleOnlySave() {
        val orig = json("")
        val st0 = StateParser.parse(orig)
        val sched = arrayOf(intArrayOf(0, 1), intArrayOf(1, 0))
        val st = st0.withSchedule(sched).togglePin(0, 1, 1)
        assertEquals(listOf(ManualPin(0, 1, 1)), saveAndReload(orig, st, sched).manualPins)
    }

    @Test
    fun removedPinIsGoneAfterSave() {
        val orig = json(""","manualPins":[{"staff":0,"day":1,"shift":1},{"staff":1,"day":0,"shift":1}]""")
        val st0 = StateParser.parse(orig)
        val sched = arrayOf(intArrayOf(0, 1), intArrayOf(1, 0))
        val st = st0.withSchedule(sched).togglePin(0, 1, 1)
        assertEquals(listOf(ManualPin(1, 0, 1)), saveAndReload(orig, st, sched).manualPins)
        val none = st.togglePin(1, 0, 1)
        assertEquals(emptyList<ManualPin>(), saveAndReload(orig, none, sched).manualPins)
    }

    @Test
    fun pinnedCellEditFollowsIntoSave() {
        val orig = json(""","manualPins":[{"staff":0,"day":1,"shift":1}]""")
        val sched = arrayOf(intArrayOf(0, 0), intArrayOf(1, 0))
        val st = StateParser.parse(orig).withSchedule(sched).withPinsFollowing(listOf(0 to 1), 0)
        val back = saveAndReload(orig, st, sched)
        assertEquals(listOf(ManualPin(0, 1, 0)), back.manualPins)
        assertEquals(0, back.schedule[0][1])
    }

    @Test
    fun noPinsKeyIsNotAddedWhenThereAreNoPins() {
        val orig = json("")
        val st = StateParser.parse(orig)
        val out = StateParser.exportCurrent(orig, st, arrayOf(intArrayOf(0, 1), intArrayOf(1, 0)), false, false)!!
        assertEquals(false, org.json.JSONObject(out).has("manualPins"))
    }
}
