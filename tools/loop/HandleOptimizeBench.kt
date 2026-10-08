package probe
// [2026-09-21/backlog#35] `V6FinalPort.handleOptimize`のExtraRefine省略条件
// （`extraRefineRequirePostHardDrop`、3.600.0実装・既定OFF）をA/Bする。既存のLoopBench.kt/PortfolioBudgetBench.kt
// は`runPostOptimization`より下の層しか呼ばず、ExtraRefine自体はhandleOptimize内部の別ステージなので
// この層を直接呼ぶ新規ハーネスが要る。
import com.magi.app.model.StateParser
import com.magi.app.v6.PolishGate
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = args[0]
    val out = File(args[1])
    val seeds = args.getOrNull(2)?.toInt() ?: 5
    val secondsBudget = args.getOrNull(3)?.toInt() ?: 60
    val workers = args.getOrNull(4)?.toInt() ?: 4
    // [2026-10-08] MAGI_HO_FEATURE: 既定（空）＝ExtraRefine 省略条件の A/B（旧来）。"c3nwall"＝on 腕で c3n 壁の短縮を外す
    //   （PolishGate.c3nWallShortStall=false、通常閾値で粘る）。"head"＝on 腕で HEAD の壁判定（c3nWallLegacy=true）と
    //   後期演算の試行中の停止確認を切る（lateOpStopPropagation=false、入口の確認は残る）に戻す（docs/stall_escape.md §5.5）。
    //   MAGI_HO_FIXTURES でフィクスチャをカンマ区切りで絞る。
    val feature = System.getenv("MAGI_HO_FEATURE") ?: ""
    val fixtures = System.getenv("MAGI_HO_FIXTURES")?.split(',')?.filter { it.isNotBlank() }
        ?: listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json")
    // [2026-10-08/診断] MAGI_HO_SEEDS: seed をカンマ区切りで指定（省略時は 1..seeds）。MAGI_HO_LOGTAGS と MAGI_HO_LOGFILE:
    //   指定タグ（例 Watchdog,EarlyStop,ExtraRefine）のエンジンログ行を run ごとにファイルへ追記する（悪化した seed の
    //   停止理由を読むため）。CSV は不変。
    val seedList = System.getenv("MAGI_HO_SEEDS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
        ?: (1..seeds).toList()
    val logTags = System.getenv("MAGI_HO_LOGTAGS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val logFile = System.getenv("MAGI_HO_LOGFILE")?.takeIf { it.isNotBlank() }?.let { File(it) }

    val w = java.io.FileWriter(out, false).buffered()
    w.write("fixture,seed,arm,elapsedMs,hard,weightedScore,total\n")
    w.flush()

    for (fname in fixtures) {
        val f = File(resDir, fname)
        if (!f.exists()) { System.err.println("skip missing $fname"); continue }
        val st = StateParser.parse(f.readText())!!
        for (seed in seedList) {
            for (armOn in listOf(false, true)) {
                PolishGate.c3nWallShortStall = !(feature == "c3nwall" && armOn)
                // "head"＝HEAD の壁判定と後期演算の試行中の停止確認を切る（on 腕。入口の確認は残る）。off 腕＝現行。
                PolishGate.c3nWallLegacy = feature == "head" && armOn
                PolishGate.lateOpStopPropagation = !(feature == "head" && armOn)
                val t0 = System.currentTimeMillis()
                val res = runBlocking {
                    V6FinalPort.handleOptimize(
                        st, st.schedule.map { it.toIntArray() }.toTypedArray(),
                        secondsRaw = secondsBudget, workers = workers, allowImpossible = true,
                        extraRefineRequirePostHardDrop = feature == "" && armOn,
                        seed = 1000L + seed,
                    )
                }
                val elapsed = System.currentTimeMillis() - t0
                val arm = if (armOn) "on" else "off"
                val line = "${fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")},$seed,$arm,$elapsed," +
                    "${res.report.hard},${res.report.weightedScore},${res.report.total}"
                w.write(line + "\n"); w.flush()
                System.err.println(line)
                if (logTags.isNotEmpty() && logFile != null) {
                    val picked = res.logs.map { it.toString() }.filter { l -> logTags.any { l.contains("tag=$it") } }
                    logFile.appendText("### $fname seed=$seed arm=$arm elapsed=$elapsed hard=${res.report.hard} w=${res.report.weightedScore} t=${res.report.total}\n" +
                        picked.joinToString("\n") + "\n")
                }
            }
        }
    }
    w.close()
}
