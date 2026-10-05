package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.PolishGate
import com.magi.app.v6.C1EjectionChainPolish
import com.magi.app.v6.UnifiedViolationChecker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

// ARMS=all,c1,off（all＝全族起点, c1＝C1起点v2, off＝なし）。奇数 seed は列挙順、偶数 seed は逆順。
// all の各回で連鎖の直前盤面を捕まえ、同じ盤面から C1 起点・全族起点の連鎖だけを固定評価予算で比べる（chainonly.csv）。
fun main(args: Array<String>) {
    val resDir = args[0]; val out = File(args[1]); val logOut = File(args[2])
    val only = File(out.parentFile, "chainonly.csv")
    val done = if (out.exists()) out.readLines().drop(1).map { it.split(",").take(4).joinToString(",") }.toSet() else emptySet()
    val fos = FileOutputStream(out, true); val lfos = FileOutputStream(logOut, true)
    if (done.isEmpty() && out.length() == 0L) { fos.write("fixture,budget,seed,arm,wallMs,hard,weightedScore,total,c1,sha,inputHash,order\n".toByteArray()); fos.fd.sync() }
    if (!only.exists()) only.writeText("fixture,budget,seed,entryHash,entryW,entryHard,c1W,c1Hard,c1Acc,c1Evals,c1End,allW,allHard,allAcc,allEvals,allEnd,allFam\n")
    for (fname in args.drop(3)) {
        val st = StateParser.parse(File(resDir, fname).readText())!!
        val ih = java.security.MessageDigest.getInstance("SHA-256").digest(File(resDir, fname).readBytes()).take(6).joinToString("") { "%02x".format(it) }
        val fx = fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")
        for (budget in listOf(60, 120)) for (seed in 1..3) {
            val armList = (System.getenv("ARMS") ?: "all,c1,off").split(",")
            val arms = if (seed % 2 == 1) armList else armList.reversed()
            for (arm in arms) {
                val key = "$fx,$budget,$seed,$arm"; if (key in done) continue
                PolishGate.c1EjectionChain = arm == "c1"
                PolishGate.allFamilyEjectionChain = arm == "all"
                var entry: Array<IntArray>? = null
                C1EjectionChainPolish.entryProbe = if (arm == "all") { b -> if (entry == null) entry = b } else null
                val t0 = System.currentTimeMillis()
                val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(),
                    secondsRaw = budget, allowImpossible = true, seed = seed.toLong()) }
                val wall = System.currentTimeMillis() - t0
                C1EjectionChainPolish.entryProbe = null
                val sb = StringBuilder()
                for (l in res.logs + (res.post?.logs ?: emptyList())) if (l.message.contains("ms")) sb.append("$key\t${l.ts - t0}\t${l.tag}\t${l.message.replace('\n',' ')}\n")
                lfos.write(sb.toString().toByteArray()); lfos.fd.sync()
                val line = "$key,$wall,${res.report.hard},${res.report.weightedScore},${res.report.total},${res.report.breakdown["c1"] ?: 0},${System.getenv("SHA") ?: "?"},$ih,${arms.indexOf(arm)}\n"
                fos.write(line.toByteArray()); fos.fd.sync(); System.err.print(line)
                File(out.parentFile, "breakdown.tsv").appendText("$key\t${res.report.breakdown.entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
                val e = entry
                if (e != null) {
                    val r0 = UnifiedViolationChecker.check(st, e)
                    val eh = e.contentDeepHashCode()
                    fun runOnly(o: C1EjectionChainPolish.Origin): Pair<com.magi.app.v6.ViolationReport, C1EjectionChainPolish.Stats> {
                        val s = C1EjectionChainPolish.Stats()
                        val r = C1EjectionChainPolish.apply(st, e.map { it.copyOf() }.toTypedArray(),
                            C1EjectionChainPolish.Config(origin = o, maxEvaluations = 2_000_000L), stats = s)
                        return UnifiedViolationChecker.check(st, r.newSchedule) to s
                    }
                    val (rc, sc) = runOnly(C1EjectionChainPolish.Origin.C1)
                    val (ra, sa) = runOnly(C1EjectionChainPolish.Origin.ALL)
                    val fam = sa.byFamily.entries.joinToString(" ") { (f, v) -> "$f:${v[0]}/${v[1]}/${v[2]}/${v[3]}" }
                    only.appendText("$fx,$budget,$seed,$eh,${r0.weightedScore},${r0.hard},${rc.weightedScore},${rc.hard},${sc.accepted},${sc.evaluations},${sc.endReason},${ra.weightedScore},${ra.hard},${sa.accepted},${sa.evaluations},${sa.endReason},$fam\n")
                }
            }
        }
    }
}
