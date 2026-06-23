package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Group
import com.magi.app.model.Staff
import java.time.LocalDate

/**
 * 病院などで広く使われる「勤務表テンプレCSV」(CP932/Excel由来) を、完全な [MagiState] として取り込む。
 *
 * 添付サンプル (令和8年7月) の構成:
 *  - 先頭: 年月タイトル（例「令和8年 7月」）
 *  - ユニット(=グループ)ごとのブロック:
 *      行: 「ユニット名：,,<ユニット名>,,1,2,…,31,…」（日番号）
 *      行: 「№,,氏 名,,水,木,金,…」（曜日）
 *      行: 「<№>,<役割>,<氏名>,予定,<31日分のシフト記号>,…,<シフト別集計>」
 *  - 凡例ブロック:
 *      行: 「,記号,時刻/時間,休憩時間,<曜日…>」
 *      行: 「,<記号>,<時刻範囲 or 説明>,<休憩>,<日別の必要人数 31列>」
 *
 * 列位置: 氏名=idx2 / シフト記号は idx4 から T 列 / 凡例は 記号=idx1, 時刻=idx2, 必要数=idx4 から。
 * 空セルは「休」に割り当てる（＝勤務指定の無い日＝公休扱い）。担当可否情報は無いため groupShift は
 * 全シフト可(permissive)で取り込み、利用者が後から調整できるようにする。
 */
object RosterCsvImport {
    private const val REST = "休"

    /** このテキストが勤務表テンプレ形式かを軽量判定。 */
    fun detect(text: String): Boolean {
        if (text.contains("ユニット名")) return true
        // 「氏 名」見出し＋時刻範囲(例 8:30～17:30 / 8：30～17：30)の両方があればテンプレとみなす。
        return text.contains("氏 名") && Regex("\\d{1,2}[:：]\\d{2}\\s*[~～]").containsMatchIn(text)
    }

