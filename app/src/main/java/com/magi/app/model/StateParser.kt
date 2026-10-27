package com.magi.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses / serializes the Web app's `state` JSON. Uses org.json (built into Android)
 * because the schema mixes types freely — e.g. need fields are sometimes an integer
 * and sometimes the empty string "". org.json tolerates that cleanly.
 */
object StateParser {
    private const val ROLE_REST = "rest"
    private const val ROLE_NONE = "none"

    /** 勤務表から導く評価情報＝保存し直すと勤務表と食い違うキー。どの保存経路でも書き戻さない。 */
    internal val DERIVED_KEYS = listOf("violations", "needViolations", "countViolations", "lastResult", "lastPhase")

    fun parse(json: String): MagiState {
        val o = JSONObject(json)

        var explicitRole = false
        val shiftsRaw = arrayOf(o, "shifts").mapObjects("shifts") {
            val r = it.optString("role")
            if (r == ROLE_REST || r == ROLE_NONE) explicitRole = true
            Shift(it.optString("name"), it.optString("kigou"), asStr(it.opt("need1")), asStr(it.opt("need2")),
                if (r == ROLE_REST) ShiftRole.Rest else ShiftRole.None)
        }
        // [3.603.0/backlog#24] 旧JSON（roleフィールド無し）の後方互換: どのシフトにもRestが
        //   付与されていなければ、記号"休"のシフト(最初の1件)へ自動で付与する。移行後はroleが
        //   唯一の正＝以後、記号を変えてもRestは追従しない（意図的な分離）。
        // [外部レビュー N1] 明示の role（"rest"/"none"）が1つでもあれば付与しない＝休みOFFの保存が往復する。
        //   "" は付与する（3.603.0〜の保存は非休を "" で書いており、CSV取込のまま保存した原本は全シフト ""）。
        val shifts = if (!explicitRole) {
            val restPos = shiftsRaw.indexOfFirst { it.kigou == "休" }
            if (restPos >= 0) shiftsRaw.mapIndexed { idx, s -> if (idx == restPos) s.copy(role = ShiftRole.Rest) else s }
            else shiftsRaw
        } else shiftsRaw
        val groups = arrayOf(o, "groups").mapObjects("groups") {
            Group(it.optString("name"), it.optString("kigou"))
        }
        val staff = withStaffIds(arrayOf(o, "staff").mapIndexedObjects("staff") { i, it ->
            Staff(it.optString("name"), intOf(it.opt("groupIdx"), 0, "staff[$i].groupIdx"),
                intOf(it.opt("skillIdx"), -1, "staff[$i].skillIdx"), intOf(it.opt("id"), 0, "staff[$i].id"))
        })
        val skillGroups = arrayOf(o, "skillGroups").mapObjects("skillGroups") {
            Group(it.optString("name"), it.optString("kigou"))
        }
        val groupShift = arrayOf(o, "groupShift").mapIndexedArrays("groupShift") { g, row ->
            (0 until row.length()).map { intOf(row.opt(it), 0, "groupShift[$g][$it]") }
        }
        val groupShiftApt = arrayOf(o, "groupShiftApt").mapArrays("groupShiftApt") { row ->
            (0 until row.length()).map { asStr(row.opt(it)) }
        }
        val schedule = arrayOf(o, "schedule").mapIndexedArrays("schedule") { i, row ->
            // [監査A9] null セルは 0(先頭シフト) でなく -1(未割当) に倒す（勝手な勤務化を防ぐ）。
            // 小数・文字・範囲外は丸めず読み込みを止める（1.9 を 1 にすると別の勤務として入る）。
            (0 until row.length()).map { j ->
                intOf(row.opt(j), -1, "schedule[$i][$j]").also {
                    if (it < -1 || it >= shifts.size) throw IllegalArgumentException("schedule[$i][$j] の勤務番号 $it が範囲外です（データが壊れています）")
                }
            }
        }

        val wishes = HashMap<String, Int>()
        objOf(o, "wishes")?.let { w ->
            w.keys().forEach { k -> wishes[k] = intOf(w.opt(k), -1, "wishes[\"$k\"]") }
        }
        val staffRange = HashMap<String, Range>()
        objOf(o, "staffRange")?.let { r ->
            r.keys().forEach { k ->
                // 型の違う値を読み飛ばすと個人の上下限が黙って消える＝読み込みを止めてキーを示す。
                val ro = r.optJSONObject(k)
                    ?: throw IllegalArgumentException("staffRange[\"$k\"] がオブジェクトではありません（データが壊れています）")
                staffRange[k] = Range(asStr(ro.opt("lo")), asStr(ro.opt("hi")))
            }
        }
        val needDay1 = strMap(objOf(o, "needDay1"), "needDay1")
        val needDay2 = strMap(objOf(o, "needDay2"), "needDay2")
        val shiftColors = strMap(objOf(o, "shiftColors"), "shiftColors")

        val cons1 = arrayOf(o, "cons1").mapObjects("cons1") {
            C1Row(asStr(it.opt("day1")), it.optString("shiftKigou"), asStr(it.opt("day2")))
        }
        val cons2 = arrayOf(o, "cons2").mapObjects("cons2") {
            C2Row(it.optString("shiftKigou"), asStr(it.opt("count")))
        }
        val cons3 = arrayOf(o, "cons3").mapObjects("cons3") { C3Row(patternOf(it, "cons3")) }
        val cons3n = arrayOf(o, "cons3n").mapObjects("cons3n") { C3Row(patternOf(it, "cons3n")) }
        val cons3m = arrayOf(o, "cons3m").mapObjects("cons3m") { C3Row(patternOf(it, "cons3m")) }
        val cons3mn = arrayOf(o, "cons3mn").mapObjects("cons3mn") { C3Row(patternOf(it, "cons3mn")) }
        val cons41 = arrayOf(o, "cons41").mapObjects("cons41") {
            C41Row(it.optString("groupKigou"), it.optString("shiftKigou"), asStr(it.opt("l")), asStr(it.opt("u")))
        }
        val cons42 = arrayOf(o, "cons42").mapObjects("cons42") {
            C42Row(it.optString("g1Kigou"), it.optString("g2Kigou"), it.optString("s1Kigou"), it.optString("s2Kigou"))
        }
        val cons41s = arrayOf(o, "cons41s").mapObjects("cons41s") {
            C41Row(it.optString("groupKigou"), it.optString("shiftKigou"), asStr(it.opt("l")), asStr(it.opt("u")))
        }
        val cons42s = arrayOf(o, "cons42s").mapObjects("cons42s") {
            C42Row(it.optString("g1Kigou"), it.optString("g2Kigou"), it.optString("s1Kigou"), it.optString("s2Kigou"))
        }
        val cons3w = arrayOf(o, "cons3w").mapObjects("cons3w") { C3wRow(it.optString("wishKigou"), it.optString("prevKigou")) }
        // [#41] 無いキー＝手動固定なし（古いデータはそのまま開く）。同じセルが重なれば後の行を採る。
        val manualPins = arrayOf(o, "manualPins").mapIndexedObjects("manualPins") { i, it ->
            ManualPin(intOf(it.opt("staff"), -1, "manualPins[$i].staff"), intOf(it.opt("day"), -1, "manualPins[$i].day"),
                intOf(it.opt("shift"), -1, "manualPins[$i].shift"))
        }.filter { it.staff >= 0 && it.day >= 0 && it.shift >= 0 }.associateBy { it.staff to it.day }.values.toList()

        val extWishes = arrayOf(o, "extWishes").mapIndexedObjects("extWishes") { n, e ->
            fun strs(key: String) = arrayOf(e, key, "extWishes[$n].$key")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            ExtWish(intOf(e.opt("staff"), -1, "extWishes[$n].staff"), strs("days"), strs("shifts"))
        }

        // Keep unmodelled top-level keys verbatim for lossless export.
        val modelled = setOf(
            "shifts", "groups", "staff", "groupShift", "groupShiftApt", "schedule", "wishes", "staffRange",
            "needDay1", "needDay2", "cons1", "cons2", "cons3", "cons3n", "cons3m", "cons3mn",
            "cons41", "cons42", "shiftColors", "startDate", "endDate", "use2Patterns",
            "skillGroups", "cons41s", "cons42s", "cons3w", "manualPins", "extWishes"
        )
        val extras = HashMap<String, Any?>()
        o.keys().forEach { key -> if (key !in modelled) extras[key] = o.get(key) }

        return MagiState(
            startDate = o.optString("startDate", "2025-01-01"),
            endDate = o.optString("endDate", ""),
            shifts = shifts, groups = groups, staff = staff,
            use2Patterns = o.optBoolean("use2Patterns", false),
            groupShift = groupShift, groupShiftApt = groupShiftApt, schedule = schedule,
            wishes = wishes, staffRange = staffRange,
            needDay1 = needDay1, needDay2 = needDay2,
            shiftColors = shiftColors,
            cons1 = cons1, cons2 = cons2,
            cons3 = cons3, cons3n = cons3n, cons3m = cons3m, cons3mn = cons3mn,
            cons41 = cons41, cons42 = cons42,
            skillGroups = skillGroups, cons41s = cons41s, cons42s = cons42s, cons3w = cons3w,
            manualPins = manualPins,
            extWishes = extWishes,
            extras = extras,
        )
    }

