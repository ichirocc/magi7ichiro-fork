package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 膜の探索（細胞 LNS、`docs/membrane_search.md` §10・§11）。頭打ちの盤面で、細胞＝職員の集合（個人の違反が重い職員＋同じグループの職員）×全期間を
 * 自由にし、細胞の中だけで同日の入れ替えと 1 セルの置き換えを焼きなましで回す。細胞の最良が盤面より良ければ（`betterReport`）書き戻す。
 * 希望固定・手動固定（`wishLocked`）と置けないシフト（`mayPlaceAt`）は動かさず、書き戻す前に `exactPinRegression` も確かめる。
 */
object MembraneCellLns {

    data class Params(
        val maxMillis: Long = 8_000L,
        val cellStaff: Int = 5,
        val itersPerCell: Int = 60_000,
        val maxCells: Int = 64,
        val seed: Long = 1L,
        val tempStart: Double = 40.0,
        val tempEnd: Double = 0.5,
        /** 回数で止める（ベンチ・再現用）。true なら maxMillis を見ず、maxCells 個の細胞で止める。 */
        val deterministic: Boolean = false,
    )

    class Result(val newSchedule: Array<IntArray>, val applied: Int, val cellsTried: Int, val logs: List<MirrorLog>)

    fun apply(state: MagiState, init: Array<IntArray>, params: Params = Params(), shouldStop: () -> Boolean = { false }): Result {
        val p = cachedProblem(state)
        val t0 = EngineClock.nowMs()
        fun out() = shouldStop() || (!params.deterministic && EngineClock.nowMs() - t0 >= params.maxMillis)
        var best = init.copy2D()
        var bestRep = UnifiedViolationChecker.check(state, best)
        val rng = JavaLcg(params.seed)
        val kotlin = lazy { DeltaEvaluator(p) }   // C++ が使えない・番兵が閉じたときだけ作る
        val handle = if (NativeGate.usable) runCatching { NativeEval.createHandle(p) }.getOrDefault(0L) else 0L
        var applied = 0; var tried = 0
        val logs = ArrayList<MirrorLog>()
        val startRep = bestRep
        try {

        while (tried < params.maxCells && !out()) {
            val order = staffOrder(p, bestRep)
            if (order.isEmpty()) break   // 職員 0 人では tried が進まない
            for (seedStaff in order) {
                if (tried >= params.maxCells || out()) break
                tried++
                val cell = cellOf(p, seedStaff, order, params.cellStaff)
                val cand = solveCell(p, handle, kotlin, best, cell, params, rng, ::out) ?: continue
                if (exactPinRegression(p, best, cand)) continue
                val rep = UnifiedViolationChecker.check(state, cand)
                if (betterReport(rep, bestRep)) {
                    best = cand; bestRep = rep; applied++
                }
            }
        }
        } finally {
            if (handle != 0L) NativeBridge.nativeDestroyProblem(handle)
        }
        logs.add(MirrorLog(level = "I", tag = "MembraneCellLns",
            message = "膜の細胞 LNS: 必須 ${startRep.hard}->${bestRep.hard} 合計 ${startRep.total}->${bestRep.total} 重み ${startRep.weightedScore.toLong()}->${bestRep.weightedScore.toLong()} 採用${applied} 細胞${tried} ${EngineClock.nowMs() - t0}ms"))
        return Result(best, applied, tried, logs)
    }

    /** OWA の順: 個人の違反（盤面の印と回数の印の数）が多い職員から。同数は添字順。2 種の印は別々に数える（同じ文字列の鍵でも別の印）。 */
    private fun staffOrder(p: Problem, rep: ViolationReport): List<Int> {
        val load = IntArray(p.S)
        for (key in rep.violations.keys.asSequence() + rep.countViolations.keys.asSequence()) { val i = key.substringBefore(',').toIntOrNull() ?: continue; if (i in 0 until p.S) load[i]++ }
        return (0 until p.S).sortedWith(compareBy({ -load[it] }, { it }))
    }

    /** 細胞の職員: 起点の職員＋同じグループの職員を OWA 順に、足りなければ他のグループから。 */
    private fun cellOf(p: Problem, seed: Int, order: List<Int>, size: Int): IntArray {
        val g = p.sgrp[seed]
        val same = order.filter { it != seed && p.sgrp[it] == g }
        val other = order.filter { it != seed && p.sgrp[it] != g }
        return (listOf(seed) + same + other).take(size.coerceAtMost(p.S)).toIntArray()
    }

    /**
     * 細胞の中の焼きなまし。手は (a) 同じ日の細胞内 2 名の入れ替え（日の人数を保つ）、(b) 1 セルを置ける別のシフトへ。
     * 盤面の score（必須を桁上げした重み付き和、`DeltaEvaluator`）が下がった最良を返す。改善が無ければ null。
     */
    private fun solveCell(
        p: Problem, handle: Long, kotlin: Lazy<DeltaEvaluator>, board: Array<IntArray>, cell: IntArray, params: Params, rng: JavaLcg, out: () -> Boolean,
    ): Array<IntArray>? {
        var freeCount = 0
        for (i in cell) for (j in 0 until p.T) if (!p.wishLocked(i, j)) freeCount++
        if (freeCount < 2) return null
        val cool = Math.pow(params.tempEnd / params.tempStart, 1.0 / params.itersPerCell.coerceAtLeast(1))
        if (handle != 0L && NativeGate.usable) {
            val rngBefore = rng.state
            when (val r = solveCellNative(p, handle, board, cell, params, cool, rng, out)) {
                is NativeCell.Done -> return r.board
                is NativeCell.Failed -> { NativeGate.disable(r.why); rng.state = rngBefore }
            }
        }
        return solveCellKotlin(p, kotlin.value, board, cell, params, cool, rng, out)
    }

