package probe
// [2026-09-21/backlog#35] `V6FinalPort.handleOptimize`のExtraRefine省略条件
// （`extraRefineRequirePostHardDrop`、3.600.0実装・既定OFF）をA/Bする。既存のLoopBench.kt/PortfolioBudgetBench.kt
// は`runPostOptimization`より下の層しか呼ばず、ExtraRefine自体はhandleOptimize内部の別ステージなので
// この層を直接呼ぶ新規ハーネスが要る。
import com.magi.app.model.StateParser
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = args[0]
    val out = File(args[1])
    val seeds = args.getOrNull(2)?.toInt() ?: 5
    val secondsBudget = args.getOrNull(3)?.toInt() ?: 60
    val workers = args.getOrNull(4)?.toInt() ?: 4
    val fixtures = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")

    val w = java.io.FileWriter(out, false).buffered()
    w.write("fixture,seed,arm,elapsedMs,hard,weightedScore,total\n")
    w.flush()

    for (fname in fixtures) {
        val f = File(resDir, fname)
        if (!f.exists()) { System.err.println("skip missing $fname"); continue }
        val st = StateParser.parse(f.readText())!!
        for (seed in 1..seeds) {
            for (armOn in listOf(false, true)) {
                val t0 = System.currentTimeMillis()
                val res = runBlocking {
                    V6FinalPort.handleOptimize(
                        st, st.schedule.map { it.toIntArray() }.toTypedArray(),
                        secondsRaw = secondsBudget, workers = workers,
                        extraRefineRequirePostHardDrop = armOn,
                    )
                }
                val elapsed = System.currentTimeMillis() - t0
                val arm = if (armOn) "on" else "off"
                val line = "${fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")},$seed,$arm,$elapsed," +
                    "${res.report.hard},${res.report.weightedScore},${res.report.total}"
                w.write(line + "\n"); w.flush()
                System.err.println(line)
            }
        }
    }
    w.close()
}
