package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T-15（`docs/overhaul_detailed_spec.md`）: JSON の往復で差分 0、勤務表 CSV の書き出しがバイト一致。
 * CSV の取込の再現（4 fixture）は `CsvRoundTripTest` が見る。ここは全 fixture での JSON の往復・冪等と、CSV の書き出しの決定性・冪等を見る。
 */
class StateRoundTripTest {
    private fun load(name: String): MagiState {
        val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
        assertNotNull("$name.json がテストリソースにありません", json)
        return StateParser.parse(json!!)!!
    }

    private fun board(st: MagiState) = Array(st.schedule.size) { st.schedule[it].toIntArray() }

    /** 保存で書き戻さない評価情報（`StateParser.DERIVED_KEYS`＝勤務表から導くので保存し直すと食い違う）。往復の比較から除く。 */
    private val derived = StateParser.DERIVED_KEYS.toSet()

    /** parse → serialize → parse で同じ状態（評価情報のキーを除く）、2 回目の serialize は 1 回目と同じ文字列。 */
    @Test fun jsonRoundTripIsLosslessAndIdempotentOnEveryFixture() {
        for (name in CheckerSnapshotTest.FIXTURES) {
            val st = load(name)
            val json1 = StateParser.serialize(st, board(st))
            val st2 = StateParser.parse(json1)!!
            // extras の値は org.json のオブジェクト（等価が参照比較）なので、キーと文字列で比べる。ほかの項目は等価で比べる。
            assertEquals("$name: 往復した状態", st.copy(extras = emptyMap()), st2.copy(extras = emptyMap()))
            assertEquals("$name: 往復した extras", (st.extras - derived).mapValues { it.value.toString() }, st2.extras.mapValues { it.value.toString() })
            val json2 = StateParser.serialize(st2, board(st2))
            assertEquals("$name: 2 回目の書き出し", json1, json2)
        }
    }

    /** 同じ盤面の勤務表 CSV は 2 回書いてもバイト一致、取り込んで書き直してもバイト一致、取り込んだ盤面は元と同じ。 */
    @Test fun scheduleCsvIsDeterministicAndIdempotentOnEveryFixture() {
        for (name in CheckerSnapshotTest.FIXTURES) {
            val st = load(name)
            val p = Problem(st)
            val sched = normalizeSchedule(board(st), p)
            val csv1 = ScheduleCsvBridge.build(st, sched)
            val csv2 = ScheduleCsvBridge.build(st, sched.copy2D())
            assertArrayEquals("$name: 2 回の書き出しのバイト", csv1.toByteArray(Charsets.UTF_8), csv2.toByteArray(Charsets.UTF_8))
            val r = ScheduleCsvBridge.parse(csv1, st, Array(st.staffCount) { IntArray(st.dayCount) { -1 } })
            assertEquals("$name: 全員一致", st.staffCount, r.matched)
            for (i in 0 until p.S) assertArrayEquals("$name: 行 $i", sched[i], r.schedule[i])
            assertEquals("$name: 取り込んで書き直した CSV", csv1, ScheduleCsvBridge.build(st, r.schedule))
        }
    }
}
