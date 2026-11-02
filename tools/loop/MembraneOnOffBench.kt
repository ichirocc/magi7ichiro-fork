// [2026-11-02] 膜の細胞 LNS（PolishGate.membraneCellLns）の 2 腕比較。同じ入力・同じ種で製品の handleOptimize を OFF／ON で回し、正式評価を書く。
package probe.membraneonoff

import com.magi.app.model.StateParser
import com.magi.app.v6.PolishGate
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val files = args[0].split(','); val seconds = args[1].toInt(); val seeds = args[2].split(',').map { it.toLong() }
    val w = File(args[3]).bufferedWriter()
    w.write("fixture,seed,arm,hard,total,weighted,ms\n"); w.flush()
    for (f in files) {
        val st = StateParser.parse(File(f).readText())!!
        for (seed in seeds) for (arm in listOf("off", "on")) {
            PolishGate.membraneCellLns = arm == "on"
            val t0 = System.currentTimeMillis()
            val res = runBlocking {
                V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = seconds, workers = 4,
                    allowImpossible = true, softPolish = true, seed = 1000L + seed)
            }
            val r = UnifiedViolationChecker.check(st, res.schedule)
            val line = "${File(f).nameWithoutExtension},$seed,$arm,${r.hard},${r.total},${r.weightedScore.toLong()},${System.currentTimeMillis() - t0}"
            w.write(line + "\n"); w.flush(); System.err.println(line)
        }
    }
    PolishGate.membraneCellLns = false
    w.close()
}
