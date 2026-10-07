package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.random.Random

/**
 * 後処理チェーンの前に置く貪欲な局所降下（`PolishGate.prePostDescent`、既定 OFF）。
 * 手は 1 セル付け替え・同日 2 者交換・同一職員 2 日交換をランダムに引き、DeltaEvaluator で悪化する手を先に落とし、
 * 残りを正式チェッカーで「改善」または「同点（連続 [maxSideways] 回まで）」なら受理する。希望固定セルは触らず、
 * 置くシフトは `mayPlace`、厳密ピンは `exactPinRegression` で守る。返すのは途中の最良盤面＝入力より悪くならない。
 */
internal object PrePostDescent {
    data class Result(
        val newSchedule: Array<IntArray>,
        val report: ViolationReport,
        val tried: Int,
        val evaluated: Int,
        val improved: Int,
        val sideways: Int,
        val logs: List<MirrorLog>,
    )

    fun apply(
        state: MagiState, schedule: Array<IntArray>,
        maxMillis: Long, maxDraws: Int = Int.MAX_VALUE, maxEvaluations: Int = Int.MAX_VALUE, maxSideways: Int = 50,
        seed: Long = 0L, shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false,
    ): Result {
        val p = Problem(state, quantitativeRangeEval)
        val cur = normalizeSchedule(schedule, p)
        val inRep = UnifiedViolationChecker.check(state, cur, quantitativeRangeEval)
        fun done(tried: Int, ev: Int, imp: Int, side: Int, b: Array<IntArray>, r: ViolationReport, note: String = ""): Result {
            val msg = "後処理前の局所降下: 試行${tried} 正式評価${ev} 改善${imp} 同点${side} / HARD ${inRep.hard}->${r.hard} weighted ${inRep.weightedScore}->${r.weightedScore} total ${inRep.total}->${r.total}$note"
            return Result(b, r, tried, ev, imp, side, listOf(MirrorLog(tag = "PrePostDescent", message = msg)))
        }
        if (cur.any { row -> row.any { it < 0 } }) return done(0, 0, 0, 0, cur, inRep, " [未割当セルありのため省略]")
        val free = ArrayList<Int>()
        for (i in 0 until p.S) for (j in 0 until p.T) if (!p.wishLocked(i, j)) free.add(i * p.T + j)
        if (free.isEmpty()) return done(0, 0, 0, 0, cur, inRep)

        val de = DeltaEvaluator(p)
        de.reset(cur)
        var curKey = de.reportKey()
        var curRep = inRep
        var best = cur.copy2D(); var bestRep = inRep
        val rnd = Random(seed)
        val t0 = EngineClock.nowMs()
        var tried = 0; var evaluated = 0; var improved = 0; var sidewaysTotal = 0; var sidewaysRun = 0
        val mi = IntArray(2); val mj = IntArray(2); val mk = IntArray(2); val mOld = IntArray(2)
        while (tried < maxDraws && evaluated < maxEvaluations && !shouldStop() && EngineClock.nowMs() - t0 < maxMillis) {
            tried++
            val cell = free[rnd.nextInt(free.size)]
            val i0 = cell / p.T; val j0 = cell % p.T
            var n = 0
            when (rnd.nextInt(3)) {
                0 -> {
                    val pl = p.allowedShiftsForStaff(i0); if (pl.isEmpty()) continue
                    val k = pl[rnd.nextInt(pl.size)]; if (k == cur[i0][j0]) continue
                    if (p.extBanned(i0, j0, k)) continue   // 拡張希望の禁止へは置かない
                    mi[0] = i0; mj[0] = j0; mk[0] = k; n = 1
                }
                1 -> {
                    val b = rnd.nextInt(p.S); if (b == i0 || p.wishLocked(b, j0)) continue
                    val ka = cur[i0][j0]; val kb = cur[b][j0]
                    if (ka == kb || !p.mayPlace(i0, kb) || !p.mayPlace(b, ka)) continue
                    if (p.extBanned(i0, j0, kb) || p.extBanned(b, j0, ka)) continue
                    mi[0] = i0; mj[0] = j0; mk[0] = kb; mi[1] = b; mj[1] = j0; mk[1] = ka; n = 2
                }
                else -> {
                    val j2 = rnd.nextInt(p.T); if (j2 == j0 || p.wishLocked(i0, j2)) continue
                    val ka = cur[i0][j0]; val kb = cur[i0][j2]
                    if (ka == kb || !p.mayPlace(i0, kb) || !p.mayPlace(i0, ka)) continue
                    if (p.extBanned(i0, j0, kb) || p.extBanned(i0, j2, ka)) continue
                    mi[0] = i0; mj[0] = j0; mk[0] = kb; mi[1] = i0; mj[1] = j2; mk[1] = ka; n = 2
                }
            }
            for (m in 0 until n) { mOld[m] = cur[mi[m]][mj[m]]; de.apply(mi[m], mj[m], mk[m]) }
            fun undoDelta() { for (m in n - 1 downTo 0) de.apply(mi[m], mj[m], mOld[m]) }
            // 事前判定は正式比較と同じ辞書式（必須件数→重み付き→総数）。素の score は必須族の重みを含まない（OPT-01）。
            val key = de.reportKey()
            if (compareReportKey(key, curKey) > 0) { undoDelta(); continue }
            val before = cur.copy2D()
            for (m in 0 until n) cur[mi[m]][mj[m]] = mk[m]
            val rep = UnifiedViolationChecker.check(state, cur, quantitativeRangeEval)
            evaluated++
            val better = betterReport(rep, curRep)
            val equal = !better && !betterReport(curRep, rep)
            val accept = when {
                better -> adoptionGate(p, before, cur, rep, curRep).accepted
                equal && sidewaysRun < maxSideways -> !exactPinRegression(p, before, cur)
                else -> false
            }
            if (!accept) {
                for (m in 0 until n) cur[mi[m]][mj[m]] = mOld[m]
                undoDelta(); continue
            }
            curRep = rep; curKey = key
            if (better) { improved++; sidewaysRun = 0 } else { sidewaysTotal++; sidewaysRun++ }
            if (betterReport(rep, bestRep)) { best = cur.copy2D(); bestRep = rep }
        }
        return done(tried, evaluated, improved, sidewaysTotal, best, bestRep, " ${EngineClock.nowMs() - t0}ms")
    }
}
