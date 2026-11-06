// [2026-11-05] 重みを使わない目的関数の P0（docs/weightless_objective.md §7.3・§8）。今の製品の作成の最終盤面について、
// 段の鍵（ライン 0）・職員ごとの不満の分布（§4.4b）・fair の leximin の上位を書く。出力不変（製品コードの採否は変えない）。
package probe.tierkey

import com.magi.app.model.StateParser
import com.magi.app.v6.Problem
import com.magi.app.v6.TierObjective
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = File(args[0]); val out = File(args[1])
    val seconds = args.getOrNull(2)?.toInt() ?: 60
    val fixtures = (System.getenv("MAGI_TK_FIXTURES") ?: "golden_state.json,sample_state_v6.json,blocked_covu_state.json,sept2026_state.json,oct2026_grid_state.json,full_coverage_state.json").split(',')
    val seeds = (System.getenv("MAGI_TK_SEEDS") ?: "1,2,3").split(',').map { it.trim().toLong() }
    val w = out.bufferedWriter()
    w.write("fixture,seed,hard,total,T0,T1,T2,T3,disMax,disSum,disStaff,disTop5,fairMax,fairSum,fairTop5\n"); w.flush()
    for (f in fixtures) {
        val st = StateParser.parse(File(resDir, f).readText())!!
        val p = Problem(st)
        for (seed in seeds) {
            val res = runBlocking {
                V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = seconds, workers = 1,
                    allowImpossible = true, softPolish = true, seed = 1000L + seed)
            }
            val rep = UnifiedViolationChecker.check(st, res.schedule)
            val k = TierObjective.key(p, res.schedule, rep)
            val line = listOf(f.removeSuffix(".json"), seed, rep.hard, rep.total) + k.excess +
                listOf(k.maxDissatisfaction, k.sumDissatisfaction, k.dissatisfaction.size, k.dissatisfaction.take(5).joinToString(" "),
                    k.fairLeximin.firstOrNull() ?: 0, k.fairLeximin.sum(), k.fairLeximin.take(5).joinToString(" "))
            w.write(line.joinToString(",") + "\n"); w.flush(); System.err.println(line.joinToString(","))
        }
    }
    w.close()
}
