// CP-SAT の実験用（2026-11-16）: アプリが CP-SAT に渡す盤面（HardRepairCore.clearCappedCells 後の入力）を schedule に入れた state JSON を書く。
//   引数: <state.json> <out.json>。入力そのままを渡すと、置けない値を残した別の問題を測ることになる（docs/cpsat_formal.md の訂正）。
package probe.cpz
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
fun main(args: Array<String>) {
    val txt = File(args[0]).readText()
    val st = StateParser.parse(txt)!!
    val (capped, n) = HardRepairCore.clearCappedCells(st, st.schedule.map { it.toIntArray() }.toTypedArray())
    val o = JSONObject(txt); o.put("schedule", JSONArray(capped.map { JSONArray(it.toList()) }))
    File(args[1]).writeText(o.toString()); System.err.println("capped cells=$n")
}