    /** 保存経路の選択（exportJson の本体）。構造編集＝全体を書き出す／制約編集＝制約を上書き／それ以外＝元のファイルへ勤務表と手動固定だけ。 */
    fun exportCurrent(originalJson: String?, state: MagiState?, schedule: Array<IntArray>, structureEdited: Boolean, constraintsEdited: Boolean): String? {
        if (structureEdited && state != null) return serialize(state, schedule)
        val orig = originalJson ?: return null
        return if (constraintsEdited && state != null) exportWithEdits(orig, state, schedule)
        else exportWithSchedule(orig, schedule, state?.manualPins, state?.staff)
    }

    /**
     * Re-emit the state with [newSchedule] substituted. We reparse the original text
     * so every field (including ones this app does not model) is preserved exactly,
     * then overwrite only "schedule".
     */
    fun exportWithSchedule(originalJson: String, newSchedule: Array<IntArray>, manualPins: List<ManualPin>? = null, staff: List<Staff>? = null): String {
        val o = JSONObject(originalJson)
        if (staff != null) putStaffIds(o, staff)
        // 最適化後 schedule と食い違う古い派生・キャッシュ系フィールドを除去（互換性事故防止）。
        for (k in DERIVED_KEYS) {
            o.remove(k)
        }
        val arr = JSONArray()
        for (row in newSchedule) {
            val r = JSONArray()
            for (v in row) r.put(v)
            arr.put(r)
        }
        o.put("schedule", arr)
        // 手動固定は構造・制約の編集フラグを立てずに付け外しできる＝元のファイルの値でなく今の値を書く（元に無く今も無ければキーを足さない）。
        if (manualPins != null && (manualPins.isNotEmpty() || o.has("manualPins"))) {
            o.put("manualPins", consArr(manualPins) { JSONObject().put("staff", it.staff).put("day", it.day).put("shift", it.shift) })
        }
        return o.toString(2)
    }

