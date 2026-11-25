// [2026-11-24] シーソーの 3 人 × 2 日の巡回（PolishGate.cycleSwapPolish）の 2 腕比較（docs/seesaw.md §2.2）。
// mode=full: 同じ入力・同じ種で製品の handleOptimize を OFF／ON。mode=post: 同じ盤面（*.opt.json）に決定的な後処理を OFF／ON。
// args: mode files(csv) seconds seeds(csv) out.csv [arms=off,on]（off2 は OFF をもう 1 回＝ばらつきの基準）。
// 段ごとの行（CycleSwapPolish・SeesawRelink・ExtraRefine・TIME）は out.csv と同じ場所の .log へ書く（負けの分解用）。
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
    val arms = args.getOrNull(5)?.split(',') ?: listOf("off", "on")
    val lw = File(args[4] + ".log").bufferedWriter()
    w.write("fixture,seed,arm,hard,total,weighted,ms,hash\n"); w.flush()
    val before = PolishGate.cycleSwapPolish
    for (f in files) {
        val st = StateParser.parse(File(f).readText())!!
        for (seed in seeds) for (arm in arms) {
            PolishGate.cycleSwapPolish = arm == "on"
            val t0 = System.currentTimeMillis()
            val board = st.schedule.map { it.toIntArray() }.toTypedArray()
            val (out, logs) = if (mode == "post") {
                val r = V6HotfixPasses.runPostOptimization(st, board, "bench", seed = seed,
                    params = V6HotfixPasses.PostOptimizationParams(deterministic = true, cycleSwap = arm == "on"))
                r.schedule to r.logs
            } else runBlocking {
                val r = V6FinalPort.handleOptimize(st, board, secondsRaw = seconds, workers = 4, allowImpossible = true, softPolish = true, seed = 1000L + seed)
                r.schedule to r.logs
            }
            for (l in logs) if (l.tag in setOf("CycleSwapPolish", "SeesawRelink", "ExtraRefine", "TIME", "PostChain"))
                lw.write("${File(f).nameWithoutExtension},$seed,$arm,${l.tag},${l.message.replace("\n", " ")}\n")
            lw.flush()
            val r = UnifiedViolationChecker.check(st, out)
            val line = "${File(f).nameWithoutExtension},$seed,$arm,${r.hard},${r.total},${r.weightedScore.toLong()},${System.currentTimeMillis() - t0},${out.contentDeepHashCode().toUInt().toString(16)}"
            w.write(line + "\n"); w.flush(); System.err.println(line)
        }
    }
    PolishGate.cycleSwapPolish = before
    w.close(); lw.close()
}
