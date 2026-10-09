package probe

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File

/**
 * [3.652.0/外部レビュー「組み合わせの選び方が十分かの検証が不足」] 取り逃し監査（測定のみ・探索の動きは変えない）。
 * 実データの最終盤面で小さな近傍を全列挙し、
 * 正式な採否（betterReport＝必須→重み付き→件数）で改善になる手が残っていないかを数える。
 *  N1＝1 セルの変更、N2＝同じ日の 2 人の入れ替え、N3＝2 人の連続 L 日（2..maxLen）の交換（日ごとの人数は変わらない）。
 * 固定条件は最適化器と同じ: 希望・手動固定のセルは動かさない（wishLocked）、置けないシフト・拡張希望の禁止へは置かない（mayPlaceAt）。
 * N3 は窓の中の動かせない日を据え置く（AdaptiveBlockSwapPolish と同じ）。同じ 2 人で入れ替わる日の集合が同じ候補は 1 回だけ数える。
 * anchored＝改善手が動かすセルのどれかが今の違反セルに当たる（違反を起点に候補を作る演算子が拾える形か）の件数。
 * 盤面の作り方 mode: det＝LoopBench と同じ（V5 の初期解＋後処理チェーン、決定論モード＝回数上限で切れる）、
 *  ho＝本番の入口 `V6FinalPort.handleOptimize`（時間制、[sec] 秒・[workers] 並列）。2026-10-07 の局所降下の否決は
 *  「決定論モードの勝ちは回数上限で切られる条件だけの効果」だったので、取り逃しの判定は ho の盤面で読む。
 */
private class Tally(val nb: String) {
    var evaluated = 0; var improving = 0; var anchored = 0
    var best: ViolationReport? = null; var example = ""
    val t0 = System.nanoTime()
}

private fun initialBoard(st: MagiState, seed: Long): Array<IntArray> = kotlinx.coroutines.runBlocking {
    V6NativeOptimizer.optimize(st, options = V6OptimizerOptions(algorithm = V6Algorithm.V5, totalBudgetSec = 4, workers = 1,
        softPolish = false, restarts = 0, seed = seed, postPolish = false)).schedule
}

