package probe
// [3.601.0/backlog#34] ポートフォリオ経路（algorithm=PORTFOLIO）を実際に回すベンチ。既存の
// LoopBench.kt は経由しない（経緯は docs/history）。PersonSwapBench.kt と同型のハーネスで
// 既定OFFオプションのA/Bを取る（最初の腕 roleBudgetFit は否決・2026-09-25 撤去）。
// [2026-09-21/backlog#28] PORTFOLIO_BENCH_FEATURE で比較対象のオプションを選ぶ。rsiFocusRotationPersist（runRsi呼出しをまたぐ周期枠の持ち越し）は
//   runRsi自体がRSI/RSI_PLUS/PORTFOLIOでしか呼ばれずLoopBench(V5固定)では測定不能なため、ここでのみ測れる。
import com.magi.app.model.StateParser
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6NativeOptimizer
import com.magi.app.v6.V6OptimizerOptions
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = args[0]
    val out = File(args[1])
    val seeds = args.getOrNull(2)?.toInt() ?: 5
    val budgetSec = args.getOrNull(3)?.toInt() ?: 90
    val workers = args.getOrNull(4)?.toInt() ?: 4
    val fixtures = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")
    val feature = System.getenv("PORTFOLIO_BENCH_FEATURE") ?: "rsifocusrotation"
    fun options(seed: Long, armOn: Boolean) = when (feature) {
        "rsifocusrotation" -> V6OptimizerOptions(
            algorithm = V6Algorithm.PORTFOLIO, totalBudgetSec = budgetSec, workers = workers,
            seed = seed, rsiFocusRotationPersist = armOn,
        )
        else -> error("unknown PORTFOLIO_BENCH_FEATURE=$feature")
    }

    val w = java.io.FileWriter(out, false).buffered()
    w.write("fixture,seed,arm,elapsedMs,hard,weightedScore,total,epochOverrunCount\n")
    w.flush()
    System.err.println("feature=$feature")

    for (fname in fixtures) {
        val f = File(resDir, fname)
        if (!f.exists()) { System.err.println("skip missing $fname"); continue }
        val st = StateParser.parse(f.readText())!!
        val sched0 = Array(st.schedule.size) { i -> st.schedule[i].toIntArray() }
        for (seed in 1..seeds) {
            for (armOn in listOf(false, true)) {
                val t0 = System.currentTimeMillis()
                val res = runBlocking {
                    V6NativeOptimizer.optimize(
                        st, sched0.map { it.copyOf() }.toTypedArray(),
                        options(seed.toLong(), armOn),
                        shouldStop = { false },
                    )
                }
                val elapsed = System.currentTimeMillis() - t0
                val rep = UnifiedViolationChecker.check(st, res.schedule)
                val arm = if (armOn) "on" else "off"
                val line = "${fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")},$seed,$arm,$elapsed," +
                    "${rep.hard},${rep.weightedScore},${rep.total},${res.epochOverrunCount}"
                w.write(line + "\n"); w.flush()
                System.err.println(line)
            }
        }
    }
    w.close()
}
