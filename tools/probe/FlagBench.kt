package probe.flag

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

// 解き直し（CP-SAT）ON/OFF などのフラグを同じ入力・同じ seed で比べる（ネイティブ＋CP-SAT 込み、優先順位方式）。
// args: resDir out.csv secs seeds(1,2,3) arms(off,resolve,rogs,both) fixtures
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val resDir = args[0]; val out = File(args[1]); val secs = args[2].toInt()
    val seeds = args[3].split(',').map { it.toInt() }
    val arms = args[4].split(',')
    val fixtures = args[5].split(',')
    val exists = if (out.exists()) out.readLines().drop(1).map { it.split(',').take(3).joinToString(",") }.toSet() else emptySet()
    if (!out.exists()) out.writeText("fixture,seed,arm,ms,hard,weighted,total,gap,fair,c1,hash\n")
    println("native=${NativeBridge.available} cpsat=${CpsatBridge.available}")
    for (fx in fixtures) for (seed in seeds) for (arm in arms) {
        if ("$fx,$seed,$arm" in exists) continue
        val st = StateParser.parse(File(resDir, "$fx.json").readText())!!
        PolishGate.cpsatResolve = arm == "resolve" || arm == "both"
        PolishGate.rogsController = arm == "rogs" || arm == "both"
        val t0 = System.currentTimeMillis()
        val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, seed = 1000L + seed) }
        val ms = System.currentTimeMillis() - t0
        val r = res.report
        val line = "$fx,$seed,$arm,$ms,${r.hard},${r.weightedScore.toLong()},${r.total},${r.breakdown["gap"] ?: 0},${r.breakdown["fair"] ?: 0},${r.breakdown["c1"] ?: 0},${res.schedule.contentDeepHashCode().toUInt().toString(16)}"
        out.appendText(line + "\n"); println(line)
        res.logs.filter { it.tag == "CP-SAT" || it.tag == "StopSummary" }.forEach { println("   ${it.tag}: ${it.message.take(300)}") }
    }
}