    /**
     * @param asWishes false=本表セルを「勤務表(初期割り当て)」として取り込む（既定）。
     *   true=本表セルを「希望シフト」として取り込む：埋まっているセルは wishes["i,j"]=記号 に、勤務表は
     *   全て公休で開始する（最適化で希望を尊重しつつ必要数を満たす）。空セルは希望なし（自由）。
     *   ※元表の明示「休」セルは希望休として wishes に入り、空セル（通常の休み）と区別される。
     */
    fun parse(text: String, asWishes: Boolean = false): MagiState? {
        val rows = parseCsvRows(text)
        if (rows.isEmpty()) return null
        fun cell(r: List<String>, idx: Int): String = r.getOrElse(idx) { "" }.trim()
        fun normName(s: String): String = s.replace('　', ' ').trim().replace(Regex("\\s+"), " ")

        // --- 列レイアウト（テンプレ固定。Excel列名→0始まり列番号）---
        //   グループ名 = C列(=2)の各ユニット見出し（例 C2=柳・C13=桐）。氏名 = C列(=2)。
        //   勤務記号 = E列(=4)から右へ T 日分（最大 AI列=34、31日）。シフト記号 = 凡例の B列(=1, 行25〜40)。
        //   スタッフ行は各ユニット見出しの2行下から、空行/凡例/次ユニットの手前まで
        //   （添付サンプルでは 4〜11 行目＝柳・15〜22 行目＝桐。空欄№は自動スキップ）。
        //   ※必要人数(need1/need2)はこのCSVに存在しない。凡例の日別数値は現在表の人数集計(タリー)であり
        //     必要数ではない（休/有の人数も含む）ため、需要としては取り込まない。
        val nameCol = 2           // C
        val dayCol0 = 4           // E
        val maxDayCol = 34        // AI（E..AI = 31日）

        // --- 日数 T: 最初のユニット見出しの日番号(1,2,3,…)の連続から求める（E列〜AI列で頭打ち） ---
        val unitHeaders = rows.indices.filter { cell(rows[it], 0).startsWith("ユニット名") }
        if (unitHeaders.isEmpty()) return null
        val uh0 = rows[unitHeaders.first()]
        var T = 0
        while (dayCol0 + T <= maxDayCol && cell(uh0, dayCol0 + T).toIntOrNull() == T + 1) T++
        if (T < 1) return null

        // --- 凡例(B列25〜40): シフト記号＋時刻表記。必要人数は無い（need1/need2は空）。 ---
        val legendHeader = rows.indexOfFirst { cell(it, 1) == "記号" && (cell(it, 2) == "時刻" || cell(it, 2) == "時間") }
        val shiftsOut = ArrayList<Shift>()
        val symToK = LinkedHashMap<String, Int>()
        if (legendHeader >= 0) {
            var r = legendHeader + 1
            while (r < rows.size) {
                val row = rows[r]
                val sym = cell(row, 1)            // B列＝シフト記号
                if (sym.isEmpty()) break          // 凡例の終端（合計行「Ａ～Ｃ」等）
                if (!symToK.containsKey(sym)) {
                    symToK[sym] = shiftsOut.size
                    val desc = cell(row, 2)       // C列＝時刻/説明（表示名に使用）
                    shiftsOut.add(Shift(name = desc.ifEmpty { sym }, kigou = sym, need1 = "", need2 = ""))
                }
                r++
            }
        }
        // 休シフトは必須（解析・整列の基準）。凡例に無ければ補う。
        if (!symToK.containsKey(REST)) {
            symToK[REST] = shiftsOut.size
            shiftsOut.add(Shift(name = "公休", kigou = REST, need1 = "", need2 = ""))
        }
        if (shiftsOut.isEmpty()) return null
        val restK = symToK.getValue(REST)

        // --- ユニット(グループ)・スタッフ・勤務表グリッド ---
        val groupsOut = ArrayList<Group>()
        val staffOut = ArrayList<Staff>()
        val grid = ArrayList<IntArray>()
        val wishes = LinkedHashMap<String, Int>()
        for (uhIdx in unitHeaders) {
            val unitName = normName(cell(rows[uhIdx], nameCol)).ifEmpty { "G${groupsOut.size + 1}" }
            val g = groupsOut.size
            groupsOut.add(Group(name = unitName, kigou = unitName))
            var rr = uhIdx + 2   // ユニット見出し＋曜日見出しを飛ばす
            while (rr < rows.size) {
                val row = rows[rr]
                if (cell(row, 0).startsWith("ユニット名")) break
                if (cell(row, 1) == "記号") break       // 凡例に到達
                val isStaffRow = cell(row, 3) == "予定"
                if (isStaffRow) {
                    val name = normName(cell(row, nameCol))
                    if (name.isNotEmpty()) {
                        val i = staffOut.size
                        staffOut.add(Staff(name = name, groupIdx = g))
                        val days = IntArray(T) { restK }
                        for (j in 0 until T) {
                            val sym = cell(row, dayCol0 + j)
                            val k = if (sym.isEmpty()) null else symToK[sym]
                            if (k != null) {
                                days[j] = k
                                if (asWishes) wishes["$i,$j"] = k   // 埋まっているセル＝希望
                            }
                        }
                        // 希望取込時は勤務表を全公休で開始（最適化が希望を尊重して埋める）。
                        grid.add(if (asWishes) IntArray(T) { restK } else days)
                    }
                    rr++
                    continue
                }
                if (row.all { it.trim().isEmpty() }) break   // 空行＝ブロック終端
                rr++
            }
        }
        if (staffOut.isEmpty() || groupsOut.isEmpty()) return null

        // --- 期間: タイトル「令和N年 M月」から ---
        val title = cell(rows[0], 0)
        val reiwa = Regex("令和(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val yr = reiwa?.let { 2018 + it } ?: LocalDate.now().year
        val mo = rows[0].drop(1).mapNotNull { it.trim().toIntOrNull() }.firstOrNull { it in 1..12 } ?: 1
        val start = String.format("%04d-%02d-01", yr, mo)
        val end = runCatching { LocalDate.parse(start).plusDays((T - 1).toLong()).toString() }.getOrDefault(start)

        val K = shiftsOut.size
        return MagiState(
            startDate = start,
            endDate = end,
            shifts = shiftsOut,
            groups = groupsOut,
            staff = staffOut,
            use2Patterns = false,
            groupShift = List(groupsOut.size) { List(K) { 1 } },           // 担当可否不明→全可(後から調整)
            groupShiftApt = List(groupsOut.size) { List(K) { "" } },
            schedule = grid.map { it.toList() },
            wishes = wishes,
            staffRange = emptyMap(),
            needDay1 = emptyMap(),   // 必要人数はCSVに無い（凡例の日別数値は集計＝需要ではない）
            needDay2 = emptyMap(),
            cons1 = emptyList(),
            cons2 = emptyList(),
            cons3 = emptyList(),
            cons3n = emptyList(),
            cons3m = emptyList(),
            cons3mn = emptyList(),
            cons41 = emptyList(),
            cons42 = emptyList(),
        )
    }
}

object ScheduleCsvBridge {
    fun build(state: MagiState, schedule: Array<IntArray>): String {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        val out = StringBuilder()
        val header = ArrayList<String>()
        header.add("スタッフ \\ 日付")
        for (j in 0 until p.T) header.add(formatDay(state.startDate, j))
        appendCsvRow(out, header)

        for (i in 0 until p.S) {
            val line = ArrayList<String>()
            line.add(state.staff[i].name)
            for (j in 0 until p.T) {
                val k = s[i][j]
                val symbol = state.shifts.getOrNull(k)?.kigou ?: ""
                line.add(symbol)
            }
            appendCsvRow(out, line)
        }

        appendCsvRow(out, emptyList())
        val sumHeader = ArrayList<String>()
        sumHeader.add("集計")
        for (shift in state.shifts) sumHeader.add(shift.kigou)
        appendCsvRow(out, sumHeader)

        val counts = countMatrix(p, s)
        for (i in 0 until p.S) {
            val row = ArrayList<String>()
            row.add(state.staff[i].name)
            for (k in 0 until p.K) row.add(counts[i][k].toString())
            appendCsvRow(out, row)
        }
        return out.toString()
    }

