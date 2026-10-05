package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File
fun main(a: Array<String>) {
    for (f in a) {
        val st = StateParser.parse(File(f).readText())!!
        val s0 = st.schedule.map { it.toIntArray() }.toTypedArray()
        val r0 = UnifiedViolationChecker.check(st, s0)
        println("${File(f).name} base H=${r0.hard} W=${r0.weightedScore} T=${r0.total} c1=${r0.breakdown["c1"]}")
        for (o in C1EjectionChainPolish.Origin.values()) for (ev in longArrayOf(200_000L, 2_000_000L)) {
            val stt = C1EjectionChainPolish.Stats()
            val r = C1EjectionChainPolish.apply(st, s0.map { it.copyOf() }.toTypedArray(), C1EjectionChainPolish.Config(origin = o, maxEvaluations = ev), stats = stt)
            val rep = UnifiedViolationChecker.check(st, r.newSchedule)
            println("  $o ev=$ev H=${rep.hard} W=${rep.weightedScore} T=${rep.total} c1=${rep.breakdown["c1"]} acc=${stt.accepted} end=${stt.endReason} | ${r.logs.last().message.substringAfter("族差").take(160)}")
        }
    }
}
