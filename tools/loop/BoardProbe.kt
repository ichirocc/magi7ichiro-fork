package probe.board
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File
import org.json.JSONArray
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val st = StateParser.parse(File(args[0]).readText())!!
    val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = args[2].toInt(), workers = args[3].toInt(), allowImpossible = true, softPolish = true, seed = args[4].toLong()) }
    val rep = UnifiedViolationChecker.check(st, res.schedule)
    val o = org.json.JSONObject(File(args[0]).readText())
    o.put("schedule", JSONArray(res.schedule.map { JSONArray(it.toList()) }))
    File(args[1]).writeText(o.toString())
    System.err.println("RESULT hard=${rep.hard} weighted=${rep.weightedScore.toLong()} fair=${rep.breakdown["fair"]}")
}