    /**
     * Like [exportWithSchedule] but also overwrites the 8 constraint arrays from [state]
     * (used after ws3-5 constraint editing). All other top-level fields — including
     * groupShiftApt and any unmodelled keys — are preserved via the original-JSON round-trip.
     */
    fun exportWithEdits(originalJson: String, state: MagiState, newSchedule: Array<IntArray>): String {
        val o = JSONObject(originalJson)
        for (k in DERIVED_KEYS) o.remove(k)
        putStaffIds(o, state.staff)
        val sched = JSONArray()
        for (row in newSchedule) { val r = JSONArray(); for (v in row) r.put(v); sched.put(r) }
        o.put("schedule", sched)
        o.put("cons1", consArr(state.cons1) { obj("day1" to it.day1, "shiftKigou" to it.shiftKigou, "day2" to it.day2) })
        o.put("cons2", consArr(state.cons2) { obj("shiftKigou" to it.shiftKigou, "count" to it.count) })
        o.put("cons3", consArr(state.cons3) { patternObj(it.pattern) })
        o.put("cons3n", consArr(state.cons3n) { patternObj(it.pattern) })
        o.put("cons3m", consArr(state.cons3m) { patternObj(it.pattern) })
        o.put("cons3mn", consArr(state.cons3mn) { patternObj(it.pattern) })
        o.put("cons41", consArr(state.cons41) { obj("groupKigou" to it.groupKigou, "shiftKigou" to it.shiftKigou, "l" to it.l, "u" to it.u) })
        o.put("cons42", consArr(state.cons42) { obj("g1Kigou" to it.g1Kigou, "g2Kigou" to it.g2Kigou, "s1Kigou" to it.s1Kigou, "s2Kigou" to it.s2Kigou) })
        // [監査A1] スキル制約も書き出す（従来は旧8族のみで、cons41s/42s の追加・削除・変更が
        //   このエクスポート経路(constraintsEditedのみ)から無言で欠落していた）。
        o.put("cons41s", consArr(state.cons41s) { obj("groupKigou" to it.groupKigou, "shiftKigou" to it.shiftKigou, "l" to it.l, "u" to it.u) })
        o.put("cons42s", consArr(state.cons42s) { obj("g1Kigou" to it.g1Kigou, "g2Kigou" to it.g2Kigou, "s1Kigou" to it.s1Kigou, "s2Kigou" to it.s2Kigou) })
        o.put("cons3w", consArr(state.cons3w) { obj("wishKigou" to it.wishKigou, "prevKigou" to it.prevKigou) })
        o.put("manualPins", consArr(state.manualPins) { JSONObject().put("staff", it.staff).put("day", it.day).put("shift", it.shift) })
        if (state.extWishes.isNotEmpty() || o.has("extWishes")) o.put("extWishes", consArr(state.extWishes) { JSONObject().put("staff", it.staff).put("days", JSONArray(it.days)).put("shifts", JSONArray(it.shifts)) })
        return o.toString(2)
    }

