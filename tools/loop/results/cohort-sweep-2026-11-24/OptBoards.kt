// 付属データを 30 秒（優先順位）で最適化し、盤面を入れ替えた JSON を書き出す（組スイープの入力＝最適化後の頭打ちの盤面）。
package probe.optboards
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val secs = args[0].toInt(); val outDir = File(args[1])
    for (path in args.drop(2)) {
        val text = File(path).readText()
        val st = StateParser.parse(text)!!
        val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, softPolish = true, seed = 1001L) }
        val o = JSONObject(text)
        o.put("schedule", JSONArray(res.schedule.map { row -> JSONArray(row.toList()) }))
        val name = File(path).nameWithoutExtension
        File(outDir, "$name.opt.json").writeText(o.toString())
        println("OPT $name 必須=${res.report.hard} 重み=${res.report.weightedScore.toLong()} 合計=${res.report.total}")
    }
}
