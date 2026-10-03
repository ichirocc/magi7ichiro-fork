package probe.hardfloor
// [2026-10-03] 最小 HARD を CP-SAT（tools/loop/hard_floor_cpsat.py）で厳密に解くための書き出しと、解の checker 照合。
// 置ける範囲は最適化器と同じ（希望固定・手動固定は lockTo、それ以外は placeable＝mayPlace）。canDo 版も併記する
// （上限 0 を外したら下がるか＝「上限 0 で置けない」の量）。HARD の正しさは解を UnifiedViolationChecker に通して確かめる。
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun cases(resDir: String, which: String): List<Pair<String, MagiState>> =
    if (which == "fixtures") listOf("blocked_covu_state.json", "sample_state_v6.json", "oct2026_grid_state.json").map { f ->
        f.removeSuffix(".json").removeSuffix("_state").removeSuffix("_state_v6") to StateParser.parse(File(resDir, f).readText())
    } else probe.Cases.specs.filter { it.id.contains(which.removePrefix("synth:")) }.map { it.id to probe.Cases.build(it) }

fun main(args: Array<String>) {
    val resDir = args[0]; val outDir = File(args[1]).apply { mkdirs() }; val mode = args[2]; val which = args[3]
    for ((name, st) in cases(resDir, which)) {
        val p = cachedProblem(st)
        if (mode == "export") {
            val o = JSONObject()
            o.put("S", p.S); o.put("T", p.T); o.put("K", p.K)
            fun cells(useCanDo: Boolean) = JSONArray((0 until p.S).map { i -> JSONArray((0 until p.T).map { j ->
                if (p.wishLocked(i, j)) JSONArray(listOf(p.lockTo(i, j)))
                else JSONArray((0 until p.K).filter { k -> if (useCanDo) p.canDo(i, k) else p.mayPlace(i, k) })
            }) })
            o.put("allowed", cells(false)); o.put("allowedCanDo", cells(true))
            o.put("covU", JSONArray((0 until p.K).map { k -> JSONArray((0 until p.T).map { j -> JSONArray((0..p.S).map { g -> p.covUCell(k, j, g) }) }) }))
            o.put("c3n", JSONArray(p.cons3n.filter { it.seq.isNotEmpty() && it.seq.size <= p.T }.map { JSONArray(it.seq.toList()) }))
            o.put("c3wBan", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.T).map { j -> JSONArray((0 until p.K).filter { k -> p.c3wBanned(i, j, k) }) }) }))
            o.put("canDo", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.K).map { k -> p.canDo(i, k) }) }))
            o.put("wish", JSONArray((0 until p.S).map { i -> JSONArray(p.wish[i].toList()) }))
            File(outDir, "$name.model.json").writeText(o.toString())
        } else {
            for (tag in listOf("mayPlace", "canDo")) {
                val f = File(outDir, "$name.$tag.sol.json"); if (!f.exists()) continue
                val a = JSONArray(f.readText())
                val s = Array(a.length()) { i -> val r = a.getJSONArray(i); IntArray(r.length()) { r.getInt(it) } }
                val rep = UnifiedViolationChecker.check(st, s)
                println("$name $tag checker hard=${rep.hard} " + MirrorKeys.hard.associateWith { rep.breakdown[it] ?: 0 })
            }
        }
    }
}