    fun parse(text: String, state: MagiState, base: Array<IntArray>): ScheduleRunResult {
        val rows = parseCsvRows(text)
        val p = Problem(state)
        val schedule = normalizeSchedule(base, p)
        val nameToI = LinkedHashMap<String, Int>()
        for (i in state.staff.indices) {
            nameToI[state.staff[i].name.trim()] = i
        }
        val kigouToK = LinkedHashMap<String, Int>()
        for (k in state.shifts.indices) {
            kigouToK[state.shifts[k].kigou.trim()] = k
        }
        var matched = 0
        var rr = 1
        while (rr < rows.size) {
            val r = rows[rr]
            if (r.isNotEmpty() && r[0].trim().isNotEmpty()) {
                val staffIndex = nameToI[r[0].trim()]
                if (staffIndex != null) {
                    matched++
                    val last = minOf(p.T, r.size - 1)
                    var j = 0
                    while (j < last) {
                        val k = kigouToK[r[j + 1].trim()]
                        if (k != null) schedule[staffIndex][j] = k
                        j++
                    }
                }
            }
            rr++
        }
        val report = UnifiedViolationChecker.check(state, schedule)
        val log = MirrorLog(tag = "CSVImport", message = "CSV取込: staff一致 ${matched}行")
        val logs = ArrayList<MirrorLog>()
        logs.add(log)
        logs.addAll(report.logs)
        return ScheduleRunResult(schedule, report.copy(logs = logs), matched = matched)
    }
}

private fun appendCsvRow(out: StringBuilder, values: List<String>) {
    var idx = 0
    while (idx < values.size) {
        if (idx > 0) out.append(',')
        out.append(csvEscapeCell(values[idx]))
        idx++
    }
    out.append('\n')
}

private fun csvEscapeCell(value: String): String {
    var mustQuote = false
    for (ch in value) {
        if (ch == ',' || ch == '"' || ch == '\n' || ch == '\r') {
            mustQuote = true
            break
        }
    }
    val escaped = value.replace("\"", "\"\"")
    return if (mustQuote) "\"$escaped\"" else escaped
}

private fun parseCsvRows(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    val row = ArrayList<String>()
    val cell = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inQuote && c == '"' && i + 1 < text.length && text[i + 1] == '"') {
            cell.append('"')
            i++
        } else if (c == '"') {
            inQuote = !inQuote
        } else if (!inQuote && c == ',') {
            row.add(cell.toString())
            cell.setLength(0)
        } else if (!inQuote && (c == '\n' || c == '\r')) {
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            row.add(cell.toString())
            cell.setLength(0)
            rows.add(ArrayList(row))
            row.clear()
        } else {
            cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(ArrayList(row))
    }
    return rows
}
