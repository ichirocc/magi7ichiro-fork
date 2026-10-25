package probe
// [2026-10-25] 作り直しの起点の対比較（backlog 探索動学「同じ盤面から作り直すと同じ結果、下書きからだと SOFT が下がった」）。
//   既定の handleOptimize で 1 回作った結果 R を起点に、A＝R からもう 1 回、B＝R の状態で作った下書き（handleSmartInitial）から 1 回。
//   CSV は ho_stats.py の形（off＝A、on＝B）。(out).best.csv は on＝A と B の良い方（製品で「良い方を採る」場合）。
import com.magi.app.model.StateParser
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.betterReport
import com.magi.app.v6.withSchedule
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = args[0]
    val out = File(args[1])
    val secondsBudget = args.getOrNull(2)?.toInt() ?: 60
    val workers = args.getOrNull(3)?.toInt() ?: 1
    val fixtures = System.getenv("MAGI_HO_FIXTURES")?.split(',')?.filter { it.isNotBlank() }
        ?: listOf("golden_state.json", "sample_state_v6.json", "sept2026_state.json", "oct2026_grid_state.json")
    val seedList = System.getenv("MAGI_HO_SEEDS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(1, 2, 3)
    val head = "fixture,seed,arm,elapsedMs,hard,weightedScore,total,rep,hash\n"
    val w = out.bufferedWriter().apply { write(head) }
    val wb = File(out.path.removeSuffix(".csv") + ".best.csv").bufferedWriter().apply { write(head) }
    val wr = File(out.path.removeSuffix(".csv") + ".base.csv").bufferedWriter().apply {
        write("fixture,seed,elapsedMs,hard,weightedScore,total,draftHard,draftWeighted,hash\n")
    }
    for (fname in fixtures) {
        val f = File(resDir, fname)
        if (!f.exists()) { System.err.println("skip missing $fname"); continue }
        val st = StateParser.parse(f.readText())!!
        val fx = fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")
        for (seed in seedList) {
            fun opt(sched: Array<IntArray>, s: Long) = runBlocking {
                val t0 = System.currentTimeMillis()
                val r = V6FinalPort.handleOptimize(st, sched, secondsRaw = secondsBudget, workers = workers, allowImpossible = true, seed = s)
                r to (System.currentTimeMillis() - t0)
            }
            val (base, bMs) = opt(st.schedule.map { it.toIntArray() }.toTypedArray(), 1000L + seed)
            val draft = runBlocking { V6FinalPort.handleSmartInitial(st.withSchedule(base.schedule), allowImpossible = true) }
            val (a, aMs) = opt(base.schedule.map { it.copyOf() }.toTypedArray(), 2000L + seed)
            val (b, bbMs) = opt(draft.schedule.map { it.copyOf() }.toTypedArray(), 2000L + seed)
            fun row(arm: String, r: V6FinalPort.ActionResult, ms: Long) =
                "$fx,$seed,$arm,$ms,${r.report.hard},${r.report.weightedScore},${r.report.total},1,${r.schedule.contentDeepHashCode().toUInt().toString(16)}\n"
            val best = if (betterReport(b.report, a.report)) b to bbMs else a to aMs
            w.write(row("off", a, aMs)); w.write(row("on", b, bbMs)); w.flush()
            wb.write(row("off", a, aMs)); wb.write(row("on", best.first, best.second)); wb.flush()
            wr.write("$fx,$seed,$bMs,${base.report.hard},${base.report.weightedScore},${base.report.total},${draft.report.hard},${draft.report.weightedScore}," +
                "${base.schedule.contentDeepHashCode().toUInt().toString(16)}\n"); wr.flush()
            System.err.println("$fx seed=$seed base=${base.report.hard}/${base.report.weightedScore} A=${a.report.hard}/${a.report.weightedScore}/${a.report.total} " +
                "draft=${draft.report.hard} B=${b.report.hard}/${b.report.weightedScore}/${b.report.total}")
        }
    }
    w.close(); wb.close(); wr.close()
}
