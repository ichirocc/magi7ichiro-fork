// 下書き（SmartInitialScheduler）の設定を ON/OFF で比べ、下書きの質と、その下書きから製品の最適化をかけた最終の質を出す（測定だけ）。
// args: files(csv) secs seeds(csv)。secs=0 なら下書きだけ。腕は spacedDeal の off/on。
package probe.draftbench

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    PolishGate.seesawRelink = true
    val files = args[0].split(','); val secs = args[1].toInt(); val seeds = args[2].split(',').map { it.toLong() }
    fun line(r: ViolationReport) = "必須=${r.hard} 重み=${r.weightedScore.toLong()} gap=${r.breakdown["gap"] ?: 0} c1=${r.breakdown["c1"] ?: 0} c3n=${r.breakdown["c3n"] ?: 0} covU=${r.breakdown["covU"] ?: 0}"
    for (f in files) {
        val st = StateParser.parse(File(f).readText())!!
        val name = File(f).nameWithoutExtension
        for (arm in listOf("off", "on")) {
            SmartInitialScheduler.spacedDeal = arm == "on"
            val draft = SmartInitialScheduler.generate(st).schedule
            val dr = UnifiedViolationChecker.check(st, draft)
            println("DRAFT $name $arm ${line(dr)} hash=${draft.contentDeepHashCode().toUInt().toString(16)}")
            if (secs <= 0) continue
            for (seed in seeds) {
                val res = runBlocking { V6FinalPort.handleOptimize(st, draft.map { it.copyOf() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, softPolish = true, seed = 1000L + seed) }
                println("FINAL $name $arm seed$seed ${line(res.report)}")
            }
        }
        SmartInitialScheduler.spacedDeal = false
    }
}
