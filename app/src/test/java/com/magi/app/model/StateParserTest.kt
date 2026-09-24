package com.magi.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [外部レビュー P2-02] `mapObjects`/`mapArrays` は要素がオブジェクト/配列でなければ黙って読み飛ばしていた
 * （null・数値・文字列の混入で staff/schedule 等が本来より短いリストへ静かに変わる）。
 * 明示的な失敗（IllegalArgumentException）へ変えたことをここで固定する。
 */
class StateParserTest {

    // 最小の妥当な JSON（staff 2件・shifts 1件）。壊れていない入力は従来どおり通ることの対照。
    private val validJson = """
        {
          "shifts": [{"name":"日勤","kigou":"日","need1":"1","need2":""}],
          "groups": [{"name":"A","kigou":"A"}],
          "staff": [{"name":"田中","groupIdx":0,"skillIdx":0},{"name":"鈴木","groupIdx":0,"skillIdx":0}]
        }
    """.trimIndent()

    @Test
    fun wellFormedArraysParseNormally() {
        val st = StateParser.parse(validJson)
        assertEquals(2, st.staff.size)
        assertEquals(1, st.shifts.size)
        assertEquals(1, st.groups.size)
    }

    @Test
    fun nullElementInStaffArrayThrowsInsteadOfSilentlyShrinking() {
        // staff[1] が null（配列としては2要素だが妥当なオブジェクトは1件だけ）。
        val corrupted = """
            {
              "shifts": [{"name":"日勤","kigou":"日","need1":"1","need2":""}],
              "groups": [{"name":"A","kigou":"A"}],
              "staff": [{"name":"田中","groupIdx":0,"skillIdx":0}, null]
            }
        """.trimIndent()
        val e = assertThrows(IllegalArgumentException::class.java) { StateParser.parse(corrupted) }
        // 旧実装ならここで例外が飛ばず staff.size==1 の「別の（縮んだ）勤務表」として静かに読み込めていた。
        assert(e.message?.contains("staff") == true) { "message should name the broken field: ${e.message}" }
    }

    @Test
    fun numberElementInScheduleRowArrayThrows() {
        // schedule の行そのもの（配列であるべき）が数値になっている壊れたケース。
        val corrupted = """
            {
              "shifts": [{"name":"日勤","kigou":"日","need1":"1","need2":""}],
              "groups": [{"name":"A","kigou":"A"}],
              "staff": [{"name":"田中","groupIdx":0,"skillIdx":0}],
              "schedule": [[0], 5]
            }
        """.trimIndent()
        val e = assertThrows(IllegalArgumentException::class.java) { StateParser.parse(corrupted) }
        assert(e.message?.contains("schedule") == true) { "message should name the broken field: ${e.message}" }
    }

    // ---- [外部レビュー N1] 休み（ShiftRole.Rest）の往復 ----

    private fun restIdx(st: MagiState) = st.shifts.indexOfFirst { it.role == ShiftRole.Rest }

    private fun twoShiftState(rest0: ShiftRole, rest1: ShiftRole) = StateParser.parse(validJson).copy(
        shifts = listOf(Shift("休み", "休", "", "", rest0), Shift("日勤", "日", "1", "", rest1)),
        groupShift = listOf(listOf(1, 1)), schedule = listOf(listOf(0), listOf(1)),
    )

    private fun roundTrip(st: MagiState) =
        StateParser.parse(StateParser.serialize(st, st.schedule.map { it.toIntArray() }.toTypedArray()))

    @Test
    fun restRoleTurnedOffSurvivesSaveAndReload() {
        // 旧: 保存は全シフト role="" で、読込の後方互換が「どれにも Rest が無い＝旧JSON」と見て記号"休"へ付け直していた。
        val back = roundTrip(twoShiftState(ShiftRole.None, ShiftRole.None))
        assertEquals("休みOFFで保存したのに再読込で休みが付いた", -1, restIdx(back))
    }

    @Test
    fun restRoleOnAnotherShiftSurvivesSaveAndReload() {
        assertEquals(1, restIdx(roundTrip(twoShiftState(ShiftRole.None, ShiftRole.Rest))))
        assertEquals(0, restIdx(roundTrip(twoShiftState(ShiftRole.Rest, ShiftRole.None))))
    }

    @Test
    fun legacyJsonWithoutRoleGetsRestBySymbol() {
        val legacy = """{"shifts":[{"name":"日勤","kigou":"日"},{"name":"休み","kigou":"休"}],"groups":[],"staff":[]}"""
        assertEquals(1, restIdx(StateParser.parse(legacy)))
    }

    @Test
    fun blankRoleFromEarlierSavesStillGetsRestBySymbol() {
        // 3.603.0〜の保存は非休を "" で書いていた（CSV取込のまま保存した原本も全シフト ""）＝旧JSONと同じく記号で付与する。
        val blank = """{"shifts":[{"name":"日勤","kigou":"日","role":""},{"name":"休み","kigou":"休","role":""}],"groups":[],"staff":[]}"""
        assertEquals(1, restIdx(StateParser.parse(blank)))
    }
}
