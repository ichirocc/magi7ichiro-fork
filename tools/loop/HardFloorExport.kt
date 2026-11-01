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
    if (which.startsWith("file:")) File(which.removePrefix("file:")).let { listOf(it.nameWithoutExtension to StateParser.parse(it.readText())) }
    else if (which == "fixtures") listOf("blocked_covu_state.json", "sample_state_v6.json", "oct2026_grid_state.json").map { f ->
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
            o.put("allowedAt", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.T).map { j ->
                if (p.wishLocked(i, j)) JSONArray(listOf(p.lockTo(i, j))) else JSONArray((0 until p.K).filter { k -> p.mayPlaceAt(i, j, k) })
            }) }))
            o.put("lo", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.K).map { k -> p.rangeLo[i][k].let { if (it == Int.MIN_VALUE || it == 0 || !p.canDo(i, k)) -1 else it } }) }))
            o.put("hi", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.K).map { k -> p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) -1 else it } }) }))
            fun c3s(l: List<C3>) = JSONArray(l.filter { it.seq.isNotEmpty() && it.seq.size <= p.T }.map { JSONArray(it.seq.toList()) })
            o.put("c3", c3s(p.cons3)); o.put("c3m", c3s(p.cons3m)); o.put("c3mn", c3s(p.cons3mn))
            o.put("c2", JSONArray(p.cons2.map { JSONArray(listOf(it.shiftIdx, it.count)) }))
            o.put("c41", JSONArray(p.cons41.map { JSONArray(listOf(it.groupIdx, it.shiftIdx, it.l, it.u)) }))
            o.put("c41s", JSONArray(p.cons41s.map { JSONArray(listOf(it.groupIdx, it.shiftIdx, it.l, it.u)) }))
            o.put("c42", JSONArray(p.cons42.map { JSONArray(listOf(it.g1, it.s1, it.g2, it.s2)) }))
            o.put("c42s", JSONArray(p.cons42s.map { JSONArray(listOf(it.g1, it.s1, it.g2, it.s2)) }))
            o.put("G", p.G); o.put("sgrp", JSONArray(p.sgrp.toList())); o.put("ssk", JSONArray(p.ssk.toList()))
            o.put("nSkill", (p.cons41s.map { it.groupIdx + 1 } + p.cons42s.flatMap { listOf(it.g1 + 1, it.g2 + 1) } + listOf(0)).max())
            o.put("covO", JSONArray((0 until p.K).map { k -> JSONArray((0 until p.T).map { j -> JSONArray((0..p.S).map { g -> p.covOCell(k, j, g) }) }) }))
            o.put("apt", JSONArray((0 until p.S).map { i -> JSONArray(p.apt[i].toList()) }))
            o.put("dow0", p.dow0)
            o.put("mayPlace", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.K).map { k -> p.mayPlace(i, k) }) }))
            o.put("rLo", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.K).map { k -> p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) JSONObject.NULL else it } }) }))
            o.put("rHi", JSONArray((0 until p.S).map { i -> JSONArray((0 until p.K).map { k -> p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) JSONObject.NULL else it } }) }))
            o.put("groupMembers", JSONArray((0 until p.G).map { g -> JSONArray(p.groupMembers[g].toList()) }))
            o.put("bucket", JSONArray((0 until p.G).map { g -> JSONArray(p.bucket[g].toList()) }))
            o.put("weights", JSONObject(MirrorKeys.all.associateWith { MirrorKeys.weightOf(it) }))
            o.put("c1", JSONArray(p.cons1.filter { it.day1 <= p.T }.map { JSONArray(listOf(it.day1, it.shiftIdx, it.day2)) }))
            o.put("board", JSONArray(st.schedule.map { JSONArray(it) }))
            File(outDir, "$name.model.json").writeText(o.toString())
        } else {
            for (tag in listOf("mayPlace", "canDo", "c1", "c1lex")) {
                val f = File(outDir, "$name.$tag.sol.json"); if (!f.exists()) continue
                val a = JSONArray(f.readText())
                val s = Array(a.length()) { i -> val r = a.getJSONArray(i); IntArray(r.length()) { r.getInt(it) } }
                val rep = UnifiedViolationChecker.check(st, s)
                println("$name $tag checker hard=${rep.hard} total=${rep.total} weighted=${rep.breakdown.entries.sumOf { (k, v) -> MirrorKeys.weightOf(k) * v }.toLong()} " +
                    MirrorKeys.all.associateWith { rep.breakdown[it] ?: 0 }.filterValues { it != 0 })
            }
        }
    }
}
