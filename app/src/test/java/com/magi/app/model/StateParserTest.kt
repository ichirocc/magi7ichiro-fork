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

    /** 型の違う並び・個人の上下限は空として取り込まず読み込みを止める（条件が黙って消えるのを防ぐ）。 */
    @Test
    fun malformedPatternOrRangeOrConstraintFieldThrows() {
        fun with(extra: String) = validJson.trimEnd().removeSuffix("}") + ",\n$extra\n}"
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""cons3n": [{"pattern": "A"}]""")) }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""cons3n": {"pattern": ["A"]}""")) }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""staffRange": {"0,0": "3"}""")) }
        assertEquals(emptyList<String>(), StateParser.parse(with(""""cons3n": [{}]""")).cons3n.single().pattern)   // 未指定は空のまま
    }

    /** 表・配列そのものの型違い（個人の上下限・希望・日別必要人数・手動固定・拡張希望）も空として取り込まない。 */
    @Test
    fun malformedTopLevelTablesThrowWithTheirName() {
        fun with(extra: String) = validJson.trimEnd().removeSuffix("}") + ",\n$extra\n}"
        for ((key, bad) in listOf("staffRange" to "\"3\"", "wishes" to "[1]", "needDay1" to "\"2\"", "needDay2" to "[]",
            "manualPins" to "{}", "extWishes" to "\"x\"", "schedule" to "{}", "skillGroups" to "\"x\"", "groupShift" to "{}")) {
            val e = assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with("\"$key\": $bad")) }
            assert(e.message?.contains(key) == true) { "message should name $key: ${e.message}" }
        }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""needDay1": {"0,0": [1]}""")) }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""extWishes": [{"staff": 0, "days": "1"}]""")) }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""wishes": {"0,0": "A"}""")) }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with(""""manualPins": [{"staff": 0, "day": 0.5, "shift": 0}]""")) }
    }

    /** 小数・範囲外の勤務番号は丸めずに止める（1.9 を 1 にすると別の勤務として入る）。整数値の小数と null は従来どおり。 */
    @Test
    fun fractionalOrOutOfRangeShiftNumberThrows() {
        fun with(cells: String) = validJson.trimEnd().removeSuffix("}") + ",\n\"schedule\": [[$cells],[0]]\n}"
        val e = assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with("1.9")) }
        assert(e.message?.contains("schedule[0][0]") == true) { e.message ?: "" }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with("1")) }    // shifts は 1 件＝0 だけが有効
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with("-2")) }
        assertThrows(IllegalArgumentException::class.java) { StateParser.parse(with("\"A\"")) }
        assertEquals(listOf(0, -1, -1), StateParser.parse(with("0.0, null, -1")).schedule[0])
    }

    /** 全体保存（serialize）も古い評価情報を書き戻さない（勤務表と食い違うため。通常保存と同じ）。 */
    @Test
    fun fullSaveDropsStaleDerivedKeys() {
        val withDerived = validJson.trimEnd().removeSuffix("}") + ",\n\"violations\": {\"0,0\": \"vio-x\"}, \"lastResult\": {\"hard\": 3}, \"note\": \"keep\"\n}"
        val st = StateParser.parse(withDerived)
        val out = org.json.JSONObject(StateParser.serialize(st, arrayOf(intArrayOf(0), intArrayOf(0))))
        assertEquals(false, out.has("violations"))
        assertEquals(false, out.has("lastResult"))
        assertEquals("keep", out.optString("note"))   // 評価情報でない未知のキーは残す
    }
}
