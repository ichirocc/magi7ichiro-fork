package probe.outerrelink

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

// シーソー S3 の外側の歩き（`docs/seesaw_detail.md` §6.3、PROPOSAL）の §9 の 4（測定だけ）。製品の最適化（アプリと同じく SeesawRelink ON）の
// 本命 P と他の案 Q を取り、内側の歩き（今の SeesawRelink.apply）と外側の歩き（P と Q が同じ値の 2 人を同じ日に入れ替えて Q から遠ざかる、
// 必須が増える手は戻す）で、本命を良くした回数と交換の候補（isTradeoff）の数を比べる。
// args: resDir fixtures(csv) secs seed
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    PolishGate.seesawRelink = true
    val resDir = File(args[0]); val secs = args[2].toInt(); val seed = args[3].toLong()
    for (fx in args[1].split(',')) {
        val st = StateParser.parse(File(resDir, "$fx.json").readText())!!
        val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, softPolish = true, seed = seed) }
        val p0 = res.schedule
        val alts = res.alternatives
        val inner = SeesawRelink.apply(st, p0, alts, maxMillis = 30_000L)
        val o = outer(st, p0, alts)
        println("OUTER $fx 案=${alts.size} 重み=${UnifiedViolationChecker.check(st, p0).weightedScore.toLong()} | 内側: 更新=${inner.applied} 候補=${inner.tradeoffs.size} 重み→${inner.report.weightedScore.toLong()}" +
            " | 外側: 手=${o.steps} 更新=${o.applied} 候補(集めた)=${o.pool} 重み→${o.bestW.toLong()} ${o.ms}ms")
    }
}

private class Out(val steps: Int, val applied: Int, val pool: Int, val bestW: Double, val ms: Long)

private fun outer(st: com.magi.app.model.MagiState, p0: Array<IntArray>, alts: List<Array<IntArray>>): Out {
    val p = Problem(st)
    val t0 = System.currentTimeMillis()
    var best = p0.copy2D(); var bestRep = UnifiedViolationChecker.check(st, best)
    var steps = 0; var applied = 0; var pool = 0
    fun ok(i: Int, j: Int, k: Int) = !p.wishLocked(i, j) && p.mayPlaceAt(i, j, k)
    for (q in alts) {
        if (q.size != best.size) continue
        val cur = best.copy2D(); var curRep = bestRep
        val vcells = curRep.violations.keys.mapNotNull { k -> k.split(',').let { s -> s.getOrNull(0)?.toIntOrNull()?.let { i -> s.getOrNull(1)?.toIntOrNull()?.let { j -> i to j } } } }.toSet()
        val cells = ArrayList<Pair<Int, Int>>()
        for (i in 0 until p.S) for (j in 0 until p.T) if (cur[i][j] == q[i][j]) cells.add(i to j)
        cells.sortBy { if (it in vcells) 0 else 1 }
        val budget = (0 until p.S).sumOf { i -> (0 until p.T).count { j -> best[i][j] != q[i][j] } }.coerceAtLeast(1)
        var walked = 0
        for ((i, j) in cells) {
            if (walked >= budget) break
            if (cur[i][j] != q[i][j]) continue
            val b = (0 until p.S).firstOrNull { b -> b != i && cur[b][j] == q[b][j] && cur[b][j] != cur[i][j] && ok(i, j, cur[b][j]) && ok(b, j, cur[i][j]) } ?: continue
            val t = cur[i][j]; cur[i][j] = cur[b][j]; cur[b][j] = t
            val r = UnifiedViolationChecker.check(st, cur)
            if (r.hard > curRep.hard) { cur[b][j] = cur[i][j]; cur[i][j] = t; continue }
            curRep = r; walked++; steps++
            if (betterReport(r, bestRep)) { best = cur.copy2D(); bestRep = r; applied++ }
            else if (SeesawRelink.isTradeoff(bestRep, r)) pool++
        }
    }
    return Out(steps, applied, pool, bestRep.weightedScore, System.currentTimeMillis() - t0)
}