    private sealed class NativeCell {
        class Done(val board: Array<IntArray>?) : NativeCell()
        class Failed(val why: String) : NativeCell()
    }

    /**
     * [solveCellKotlin] と同じ焼きなましを C++ で（`MembraneCellN`）。止める確認は Kotlin と同じく 1024 反復ごと。番兵は 2 段:
     * C++ の自己整合（差分の点数＝全体評価）と、最良の盤面の Kotlin 全体評価との照合。崩れたら呼び出し側が同じ乱数状態から Kotlin でやり直す。
     */
    private fun solveCellNative(
        p: Problem, handle: Long, board: Array<IntArray>, cell: IntArray, params: Params, cool: Double, rng: JavaLcg, out: () -> Boolean,
    ): NativeCell {
        val h = NativeBridge.nativeMembraneCreate(handle, NativeEval.flatten(board), cell, rng.state, params.tempStart, cool)
        if (h == 0L) return NativeCell.Failed("膜の細胞の生成NG")
        try {
            val iters = params.itersPerCell
            var it = 0
            while (it < iters) {
                if (out()) break
                val n = minOf(CHECK_EVERY, iters - it)
                val ret = NativeBridge.nativeMembraneRun(h, n, 0)
                if (!NativeGate.chunkConsistent(ret, 5)) return NativeCell.Failed("膜の細胞の実行NG")
                it += n
            }
            val fin = NativeBridge.nativeMembraneRun(h, 0, 1)
            if (!NativeGate.chunkConsistent(fin, 5)) return NativeCell.Failed("膜の細胞の整合性NG(status=${fin.getOrNull(0)})")
            rng.state = fin[1]
            if (fin[4] != 1L) return NativeCell.Done(null)
            val flat = IntArray(p.S * p.T)
            if (!NativeBridge.nativeMembraneRead(h, flat)) return NativeCell.Failed("膜の細胞の読み出しNG")
            val best = NativeEval.unflatten(flat, p.S, p.T)
            val mismatch = NativeGate.parityMismatch(fin[3]) { Evaluator(p).fullEval(best) }
            if (mismatch != null) return NativeCell.Failed("膜の細胞のKotlin照合NG(native=${fin[3]} kotlin=$mismatch)")
            return NativeCell.Done(best)
        } finally {
            NativeBridge.nativeMembraneDestroy(h)
        }
    }

    private fun solveCellKotlin(
        p: Problem, de: DeltaEvaluator, board: Array<IntArray>, cell: IntArray, params: Params, cool: Double, rng: JavaLcg, out: () -> Boolean,
    ): Array<IntArray>? {
        val T = p.T; val K = p.K
        val work = board.copy2D()
        de.reset(work)
        val start = de.score()
        var cur = start
        var bestScore = start
        var bestBoard: Array<IntArray>? = null
        val free = ArrayList<IntArray>()
        for (i in cell) for (j in 0 until T) if (!p.wishLocked(i, j)) free.add(intArrayOf(i, j))
        val unit = SCORE_HARD_UNIT
        val iters = params.itersPerCell
        var temp = params.tempStart
        for (it in 0 until iters) {
            if (it % CHECK_EVERY == 0 && out()) break
            temp *= cool
            if (rng.nextInt(4) != 0) {
                val j = rng.nextInt(T)
                val a = cell[rng.nextInt(cell.size)]; val b = cell[rng.nextInt(cell.size)]
                if (a == b || p.wishLocked(a, j) || p.wishLocked(b, j)) continue
                val ka = work[a][j]; val kb = work[b][j]
                if (ka == kb || !p.mayPlaceAt(a, j, kb) || !p.mayPlaceAt(b, j, ka)) continue
                de.apply(a, j, kb)
                val s = de.apply(b, j, ka)
                if (accept(s - cur, temp, unit, rng)) { work[a][j] = kb; work[b][j] = ka; cur = s }
                else { de.apply(b, j, kb); cur = de.apply(a, j, ka) }
            } else {
                val c = free[rng.nextInt(free.size)]; val i = c[0]; val j = c[1]
                val k = rng.nextInt(K); val old = work[i][j]
                if (k == old || !p.mayPlaceAt(i, j, k)) continue
                val s = de.apply(i, j, k)
                if (accept(s - cur, temp, unit, rng)) { work[i][j] = k; cur = s } else cur = de.apply(i, j, old)
            }
            if (cur < bestScore) {
                bestScore = cur
                // 細胞の外の行は盤面のまま＝2 回目からは細胞の行だけ写す（改善のたびに盤面全体を作らない）。
                val bb = bestBoard ?: work.copy2D().also { bestBoard = it }
                for (i in cell) System.arraycopy(work[i], 0, bb[i], 0, T)
            }
        }
        return bestBoard
    }

    private const val CHECK_EVERY = 1024

    private fun accept(delta: Long, temp: Double, unit: Long, rng: JavaLcg): Boolean {
        if (delta <= 0) return true
        if (delta >= unit) return false   // 必須が増える手は取らない
        return rng.nextDouble() < Math.exp(-delta / temp)
    }
}
