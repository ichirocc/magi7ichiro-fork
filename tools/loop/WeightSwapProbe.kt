// [2026-10-27] 重みの入れ替え（c1 50→90・c3mn 90→50、aac2d54）の効果の記録。同じファイルを旧重み・新重みの各ビルドに当てて、
// 製品の既定（仕上げ最適化 ON）の handleOptimize の最終盤面を族ごとの件数で書く（採点の重みでなく件数で比べる）。
package probe

import com.magi.app.model.StateParser
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = File(args[0]); val out = File(args[1]); val arm = args[2]
    val seconds = args.getOrNull(3)?.toInt() ?: 60
    val workers = args.getOrNull(4)?.toInt() ?: 1
    val fixtures = (System.getenv("MAGI_WS_FIXTURES") ?: "golden_state.json,sample_state_v6.json,sept2026_state.json,oct2026_grid_state.json").split(',')
    val seeds = (System.getenv("MAGI_WS_SEEDS") ?: "1,2,3").split(',').map { it.trim().toInt() }
    val fams = MirrorKeys.all
    val w = out.bufferedWriter()
    w.write("fixture,seed,arm,elapsedMs,hard,total," + fams.joinToString(",") + "\n"); w.flush()
    for (f in fixtures) {
        val st = StateParser.parse(File(resDir, f).readText())!!
        for (seed in seeds) {
            val t0 = System.currentTimeMillis()
            val res = runBlocking {
                V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = seconds, workers = workers,
                    allowImpossible = true, softPolish = true, seed = 1000L + seed)
            }
            val line = "${f.removeSuffix(".json")},$seed,$arm,${System.currentTimeMillis() - t0},${res.report.hard},${res.report.total}," +
                fams.joinToString(",") { (res.report.breakdown[it] ?: 0).toString() }
            w.write(line + "\n"); w.flush(); System.err.println(line)
        }
    }
    w.close()
}
