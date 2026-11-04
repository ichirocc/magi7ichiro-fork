// [2026-11-04] weekly の取り逃しの測定（docs/seesaw.md §13.5）。頭打ちの盤面 x に、weekly の研磨（長方形交換）と膜の細胞 LNS を
// 単独で当てて、族ごとの件数の変化を書く。S2b（tools/loop/seesaw_s2b.py）が見つけた優越する盤面と比べ、どの部品がどこまで届くかを見る。
//   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 ... probe.weeklymiss.WeeklyMissProbeKt <state.json> <board.json>...
package probe.weeklymiss

import com.magi.app.model.StateParser
import com.magi.app.v6.CyclicSwapWeeklyPolish
import com.magi.app.v6.MembraneCellLns
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.DeltaEvaluator
import com.magi.app.v6.SCORE_HARD_UNIT
import com.magi.app.v6.betterReport
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.exactPinRegression
import com.magi.app.v6.wishLocked
import com.magi.app.model.MagiState
import org.json.JSONArray
import java.io.File

fun main(args: Array<String>) {
    val st = StateParser.parse(File(args[0]).readText())!!
    for (path in args.drop(1)) {
        val a = JSONArray(File(path).readText())
        val x = Array(a.length()) { i -> val r = a.getJSONArray(i); IntArray(r.length()) { r.getInt(it) } }
        val rx = UnifiedViolationChecker.check(st, x)
        fun diff(r: ViolationReport) = MirrorKeys.all.mapNotNull { f -> val d = (r.breakdown[f] ?: 0) - (rx.breakdown[f] ?: 0); if (d != 0) "$f${if (d > 0) "+" else ""}$d" else null }.joinToString(" ")
        fun line(tag: String, s: Array<IntArray>, extra: String) {
            val r = UnifiedViolationChecker.check(st, s)
            println("${File(path).name} $tag: 必須 ${rx.hard}->${r.hard} 合計 ${rx.total}->${r.total} 重み ${rx.weightedScore.toLong()}->${r.weightedScore.toLong()} [$extra] ${diff(r)}")
        }
        val w2 = CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish(st, x, maxPasses = 2)
        line("weekly 研磨 2 巡", w2.newSchedule, "採用 ${w2.applied}")
        val w50 = CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish(st, x, maxPasses = 50)
        line("weekly 研磨 50 巡", w50.newSchedule, "採用 ${w50.applied}")
        val m8 = MembraneCellLns.apply(st, x)
        line("膜の細胞 8 秒", m8.newSchedule, "採用 ${m8.applied} 細胞 ${m8.cellsTried}")
        val m60 = MembraneCellLns.apply(st, x, MembraneCellLns.Params(maxMillis = 60_000L, maxCells = 400))
        line("膜の細胞 60 秒", m60.newSchedule, "採用 ${m60.applied} 細胞 ${m60.cellsTried}")
        for (iters in listOf(60_000, 300_000)) {
            val b = dayBlockSa(st, x, 14, 7, iters)
            line("全職員×14日の焼きなまし ${iters / 1000}k 回", b.first, "採用 ${b.second}")
        }
        System.out.flush()
    }
}

/** MembraneCellLns.solveCell と同じ手（同じ日の 2 名の入れ替え 3/4、1 セルの置き換え 1/4）と温度で、細胞だけを「全職員×日の区間」にした版。 */
fun dayBlockSa(st: MagiState, x0: Array<IntArray>, len: Int, step: Int, iters: Int): Pair<Array<IntArray>, Int> {
    val p = cachedProblem(st)
    val S = p.S; val T = p.T; val K = p.K
    var best = Array(S) { x0[it].copyOf() }
    var bestRep = UnifiedViolationChecker.check(st, best)
    val rng = java.util.Random(1L)
    val de = DeltaEvaluator(p)
    var adopted = 0
    var d0 = 0
    while (d0 < T) {
        val days = (d0 until minOf(T, d0 + len)).toList()
        val work = Array(S) { best[it].copyOf() }
        de.reset(work)
        var cur = de.score(); var bestScore = cur; var bestBoard: Array<IntArray>? = null
        val free = ArrayList<IntArray>()
        for (i in 0 until S) for (j in days) if (!p.wishLocked(i, j)) free.add(intArrayOf(i, j))
        var temp = 40.0
        val cool = Math.pow(0.5 / 40.0, 1.0 / iters)
        for (it in 0 until iters) {
            temp *= cool
            if (rng.nextInt(4) != 0) {
                val j = days[rng.nextInt(days.size)]
                val a = rng.nextInt(S); val b = rng.nextInt(S)
                if (a == b || p.wishLocked(a, j) || p.wishLocked(b, j)) continue
                val ka = work[a][j]; val kb = work[b][j]
                if (ka == kb || !p.mayPlaceAt(a, j, kb) || !p.mayPlaceAt(b, j, ka)) continue
                de.apply(a, j, kb); val s = de.apply(b, j, ka)
                if (acc(s - cur, temp, rng)) { work[a][j] = kb; work[b][j] = ka; cur = s } else { de.apply(b, j, kb); cur = de.apply(a, j, ka) }
            } else {
                if (free.isEmpty()) break
                val c = free[rng.nextInt(free.size)]; val i = c[0]; val j = c[1]
                val k = rng.nextInt(K); val old = work[i][j]
                if (k == old || !p.mayPlaceAt(i, j, k)) continue
                val s = de.apply(i, j, k)
                if (acc(s - cur, temp, rng)) { work[i][j] = k; cur = s } else cur = de.apply(i, j, old)
            }
            if (cur < bestScore) { bestScore = cur; bestBoard = Array(S) { work[it].copyOf() } }
        }
        val cand = bestBoard
        if (cand != null && !exactPinRegression(p, best, cand)) {
            val rep = UnifiedViolationChecker.check(st, cand)
            if (betterReport(rep, bestRep)) { best = cand; bestRep = rep; adopted++ }
        }
        d0 += step
    }
    return best to adopted
}

private fun acc(delta: Long, temp: Double, rng: java.util.Random): Boolean {
    if (delta <= 0) return true
    if (delta >= SCORE_HARD_UNIT) return false
    return rng.nextDouble() < Math.exp(-delta / temp)
}
