package probe.slide

import com.magi.app.model.GapRow
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

// 休日スライドの増大路（A→B…→C）の「採れる手」を数えるだけ（盤面は変えない）。
// 辺 u -(d,s)-> v: 日 d で u の勤務 s を休に、v の休を s に（その日の人数は不変）。
// A: 日 X の勤務を手放す。中継 B: X で引き受け Y で手放す（回数不変）。C: 最後の日で引き受ける。
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val resDir = args[0]; val secs = args[1].toInt(); val seeds = args[2].split(',').map { it.toInt() }
    val gapRules = args[3].takeIf { it != "-" }?.split(';')?.map { val (k, n) = it.split(':'); GapRow(k, n) } ?: emptyList()
    val fixtures = args[4].split(','); val maxHops = args.getOrNull(5)?.toInt() ?: 2; val reach = 14
    for (fx in fixtures) for (seed in seeds) {
        val st = StateParser.parse(File(resDir, "$fx.json").readText())!!.let { if (gapRules.isEmpty()) it else it.copy(consGap = gapRules) }
        val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, seed = 1000L + seed) }
        val p = Problem(st); val rest = restShiftIndex(st) ?: continue
        val w = normalizeSchedule(res.schedule, p)
        val base = UnifiedViolationChecker.check(st, w)
        val screen = DeltaScreen(p)
        var evaluated = 0L; var better = 0; var bestGain = 0.0; var bestDesc = ""
        val t0 = System.currentTimeMillis()
        fun ok(i: Int, j: Int, k: Int) = !p.wishLocked(i, j) && p.mayPlaceAt(i, j, k)
        // パス（辺の列）を当てて評価し戻す。
        fun tryPath(edges: List<IntArray>) {   // [from, to, day, shift]
            for (e in edges) { w[e[0]][e[2]] = rest; w[e[1]][e[2]] = e[3] }
            var clean = true
            for (e in edges) if (p.makesForbiddenRun(w, e[0], e[2], rest) || p.makesForbiddenRun(w, e[1], e[2], e[3])) { clean = false; break }
            if (clean) {
                evaluated++
                val r = screen.checkIfBetter(st, w, base)
                if (r != null) { better++; val g = base.weightedScore - r.weightedScore
                    if (g > bestGain) { bestGain = g; bestDesc = edges.joinToString(" ") { "${it[0]}→${it[1]}@${it[2]}" } + " " +
                        r.breakdown.filter { (k, v) -> v != (base.breakdown[k] ?: 0) }.entries.joinToString(" ") { "${it.key}:${base.breakdown[it.key] ?: 0}->${it.value}" } } }
            }
            for (e in edges.asReversed()) { w[e[1]][e[2]] = rest; w[e[0]][e[2]] = e[3] }
        }
        fun dfs(prev: Int, day: Int, shift: Int, depth: Int, used: MutableSet<Int>, usedDays: MutableSet<Int>, path: MutableList<IntArray>) {
            // C で閉じる
            if (depth >= 1) for (c in 0 until p.S) {
                if (c in used || w[c][day] != rest || !ok(c, day, shift)) continue
                path.add(intArrayOf(prev, c, day, shift)); tryPath(path); path.removeAt(path.lastIndex)
            }
            if (depth + 1 >= maxHops) return
            for (b in 0 until p.S) {
                if (b in used || w[b][day] != rest || !ok(b, day, shift)) continue
                for (y in maxOf(0, day - reach)..minOf(p.T - 1, day + reach)) {
                    if (y in usedDays) continue
                    val sy = w[b][y]
                    if (sy == rest || p.wishLocked(b, y) || !p.mayPlaceAt(b, y, rest)) continue
                    used.add(b); usedDays.add(y); path.add(intArrayOf(prev, b, day, shift))
                    dfs(b, y, sy, depth + 1, used, usedDays, path)
                    path.removeAt(path.lastIndex); usedDays.remove(y); used.remove(b)
                }
            }
        }
        for (a in 0 until p.S) for (x in 0 until p.T) {
            val sx = w[a][x]
            if (sx == rest || p.wishLocked(a, x) || !p.mayPlaceAt(a, x, rest)) continue
            dfs(a, x, sx, 0, mutableSetOf(a), mutableSetOf(x), ArrayList())
        }
        println("$fx seed=$seed hops=$maxHops hard=${base.hard} w=${base.weightedScore.toLong()} 評価=$evaluated 採れる手=$better 最大の改善=${bestGain.toLong()} ${(System.currentTimeMillis() - t0) / 1000}s  $bestDesc")
    }
}
