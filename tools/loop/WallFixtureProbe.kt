package probe.wall
// [2026-10-09/3.643.0 手順②] 経験的な c3n 壁（全セル塞がり・希望固定でなく・1 手探索が反証する）を持つ盤面を、合成ケース
// （LoopBench の Cases）の短い最適化の最終盤面から探してフィクスチャ JSON に書く調査用ハーネス。採点・探索は呼ぶだけで変えない。
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val outDir = File(args[0]).apply { mkdirs() }
    val prefix = args.getOrNull(1) ?: ""              // Cases.specs の id の部分一致（空＝全部）
    val variants = args.getOrNull(2)?.toInt() ?: 3    // seed の変種数
    val budgetSec = args.getOrNull(3)?.toInt() ?: 4
    val algo = V6Algorithm.valueOf(args.getOrNull(4) ?: "AUTO")
    val csv = File(outDir, "wall_probe.csv")
    if (!csv.exists()) csv.writeText("case,seed,ms,hard,c3n,covU,pref,groupViol,c3w,runs,allBlocked,certified,refuted,escapes\n")
    var found = 0
    for (sp0 in probe.Cases.specs.filter { it.id.contains(prefix) }) for (k in 0 until variants) {
        val sp = sp0.copy(id = sp0.id + "-s$k", seed = sp0.seed + k * 1009L)
        val st = probe.Cases.build(sp)
        val t0 = System.currentTimeMillis()
        val res = runBlocking {
            V6NativeOptimizer.optimize(st, options = V6OptimizerOptions(algorithm = algo, totalBudgetSec = budgetSec, workers = 1,
                softPolish = false, restarts = 0, seed = sp.seed, postPolish = false))
        }
        val ms = System.currentTimeMillis() - t0
        val sched = res.schedule
        val rep = UnifiedViolationChecker.check(st, sched)
        val bd = rep.breakdown
        val c3n = bd["c3n"] ?: 0
        var allBlocked = false; var certified = false; var refuted = false; var escapes = ""; var runs = 0
        if (c3n > 0) {
            val diag = V6PortAnalyzer.diagnoseForbiddenRuns(st, sched)
            runs = diag.totalRuns
            allBlocked = diag.allBlocked; certified = diag.allBlockedCertified
            escapes = diag.runs.joinToString(";") { r -> r.cells.joinToString("/") { it.escape.name.take(2) } }
            if (allBlocked && !certified) refuted = V6PortAnalyzer.c3nWallRefutedByOneMove(st, sched)
        }
        val line = "${sp.id},${sp.seed},$ms,${rep.hard},$c3n,${bd["covU"] ?: 0},${bd["pref"] ?: 0},${bd["groupViol"] ?: 0},${bd["c3w"] ?: 0},$runs,$allBlocked,$certified,$refuted,$escapes"
        csv.appendText(line + "\n"); println(line)
        if (allBlocked && !certified) {
            val f = File(outDir, "wall_${sp.id}_${if (refuted) "refuted" else "confirmed"}_state.json")
            f.writeText(StateParser.serialize(st, sched)); found++
            println("  -> wrote ${f.name}")
        }
    }
    println("done: empirical walls written=$found")
}
