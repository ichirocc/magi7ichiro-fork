package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** [外部レビュー P2] 同じ名前の職員が複数いる勤務表CSVは、その名前の行を取り込まず警告する（先勝ちで別の職員を上書きしない）。 */
class ScheduleCsvDuplicateNameTest {

    private val json = """
        {
          "startDate": "2026-07-01", "endDate": "2026-07-03",
          "shifts": [{"name":"日勤","kigou":"日","need1":"0","need2":""},{"name":"夜勤","kigou":"夜","need1":"0","need2":""},{"name":"休み","kigou":"休","need1":"0","need2":""}],
          "groups": [{"name":"A","kigou":"A"}],
          "staff": [{"name":"山田","groupIdx":0,"skillIdx":0},{"name":"鈴木","groupIdx":0,"skillIdx":0},{"name":"山田","groupIdx":0,"skillIdx":0}],
          "schedule": [[0,0,0],[2,2,2],[1,1,1]]
        }
    """.trimIndent()

    @Test
    fun sameNameStaffRoundTripLeavesBoardUnchangedAndWarns() {
        val st = StateParser.parse(json)
        val sched = arrayOf(intArrayOf(0, 0, 0), intArrayOf(2, 2, 2), intArrayOf(1, 1, 1))
        val r = ScheduleCsvBridge.parse(ScheduleCsvBridge.build(st, sched), st, sched.map { it.clone() }.toTypedArray())
        for (i in sched.indices) assertArrayEquals("行 $i", sched[i], r.schedule[i])
        assertEquals(listOf("山田"), r.ambiguousNames)
        assertEquals(1, r.matched)
        assertEquals("同じ名前の職員が複数いるため取り込みませんでした: 山田", csvAmbiguousText(r.ambiguousNames))
    }

    @Test
    fun ambiguousRowsAreNotAppliedWhileOthersAre() {
        val st = StateParser.parse(json)
        val base = arrayOf(intArrayOf(0, 0, 0), intArrayOf(2, 2, 2), intArrayOf(1, 1, 1))
        val r = ScheduleCsvBridge.parse("山田,休,休,休\n鈴木,日,日,日\n", st, base.map { it.clone() }.toTypedArray())
        assertArrayEquals(base[0], r.schedule[0])
        assertArrayEquals(intArrayOf(0, 0, 0), r.schedule[1])
        assertArrayEquals(base[2], r.schedule[2])
    }

    @Test
    fun duplicateRowsForUniqueNameKeepLastRowAndWarn() {
        val st = StateParser.parse(json)
        val base = arrayOf(intArrayOf(0, 0, 0), intArrayOf(2, 2, 2), intArrayOf(1, 1, 1))
        val r = ScheduleCsvBridge.parse("鈴木,日,日,日\n鈴木,夜,夜,夜\n", st, base.map { it.clone() }.toTypedArray())
        assertArrayEquals(intArrayOf(1, 1, 1), r.schedule[1])
        assertEquals(listOf("鈴木"), r.duplicateRowNames)
        assertEquals(emptyList<String>(), r.ambiguousNames)
    }
}
