// CP-SAT の実験用（2026-11-16）: 優先順位方式で handleOptimize を回し、結果の盤面を schedule に入れた state JSON を書く
//   （tools/native/state_to_flat.py にそのまま通せる）。引数: <state.json> <out.json> <秒> <workers> <seed>。組み方は OptDump.kt と同じ。
package probe.cpz
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val txt = File(args[0]).readText()
    val st = StateParser.parse(txt)!!
    val res = runBlocking {
        V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = args[2].toInt(), workers = args[3].toInt(),
            allowImpossible = true, softPolish = true, seed = 1000L + args[4].toLong())
    }
    // state JSON の schedule を結果に差し替えて書く（state_to_flat.py にそのまま通すため）
    val o = JSONObject(txt)
    o.put("schedule", JSONArray(res.schedule.map { JSONArray(it.toList()) }))
    File(args[1]).writeText(o.toString())
    val r = res.report
    System.err.println("RESULT seed=${args[4]} hard=${r.hard} weighted=${r.weightedScore} total=${r.total} bd=${r.breakdown}")
}
