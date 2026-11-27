// 決定的な後処理の時間と最終盤面のハッシュを出す（出力不変の高速化の照合用、3.727.136）。args: state.json...（REPS で回数）
package probe.postprof
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File
// 決定的な後処理を入力盤面にかけ、時間とハッシュを出す（出力不変の高速化の照合用）。args: files... [reps=1 via env REPS]
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val reps = (System.getenv("REPS") ?: "1").toInt()
    for (f in args) {
        val st = StateParser.parse(File(f).readText())!!
        repeat(reps) {
            val t0 = System.nanoTime()
            val r = V6HotfixPasses.runPostOptimization(st, st.schedule.map { it.toIntArray() }.toTypedArray(), "prof", seed = 1L,
                params = V6HotfixPasses.PostOptimizationParams(deterministic = true))
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("${File(f).nameWithoutExtension} ms=$ms hard=${r.report.hard} w=${r.report.weightedScore.toLong()} hash=${r.schedule.contentDeepHashCode().toUInt().toString(16)}")
        }
    }
}
