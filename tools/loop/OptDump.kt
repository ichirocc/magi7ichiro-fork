// [2026-11-01] 膜の探索の M0 用: fixture を製品の既定（仕上げ最適化 ON）の handleOptimize で回し、出力の盤面を JSON で書く。
package probe.optdump

import com.magi.app.model.StateParser
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.File

fun main(args: Array<String>) {
    val st = StateParser.parse(File(args[0]).readText())!!
    val res = runBlocking {
        V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = args[2].toInt(), workers = args[3].toInt(),
            allowImpossible = true, softPolish = true, seed = 1001L)
    }
    File(args[1]).writeText(JSONArray(res.schedule.map { JSONArray(it.toList()) }).toString())
    System.err.println("hard=${res.report.hard} total=${res.report.total}")
}