fun main(args: Array<String>) {
    val resDir = File(args[0]); val out = File(args[1])
    val seeds = args.getOrNull(2)?.toInt() ?: 1
    val maxLen = args.getOrNull(3)?.toInt() ?: 7
    val only = args.getOrNull(4) ?: ""
    val mode = args.getOrNull(5) ?: "det"
    val sec = args.getOrNull(6)?.toInt() ?: 120
    val workers = args.getOrNull(7)?.toInt() ?: 4
    val files = listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json", "oct2026_grid_state.json")
    val w = out.bufferedWriter()
    w.write("case,mode,seed,hard,weighted,nbhd,evaluated,improving,anchored,bestHard,bestWeighted,sec,example\n")
    for ((n, f) in files.withIndex()) {
        val id = "r%02d-%s".format(n + 1, f.removeSuffix("_state.json").removeSuffix("_state_v6.json"))
        if (only.isNotEmpty() && !id.contains(only)) continue
        val st = StateParser.parse(File(resDir, f).readText())
        val init = if (mode == "det") initialBoard(st, 0x5EA1L + n) else null
        val p = Problem(st)
        val names = st.staff.map { it.name }
        val syms = st.shifts.map { it.kigou }
        for (seed in 0 until seeds) {
            val work = if (init != null) {
                V6HotfixPasses.runPostOptimization(st, init.copy2D(), "probe", seed = seed.toLong() * 1000003L + 17,
                    deadlineMs = EngineClock.nowMs() + 40_000L, params = V6HotfixPasses.PostOptimizationParams(deterministic = true)).schedule.copy2D()
            } else kotlinx.coroutines.runBlocking {
                V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = sec, workers = workers,
                    allowImpossible = true, seed = 1000L + seed + 1).schedule.copy2D()
            }
            val base = UnifiedViolationChecker.check(st, work)
            val vioCells = base.violations.keys
            System.err.println("$id $mode seed=$seed hard=${base.hard} weighted=${base.weightedScore} ${base.breakdown.filterValues { it > 0 }}")
            fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
            fun consider(t: Tally, cells: List<Pair<Int, Int>>, desc: () -> String) {
                t.evaluated++
                val rep = UnifiedViolationChecker.check(st, work)
                if (!betterReport(rep, base)) return
                t.improving++
                if (cells.any { (i, j) -> "$i,$j" in vioCells }) t.anchored++
                if (t.best == null || betterReport(rep, t.best!!)) { t.best = rep; t.example = desc() + " " + rep.breakdown.filter { (k, v) -> v != (base.breakdown[k] ?: 0) }.map { (k, v) -> "$k ${base.breakdown[k] ?: 0}→$v" } }
            }
            val tallies = ArrayList<Tally>()
            // N1: 1 セルの変更
            run {
                val t = Tally("N1")
                for (i in 0 until p.S) for (j in 0 until p.T) {
                    if (!movable(i, j)) continue
                    val cur = work[i][j]
                    for (k in 0 until p.K) {
                        if (k == cur || !p.mayPlaceAt(i, j, k)) continue
                        work[i][j] = k
                        consider(t, listOf(i to j)) { "${names[i]} ${j + 1}日 ${syms[cur]}→${syms[k]}" }
                        work[i][j] = cur
                    }
                }
                tallies.add(t)
            }
            fun swapOk(a: Int, b: Int, d: Int) = movable(a, d) && movable(b, d) && work[a][d] != work[b][d] &&
                p.mayPlaceAt(a, d, work[b][d]) && p.mayPlaceAt(b, d, work[a][d])
            fun swap(a: Int, b: Int, d: Int) { val x = work[a][d]; work[a][d] = work[b][d]; work[b][d] = x }
            // N2: 同じ日の 2 人の入れ替え
            run {
                val t = Tally("N2")
                for (j in 0 until p.T) for (a in 0 until p.S) for (b in a + 1 until p.S) {
                    if (!swapOk(a, b, j)) continue
                    swap(a, b, j)
                    consider(t, listOf(a to j, b to j)) { "${names[a]}↔${names[b]} ${j + 1}日" }
                    swap(a, b, j)
                }
                tallies.add(t)
            }
            // N3: 2 人の連続 L 日の交換（動かせない日は据え置き、入れ替わる日が 2 日以上）
            run {
                val t = Tally("N3")
                for (a in 0 until p.S) for (b in a + 1 until p.S) {
                    val seen = HashSet<Long>()
                    for (len in 2..maxLen) for (s in 0..p.T - len) {
                        var mask = 0L; var cnt = 0
                        for (d in s until s + len) if (swapOk(a, b, d)) { mask = mask or (1L shl d); cnt++ }
                        if (cnt < 2 || !seen.add(mask)) continue
                        val days = (s until s + len).filter { mask and (1L shl it) != 0L }
                        for (d in days) swap(a, b, d)
                        consider(t, days.flatMap { listOf(a to it, b to it) }) { "${names[a]}↔${names[b]} ${days.joinToString("・") { "${it + 1}" }}日" }
                        for (d in days) swap(a, b, d)
                    }
                }
                tallies.add(t)
            }
            for (t in tallies) {
                val sec = (System.nanoTime() - t.t0) / 1e9
                val b = t.best
                w.write("$id,$mode,$seed,${base.hard},${base.weightedScore},${t.nb},${t.evaluated},${t.improving},${t.anchored},${b?.hard ?: ""},${b?.weightedScore ?: ""},${"%.1f".format(sec)},\"${t.example.replace("\"", "'")}\"\n")
                w.flush()
                System.err.println("  ${t.nb}: evaluated=${t.evaluated} improving=${t.improving} anchored=${t.anchored} best=${b?.hard}/${b?.weightedScore} ${"%.1f".format(sec)}s ${t.example}")
            }
        }
    }
    w.close()
}
