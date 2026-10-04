package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.PolishGate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

fun main(args: Array<String>) {
    val resDir = args[0]; val out = File(args[1]); val logOut = File(args[2])
    val done = if (out.exists()) out.readLines().drop(1).map { it.split(",").take(4).joinToString(",") }.toSet() else emptySet()
    val fos = FileOutputStream(out, true); val lfos = FileOutputStream(logOut, true)
    if (done.isEmpty() && out.length() == 0L) { fos.write("fixture,budget,seed,arm,wallMs,hard,weightedScore,total,c1\n".toByteArray()); fos.fd.sync() }
    for (fname in args.drop(3)) {
        val st = StateParser.parse(File(resDir, fname).readText())!!
        val fx = fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")
        for (budget in listOf(60, 120)) for (seed in 1..3) {
            val arms = if (seed % 2 == 1) listOf(true, false) else listOf(false, true)
            for (on in arms) {
                val arm = if (on) "on" else "off"
                val key = "$fx,$budget,$seed,$arm"; if (key in done) continue
                PolishGate.c1EjectionChain = on
                val t0 = System.currentTimeMillis()
                val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(),
                    secondsRaw = budget, allowImpossible = true, seed = seed.toLong()) }
                val wall = System.currentTimeMillis() - t0
                val sb = StringBuilder()
                for (l in res.logs + (res.post?.logs ?: emptyList())) if (l.message.contains("ms")) sb.append("$key\t${l.ts - t0}\t${l.tag}\t${l.message.replace('\n',' ')}\n")
                lfos.write(sb.toString().toByteArray()); lfos.fd.sync()
                val line = "$key,$wall,${res.report.hard},${res.report.weightedScore},${res.report.total},${res.report.breakdown["c1"] ?: 0}\n"
                fos.write(line.toByteArray()); fos.fd.sync(); System.err.print(line)
            }
        }
    }
}
