package probe

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File

/**
 * [3.680.0] 後処理の無駄取り 2 件（PolishGate.rotationDeltaPrescreen / roundPassMemo）の probe。実データ 4 件で、
 * 同じ初期盤面（V5 短時間・seed 固定）から決定的モードの後処理を ON/OFF で交互に走らせ、最終盤面ハッシュと所要 ms を比べる。
 * 使い方: 引数 = resources ディレクトリ [反復=2] [初期盤面の秒=10]
 */
fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
    val resDir = File(args[0]); val reps = args.getOrNull(1)?.toInt() ?: 2; val sec = args.getOrNull(2)?.toInt() ?: 10
    println("fixture,arm,rep,ms,rotMs,hash,hard,weighted,total")
    for (f in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")) {
        val st = StateParser.parse(File(resDir, f).readText())!!
        val init = V6NativeOptimizer.optimize(st, options = V6OptimizerOptions(algorithm = V6Algorithm.V5, totalBudgetSec = sec, workers = 1,
            softPolish = false, restarts = 0, seed = 0x5EA1L, postPolish = false)).schedule
        val params = V6HotfixPasses.PostOptimizationParams(deterministic = true)
        for (rep in 0 until reps) for (arm in listOf("off", "on")) {
            PolishGate.rotationDeltaPrescreen = arm == "on"
            PolishGate.roundPassMemo = arm == "on"
            val t = System.nanoTime()
            val r = V6HotfixPasses.runPostOptimization(st, init.copy2D(), "probe", seed = 11L, params = params)
            val ms = (System.nanoTime() - t) / 1_000_000
            val rot = r.stageRecords.filter { it.key == "BlockRotationPolish" }.sumOf { it.ms }
            val h = r.schedule.contentDeepHashCode()
            println("${f.removeSuffix(".json")},$arm,$rep,$ms,$rot,$h,${r.report.hard},${r.report.weightedScore.toLong()},${r.report.total}")
        }
    }
    PolishGate.rotationDeltaPrescreen = true; PolishGate.roundPassMemo = true
}
