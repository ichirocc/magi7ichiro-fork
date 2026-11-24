// [2026-11-24] シーソーの 3 人 × 2 日の巡回（PolishGate.cycleSwapPolish）の 2 腕比較（docs/seesaw.md §2.2）。
// mode=full: 同じ入力・同じ種で製品の handleOptimize を OFF／ON。mode=post: 同じ盤面（*.opt.json）に決定的な後処理を OFF／ON。
// args: mode files(csv) seconds seeds(csv) out.csv
package probe.cycleonoff

import com.magi.app.model.StateParser
import com.magi.app.v6.PolishGate
import com.magi.app.v6.ObjectiveMethod
import com.magi.app.v6.ObjectiveWeights
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6HotfixPasses
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val mode = args[0]; val files = args[1].split(','); val seconds = args[2].toInt(); val seeds = args[3].split(',').map { it.toLong() }
    val w = File(args[4]).bufferedWriter()
    w.write("fixture,seed,arm,hard,total,weighted,ms,hash\n"); w.flush()
    val before = PolishGate.cycleSwapPolish
    for (f in files) {
        val st = StateParser.parse(File(f).readText())!!
        for (seed in seeds) for (arm in listOf("off", "on")) {
            PolishGate.cycleSwapPolish = arm == "on"
            val t0 = System.currentTimeMillis()
            val board = st.schedule.map { it.toIntArray() }.toTypedArray()
            val out = if (mode == "post") {
                V6HotfixPasses.runPostOptimization(st, board, "bench", seed = seed,
                    params = V6HotfixPasses.PostOptimizationParams(deterministic = true, cycleSwap = arm == "on")).schedule
            } else runBlocking {
                V6FinalPort.handleOptimize(st, board, secondsRaw = seconds, workers = 4, allowImpossible = true, softPolish = true, seed = 1000L + seed).schedule
            }
            val r = UnifiedViolationChecker.check(st, out)
            val line = "${File(f).nameWithoutExtension},$seed,$arm,${r.hard},${r.total},${r.weightedScore.toLong()},${System.currentTimeMillis() - t0},${out.contentDeepHashCode().toUInt().toString(16)}"
            w.write(line + "\n"); w.flush(); System.err.println(line)
        }
    }
    PolishGate.cycleSwapPolish = before
    w.close()
}
