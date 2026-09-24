package probe

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.ui.UiState
import com.magi.app.ui.involvedWishes
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * S5（希望を外して試算）の机上試験。盤面 B（本計算の結果）で必須違反に関わる希望を 1 件ずつ外し、
 * 短い試算の見込み（配置固定・1手探索・短い最適化・短い後処理）と、同じ希望を外した本計算（正解）を比べる。
 * 外さずに本計算をやり直した結果（G0）も取り、外したことの本当の効果＝G0−G を測る。
 * 使い方: tools/loop/run_s5probe.sh out.csv [本計算秒=30] [試算秒=3] [1盤あたりの希望上限=6]
 */
fun main(args: Array<String>) = runBlocking {
    val out = File(args[0]); val fullSec = args.getOrNull(1)?.toInt() ?: 30
    val trialSec = args.getOrNull(2)?.toInt() ?: 3; val perBoard = args.getOrNull(3)?.toInt() ?: 6
    val resDir = File(args.getOrNull(4) ?: "app/src/test/resources")
    val fixtures = ArrayList<Pair<String, MagiState>>()
    for (f in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json"))
        fixtures += f.removeSuffix(".json") to StateParser.parse(File(resDir, f).readText())
    for (sp in Cases.specs.filter { (it.cat == "wishheavy" || it.cat == "forbidden") && it.size != "small" && it.id.endsWith("0") })
        fixtures += sp.id to Cases.build(sp)
    out.writeText("fixture,staff,day,reason,hardB,hardG0,fixed,hardFix,msFix,hardOpt,msOpt,hardPost,msPost,hardG,msG\n")
    for ((name, st0) in fixtures) {
        val b = V6FinalPort.handleOptimize(st0, st0.schedule.toIntArray2D(), secondsRaw = fullSec, workers = 2, allowImpossible = true).schedule
        val st = st0.withSchedule(b)
        val repB = UnifiedViolationChecker.check(st, b)
        val ui = UiState(staffNames = st.staff.map { it.name }, wishes = st.wishes, violationCellFamilies = repB.cellFamilies)
        val ws = involvedWishes(ui).distinctBy { it.staff to it.day }.filter { st.wishes.containsKey("${it.staff},${it.day}") }.take(perBoard)
        System.err.println("$name hardB=${repB.hard} involved=${involvedWishes(ui).size} probing=${ws.size}")
        if (ws.isEmpty()) continue
        val g0 = V6FinalPort.handleOptimize(st, b, secondsRaw = fullSec, workers = 2, allowImpossible = true).report.hard
        for (w in ws) {
            val key = "${w.staff},${w.day}"
            val st2 = st.copy(wishes = st.wishes - key)
            val fixed = UnifiedViolationChecker.check(st2, b).hard
            var t = System.currentTimeMillis()
            val sugg = FixSuggester.suggest(st2, b, maxResults = 8, deadlineMs = trialSec * 1000L)
            val hardFix = fixed + (sugg.minOfOrNull { it.deltaHard } ?: 0).coerceAtMost(0)
            val msFix = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val opt = V6NativeOptimizer.optimize(st2, b.copy2D(), V6OptimizerOptions(algorithm = V6Algorithm.V5, totalBudgetSec = trialSec,
                workers = 1, softPolish = false, restarts = 0, seed = 1L, postPolish = false)).report.hard
            val msOpt = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val post = V6HotfixPasses.runPostOptimization(st2, b.copy2D(), "s5", seed = 5L,
                deadlineMs = EngineClock.nowMs() + trialSec * 1000L).report.hard
            val msPost = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val g = V6FinalPort.handleOptimize(st2, b, secondsRaw = fullSec, workers = 2, allowImpossible = true).report.hard
            val msG = System.currentTimeMillis() - t
            val row = listOf(name, w.staff, w.day, w.reason, repB.hard, g0, fixed, hardFix, msFix, opt, msOpt, post, msPost, g, msG).joinToString(",")
            out.appendText(row + "\n"); System.err.println(row)
        }
    }
}
