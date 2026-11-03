// [2026-11-01] 膜の探索の試作（tools/loop/membrane_cpsat.py）が使う正式評価の窓口。状態 JSON を 1 回読み、
// 標準入力の 1 行ごとの盤面（JSON の 2 次元配列）を UnifiedViolationChecker に通して、族ごとの件数と重みを 1 行の JSON で返す。
package probe.checkserver

import com.magi.app.model.StateParser
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.UnifiedViolationChecker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    val st = StateParser.parse(File(args[0]).readText())!!
    val out = System.out.bufferedWriter()
    while (true) {
        val line = readLine() ?: break
        if (line.isBlank()) continue
        val a = JSONArray(line)
        val s = Array(a.length()) { i -> val r = a.getJSONArray(i); IntArray(r.length()) { r.getInt(it) } }
        val rep = UnifiedViolationChecker.check(st, s)
        val o = JSONObject()
        o.put("hard", rep.hard); o.put("total", rep.total)
        o.put("weighted", rep.breakdown.entries.sumOf { (k, v) -> MirrorKeys.weightOf(k) * v }.toLong())
        o.put("bd", JSONObject(MirrorKeys.all.associateWith { rep.breakdown[it] ?: 0 }))
        val per = JSONObject()
        for ((fam, locs) in rep.distLocations) per.put(fam, JSONArray(locs.map { JSONArray(it) }))
        o.put("dist", per)
        out.write(o.toString()); out.newLine(); out.flush()
    }
}
