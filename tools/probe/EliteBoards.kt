package probe.eliteboards

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

// SpprcGridFixingLns の数え上げ（docs/seesaw.md §6）用のエリート: 付属データを乱数を変えて製品の最適化にかけ、本命と他の案の盤面を
// 1 行 1 職員の JSON 配列で書き出す（<out>/<fixture>.s<seed>.<n>.rows、n=0 が本命）。測定だけ。
// args: resDir outDir secs seeds(csv) fixtures(csv)
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    PolishGate.seesawRelink = true
    val res = File(args[0]); val out = File(args[1]).also { it.mkdirs() }; val secs = args[2].toInt()
    for (fx in args[4].split(',')) for (seed in args[3].split(',').map { it.toLong() }) {
        val st = StateParser.parse(File(res, "$fx.json").readText())!!
        val r = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, softPolish = true, seed = seed) }
        (listOf(r.schedule) + r.alternatives).forEachIndexed { n, b ->
            File(out, "$fx.s$seed.$n.rows").writeText(b.joinToString("\n") { row -> row.joinToString(",", "[", "]") } + "\n")
        }
        println("ELITE $fx seed=$seed 必須=${r.report.hard} 重み=${r.report.weightedScore.toLong()} 案=${r.alternatives.size}")
    }
}
