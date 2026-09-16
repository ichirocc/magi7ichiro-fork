package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File
import kotlinx.coroutines.runBlocking

/** [backlog#19] `officialTieBreak` 計測用ベンチ：同一 seed・同一実行内で fullEval 基準の選定(`schedule`)と
 *  `betterReport` 基準で並行追跡した候補(`officialBestSchedule`)を比較する＝探索の分岐は同一、勝者選定だけの
 *  差を後知恵評価する（選定には非介入、`SaOfficialTieBreakTest` で固定済み）。使い方:
 *  `tools/loop/run_tiebreak_bench.sh [out.csv] [seeds=5] [budgetMs=20000] [workers=4] [resDir]`
 *  （列の `off*`=official側の同評価、`diverged`=盤面差、`officialWins`=diverged かつ official が優る）。 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0) ?: "tools/loop/results/tiebreak.csv")
    val seeds = args.getOrNull(1)?.toInt() ?: 5
    val budgetMs = args.getOrNull(2)?.toLong() ?: 20_000L
    val workers = args.getOrNull(3)?.toInt() ?: 4
    val resDir = File(args.getOrNull(4) ?: "app/src/test/resources")

    val fixtures = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")
    out.parentFile?.mkdirs()
    val already = if (out.exists()) out.readLines().drop(1).map { it.substringBefore(',') + "," + it.split(",")[1] }.toSet() else emptySet()
    val header = "fixture,seed,budgetMs,workers,ms,hard,weighted,total,changed,offHard,offWeighted,offTotal,offChanged,diverged,officialWins"
    if (!out.exists()) out.writeText(header + "\n")

    fun changedCells(a: Array<IntArray>, b: Array<IntArray>): Int {
        var n = 0
        for (i in a.indices) for (j in a[i].indices) if (a[i][j] != b[i][j]) n++
        return n
    }
    fun boardKey(s: Array<IntArray>): Long { var h = 1125899906842597L; for (row in s) for (v in row) h = h * 31L + v; return h }

    for (f in fixtures) {
        val st = StateParser.parse(File(resDir, f).readText()) ?: continue
        val label = f.removeSuffix("_state.json").removeSuffix("_state_v6.json")
        for (seed in 1L..seeds) {
            if ("$label,$seed" in already) { println("skip $label seed=$seed (already in $out)"); continue }
            val p = Problem(st)
            val ev = Evaluator(p)
            val init = p.initialAssignment()
            val t0 = System.nanoTime()
            val r = runBlocking {
                SaOptimizer(p, ev).run(SaParams(budgetMs = budgetMs, workers = workers, seed = seed, officialTieBreak = true))
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val rep = UnifiedViolationChecker.check(st, r.schedule)
            val offRep = r.officialBestReport!!
            val offSched = r.officialBestSchedule!!
            val diverged = boardKey(r.schedule) != boardKey(offSched)
            val officialWins = diverged && betterReport(offRep, rep)
            val row = listOf(
                label, seed, budgetMs, workers, ms,
                rep.hard, rep.weightedScore, rep.total, changedCells(init, r.schedule),
                offRep.hard, offRep.weightedScore, offRep.total, changedCells(init, offSched),
                diverged, officialWins,
            ).joinToString(",")
            out.appendText(row + "\n")
            println("$label seed=$seed ms=$ms hard=${rep.hard} weighted=${rep.weightedScore} total=${rep.total} | " +
                "official hard=${offRep.hard} weighted=${offRep.weightedScore} total=${offRep.total} | diverged=$diverged officialWins=$officialWins")
        }
    }
}
