package probe
// [2026-09-21/backlog#35] `V6FinalPort.handleOptimize`のExtraRefine省略条件
// （`extraRefineRequirePostHardDrop`、3.600.0実装・既定OFF）をA/Bする。既存のLoopBench.kt/PortfolioBudgetBench.kt
// は`runPostOptimization`より下の層しか呼ばず、ExtraRefine自体はhandleOptimize内部の別ステージなので
// この層を直接呼ぶ新規ハーネスが要る。
import com.magi.app.model.StateParser
import com.magi.app.v6.EjectionChainPipeline
import com.magi.app.v6.PolishGate
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.WishFloorMode
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val resDir = args[0]
    val out = File(args[1])
    val seeds = args.getOrNull(2)?.toInt() ?: 5
    val secondsBudget = args.getOrNull(3)?.toInt() ?: 60
    val workers = args.getOrNull(4)?.toInt() ?: 4
    // [2026-10-25] "tol"＝じっくりの条件で apt/fair の 6% 許容だけ ON/OFF（実機ログで許容の採用が最終盤面に残らなかった件）。
    // [2026-10-08] MAGI_HO_FEATURE: 既定（空）＝ExtraRefine 省略条件の A/B（旧来）。"c3nwall"＝on 腕で c3n 壁の短縮を外す（off 腕は短縮あり＝3.672.0 までの既定）
    //   （PolishGate.c3nWallShortStall=false、通常閾値で粘る）。"head"＝on 腕で HEAD の壁判定（c3nWallLegacy=true）と
    //   後期演算の試行中の停止確認を切る（lateOpStopPropagation=false、入口の確認は残る）に戻す（docs/stall_escape.md §5.5）。
    //   "deep"＝on 腕で経験的な c3n 壁の 1 手探索による反証（PolishGate.c3nWallDeepCheck）。"adaptive"＝on 腕で適応閾値（PolishGate.adaptiveStall）。"floorup0"＝on 腕で上限0込みの希望衝突の床で頭打ち（PolishGate.floorCountsUpperZero）。"wishfloor"＝3 腕 off/E0A/E0B（handleOptimize の wishFloorMode）。MAGI_HO_FIXTURES でフィクスチャをカンマ区切りで絞る。
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
    // [2026-10-08/測定の精度] MAGI_HO_REPEATS: 同じ seed・同じ腕を k 回繰り返す（workers>1 は壁時計に依存して非決定なので、
    //   腕の差を読む前に揺れを測る）。CSV の末尾に rep 列を足す（集計は tools/loop/ho_stats.py）。
    val repeats = (System.getenv("MAGI_HO_REPEATS")?.toIntOrNull() ?: 1).coerceAtLeast(1)
    // [3.666.0/backlog #48] "budget"＝on 腕だけ予算を MAGI_HO_ON_SECONDS（既定 300）にする（60 秒モデルの品質の比較 V1）。
    //   "earlystall"＝on 腕で通常の停滞閾値の割合 PolishGate.normalStallFraction を MAGI_HO_FRACTION（既定 0.5）にする（同じ全体時間で早めに仕上げへ移る案 T1）。
    val onSeconds = System.getenv("MAGI_HO_ON_SECONDS")?.toIntOrNull() ?: 300
    val onFraction = System.getenv("MAGI_HO_FRACTION")?.toDoubleOrNull() ?: 0.5

    val w = java.io.FileWriter(out, false).buffered()
    w.write("fixture,seed,arm,elapsedMs,hard,weightedScore,total,rep,hash\n")
    w.flush()

    for (fname in fixtures) {
        val f = File(resDir, fname)
        if (!f.exists()) { System.err.println("skip missing $fname"); continue }
        val st = StateParser.parse(f.readText())!!
        for (seed in seedList) for (rep in 1..repeats) {
            val arms = if (feature == "wishfloor") listOf(WishFloorMode.OFF, WishFloorMode.E0A, WishFloorMode.E0B) else listOf(null, null)
            for ((ai, wfm) in arms.withIndex()) {
                val armOn = ai > 0
                // "floorstall"＝on 腕で必須違反の下限到達による短縮を外す（PolishGate.floorShortStall=false、通常閾値で粘る）。
                PolishGate.floorShortStall = !(feature == "floorstall" && armOn)
                // "deep"＝on 腕で経験的な c3n 壁を 1 手探索で反証する（PolishGate.c3nWallDeepCheck、根拠の段階化）。off 腕＝現行。
                PolishGate.c3nWallDeepCheck = feature == "deep" && armOn
                // "ejpipe*"＝on 腕で玉突き連鎖パイプライン（BOTH／C1）。"ejpipefull"＝off 腕が従来の全族の常時フル、on 腕がパイプライン BOTH。
                PolishGate.allFamilyEjectionChain = feature == "ejpipefull" && !armOn
                PolishGate.ejectionPipelineFocus = when {
                    armOn && (feature == "ejpipe" || feature == "ejpipefull" || feature == "ejpipeend") -> EjectionChainPipeline.Focus.BOTH
                    armOn && feature == "ejpipec1" -> EjectionChainPipeline.Focus.C1
                    feature.startsWith("ejpipe") -> EjectionChainPipeline.Focus.OFF
                    // [3.656.0] それ以外の機能は既定（パイプライン BOTH・修復の後）のまま測る。
                    else -> EjectionChainPipeline.Focus.BOTH
                }
                PolishGate.normalStallFraction = if (feature == "earlystall" && armOn) onFraction else 0.9
                // [2026-10-25] "tol"＝じっくりの他 3 つ（粘り強く・禁止連続の崩し範囲・回数連鎖研磨）と仕上げ最適化は両腕 ON、on 腕だけ apt/fair の 6% 許容。
                if (feature == "tol") {
                    PolishGate.combineExhaustPairs = true; PolishGate.wideC3nBreakDays = true; PolishGate.countChainPolish = true
                    PolishGate.aptFairSoftTolerance = armOn
                }
                val t0 = System.currentTimeMillis()
                val res = runBlocking {
                    V6FinalPort.handleOptimize(
                        st, st.schedule.map { it.toIntArray() }.toTypedArray(),
                        secondsRaw = if (feature == "budget" && armOn) onSeconds else secondsBudget, workers = workers, allowImpossible = true,
                        softPolish = feature == "tol",
                        extraRefineRequirePostHardDrop = feature == "" && armOn,
                        wishFloorMode = wfm ?: WishFloorMode.OFF,
                        seed = 1000L + seed,
                    )
                }
                val elapsed = System.currentTimeMillis() - t0
                val arm = wfm?.name ?: if (armOn) "on" else "off"
                val hash = res.schedule.contentDeepHashCode().toUInt().toString(16)
                val line = "${fname.removeSuffix("_state.json").removeSuffix("_state_v6.json")},$seed,$arm,$elapsed," +
                    "${res.report.hard},${res.report.weightedScore},${res.report.total},$rep,$hash"
                w.write(line + "\n"); w.flush()
                System.err.println(line)
                if (logTags.isNotEmpty() && logFile != null) {
                    val picked = res.logs.map { it.toString() }.filter { l -> logTags.any { l.contains("tag=$it") } }
                    logFile.appendText("### $fname seed=$seed rep=$rep arm=$arm elapsed=$elapsed hard=${res.report.hard} w=${res.report.weightedScore} t=${res.report.total} " +
                        "bd=${res.report.breakdown.filterValues { it > 0 }.toSortedMap()}\n" +
                        picked.joinToString("\n") + "\n")
                }
            }
        }
    }
    w.close()
}