    /** 元のファイルの職員へ読込時に振った ID を書く（構造を編集していない経路＝並びと人数は元のまま。食い違えば触らない）。 */
    private fun putStaffIds(o: JSONObject, staff: List<Staff>) {
        val arr = o.optJSONArray("staff") ?: return
        if (arr.length() != staff.size) return
        for (i in staff.indices) {
            val so = arr.optJSONObject(i) ?: continue
            if (staff[i].id > 0) so.put("id", staff[i].id)
        }
    }

    private fun <T> consArr(list: List<T>, f: (T) -> JSONObject): JSONArray {
        val a = JSONArray(); for (e in list) a.put(f(e)); return a
    }

    private fun obj(vararg pairs: Pair<String, String>): JSONObject {
        val o = JSONObject(); for ((k, v) in pairs) o.put(k, v); return o
    }

    private fun patternObj(pattern: List<String>): JSONObject {
        val o = JSONObject(); val p = JSONArray(); for (s in pattern) p.put(s); o.put("pattern", p); return o
    }

    /**
     * Full serialization of a [MagiState] (used after ws1 initial-setup edits that change
     * dimensions, where the original-JSON round-trip is no longer valid). Writes every modelled
     * field plus any [MagiState.extras] verbatim, using the exact key names [parse] reads, so
     * serialize -> parse round-trips. [schedule] overrides state.schedule (the working table).
     */
    fun serialize(state: MagiState, schedule: Array<IntArray>): String {
        val o = JSONObject()
        o.put("startDate", state.startDate)
        o.put("endDate", state.endDate)
        o.put("use2Patterns", state.use2Patterns)
        o.put("shifts", consArr(state.shifts) {
            obj("name" to it.name, "kigou" to it.kigou, "need1" to it.need1, "need2" to it.need2,
                "role" to (if (it.role == ShiftRole.Rest) ROLE_REST else ROLE_NONE))
        })
        o.put("groups", consArr(state.groups) { obj("name" to it.name, "kigou" to it.kigou) })
        val staffArr = JSONArray()
        for (s in state.staff) { val so = JSONObject(); if (s.id > 0) so.put("id", s.id); so.put("name", s.name); so.put("groupIdx", s.groupIdx); so.put("skillIdx", s.skillIdx); staffArr.put(so) }
        o.put("staff", staffArr)
        o.put("skillGroups", consArr(state.skillGroups) { obj("name" to it.name, "kigou" to it.kigou) })
        o.put("groupShift", intGrid(state.groupShift))
        o.put("groupShiftApt", strGrid(state.groupShiftApt))
        val sched = JSONArray()
        for (row in schedule) { val r = JSONArray(); for (v in row) r.put(v); sched.put(r) }
        o.put("schedule", sched)
        val wishes = JSONObject(); for ((k, v) in state.wishes) wishes.put(k, v); o.put("wishes", wishes)
        val sr = JSONObject()
        for ((k, v) in state.staffRange) { val ro = JSONObject(); ro.put("lo", v.lo); ro.put("hi", v.hi); sr.put(k, ro) }
        o.put("staffRange", sr)
        o.put("needDay1", strKeyMap(state.needDay1))
        o.put("needDay2", strKeyMap(state.needDay2))
        o.put("shiftColors", strKeyMap(state.shiftColors))
        o.put("cons1", consArr(state.cons1) { obj("day1" to it.day1, "shiftKigou" to it.shiftKigou, "day2" to it.day2) })
        o.put("cons2", consArr(state.cons2) { obj("shiftKigou" to it.shiftKigou, "count" to it.count) })
        o.put("cons3", consArr(state.cons3) { patternObj(it.pattern) })
        o.put("cons3n", consArr(state.cons3n) { patternObj(it.pattern) })
        o.put("cons3m", consArr(state.cons3m) { patternObj(it.pattern) })
        o.put("cons3mn", consArr(state.cons3mn) { patternObj(it.pattern) })
        o.put("cons41", consArr(state.cons41) { obj("groupKigou" to it.groupKigou, "shiftKigou" to it.shiftKigou, "l" to it.l, "u" to it.u) })
        o.put("cons42", consArr(state.cons42) { obj("g1Kigou" to it.g1Kigou, "g2Kigou" to it.g2Kigou, "s1Kigou" to it.s1Kigou, "s2Kigou" to it.s2Kigou) })
        o.put("cons41s", consArr(state.cons41s) { obj("groupKigou" to it.groupKigou, "shiftKigou" to it.shiftKigou, "l" to it.l, "u" to it.u) })
        o.put("cons42s", consArr(state.cons42s) { obj("g1Kigou" to it.g1Kigou, "g2Kigou" to it.g2Kigou, "s1Kigou" to it.s1Kigou, "s2Kigou" to it.s2Kigou) })
        o.put("cons3w", consArr(state.cons3w) { obj("wishKigou" to it.wishKigou, "prevKigou" to it.prevKigou) })
        o.put("manualPins", consArr(state.manualPins) { JSONObject().put("staff", it.staff).put("day", it.day).put("shift", it.shift) })
        if (state.extWishes.isNotEmpty() || o.has("extWishes")) o.put("extWishes", consArr(state.extWishes) { JSONObject().put("staff", it.staff).put("days", JSONArray(it.days)).put("shifts", JSONArray(it.shifts)) })
        // 古い評価情報（違反の印・前回の結果）は勤務表と食い違うので、全体保存でも書き戻さない（他の保存経路と同じ）。
        for ((k, v) in state.extras) if (!o.has(k) && k !in DERIVED_KEYS) o.put(k, v)
        return o.toString(2)
    }

    private fun intGrid(grid: List<List<Int>>): JSONArray {
        val a = JSONArray(); for (row in grid) { val r = JSONArray(); for (v in row) r.put(v); a.put(r) }; return a
    }

    private fun strGrid(grid: List<List<String>>): JSONArray {
        val a = JSONArray(); for (row in grid) { val r = JSONArray(); for (v in row) r.put(v); a.put(r) }; return a
    }

    private fun strKeyMap(m: Map<String, String>): JSONObject {
        val o = JSONObject(); for ((k, v) in m) o.put(k, v); return o
    }

    // ---- helpers -------------------------------------------------------------

    private fun asStr(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** 条件の配列。無ければ空、配列でなければ読み込みを止める（空として取り込むと条件が黙って消える）。 */
    private fun arrayOf(o: JSONObject, key: String, name: String = key): JSONArray? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optJSONArray(key) ?: throw IllegalArgumentException("$name が配列ではありません（データが壊れています）")
    }

    /** 条件の表（キー→値）。無ければ null、オブジェクトでなければ読み込みを止める（空として取り込むと条件が黙って消える）。 */
    private fun objOf(o: JSONObject, key: String): JSONObject? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optJSONObject(key) ?: throw IllegalArgumentException("$key がオブジェクトではありません（データが壊れています）")
    }

    /** 整数の値。無い・null・空文字は [def]。整数でない数（1.9）・数字でない文字・範囲外は丸めず読み込みを止める。 */
    private fun intOf(v: Any?, def: Int, name: String): Int {
        val n: Long? = when (v) {
            null, JSONObject.NULL -> return def
            is Int -> v.toLong()
            is Long -> v
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong() else null
            is Float -> if (v == Math.floor(v.toDouble()).toFloat() && !v.isInfinite()) v.toLong() else null
            is java.math.BigDecimal -> runCatching { v.longValueExact() }.getOrNull()
            is java.math.BigInteger -> runCatching { v.longValueExact() }.getOrNull()
            is String -> if (v.isBlank()) return def else v.trim().toLongOrNull()
            else -> null
        }
        if (n == null || n < Int.MIN_VALUE || n > Int.MAX_VALUE)
            throw IllegalArgumentException("$name が整数ではありません（値 $v、データが壊れています）")
        return n.toInt()
    }

    /** 並びの pattern。無ければ空、配列でなければ読み込みを止める（空として取り込むと条件が黙って消える）。 */
    private fun patternOf(row: JSONObject, name: String): List<String> {
        if (!row.has("pattern") || row.isNull("pattern")) return emptyList()
        val a = row.optJSONArray("pattern") ?: throw IllegalArgumentException("$name の pattern が配列ではありません（データが壊れています）")
        return strList(a)
    }

    private fun strList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { asStr(a.opt(it)) }
    }

    private fun strMap(o: JSONObject?, name: String): Map<String, String> {
        if (o == null) return emptyMap()
        val m = HashMap<String, String>()
        o.keys().forEach { k ->
            val v = o.opt(k)
            if (v is JSONObject || v is JSONArray) throw IllegalArgumentException("$name[\"$k\"] が値ではありません（データが壊れています）")
            m[k] = asStr(v)
        }
        return m
    }

    // [外部レビュー P2-02] 旧: 要素がオブジェクト/配列でなければ黙って読み飛ばしていた（null・数値・文字列の
    //   混入で staff/schedule 等が本来より短いリストへ静かに変わる）。インポート・復元データは職員名や
    //   勤務割当を含むため、読み飛ばしより「配列N番目が不正」という明示的な失敗のほうが安全。
    //   throw は3呼出元（MagiViewModel×2・OptimizationWorker×1）すべてが runCatching/try-catch で
    //   包んでいる（確認済み）ので、ここで投げても未捕捉クラッシュにはならない。
    private inline fun <T> JSONArray?.mapIndexedObjects(name: String, f: (Int, JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i)
                ?: throw IllegalArgumentException("$name[$i] がオブジェクトではありません（データが壊れています）")
            out.add(f(i, o))
        }
        return out
    }

    private inline fun <T> JSONArray?.mapObjects(name: String, f: (JSONObject) -> T): List<T> = mapIndexedObjects(name) { _, o -> f(o) }

    private inline fun <T> JSONArray?.mapArrays(name: String, f: (JSONArray) -> T): List<T> = mapIndexedArrays(name) { _, a -> f(a) }

    private inline fun <T> JSONArray?.mapIndexedArrays(name: String, f: (Int, JSONArray) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val a = optJSONArray(i)
                ?: throw IllegalArgumentException("$name[$i] が配列ではありません（データが壊れています）")
            out.add(f(i, a))
        }
        return out
    }
}
