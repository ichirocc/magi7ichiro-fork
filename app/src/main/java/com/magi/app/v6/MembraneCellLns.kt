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
        val rng = java.util.Random(params.seed)
        val de = DeltaEvaluator(p)
        var applied = 0; var tried = 0
        val logs = ArrayList<MirrorLog>()
        val startRep = bestRep

        while (tried < params.maxCells && !out()) {
            val order = staffOrder(p, bestRep)
            for (seedStaff in order) {
                if (tried >= params.maxCells || out()) break
                tried++
                val cell = cellOf(p, seedStaff, order, params.cellStaff)
                val cand = solveCell(p, de, best, cell, params, rng, ::out) ?: continue
                if (exactPinRegression(p, best, cand)) continue
                val rep = UnifiedViolationChecker.check(state, cand)
                if (betterReport(rep, bestRep)) {
                    best = cand; bestRep = rep; applied++
                }
            }
        }
        logs.add(MirrorLog(level = "I", tag = "MembraneCellLns",
            message = "膜の細胞 LNS: 必須 ${startRep.hard}->${bestRep.hard} 合計 ${startRep.total}->${bestRep.total} 重み ${startRep.weightedScore}->${bestRep.weightedScore} 採用${applied} 細胞${tried} ${EngineClock.nowMs() - t0}ms"))
        return Result(best, applied, tried, logs)
    }

    /** OWA の順: 個人の違反（盤面の印の数）が多い職員から。同数は添字順。 */
    private fun staffOrder(p: Problem, rep: ViolationReport): List<Int> {
        val load = IntArray(p.S)
        for (key in rep.violations.keys + rep.countViolations.keys) { val i = key.substringBefore(',').toIntOrNull() ?: continue; if (i in 0 until p.S) load[i]++ }
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
    private fun solveCell(p: Problem, de: DeltaEvaluator, board: Array<IntArray>, cell: IntArray, params: Params, rng: java.util.Random, out: () -> Boolean): Array<IntArray>? {
        val T = p.T; val K = p.K
        val work = board.copy2D()
        de.reset(work)
        val start = de.score()
        var cur = start
        var bestScore = start
        var bestBoard: Array<IntArray>? = null
        val free = ArrayList<IntArray>()
        for (i in cell) for (j in 0 until T) if (!p.wishLocked(i, j)) free.add(intArrayOf(i, j))
        if (free.size < 2) return null
        val unit = SCORE_HARD_UNIT
        val iters = params.itersPerCell
        var temp = params.tempStart
        val cool = Math.pow(params.tempEnd / params.tempStart, 1.0 / iters.coerceAtLeast(1))
        for (it in 0 until iters) {
            if ((it and 1023) == 0 && out()) break
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
            if (cur < bestScore) { bestScore = cur; bestBoard = work.copy2D() }
        }
        return bestBoard
    }

    private fun accept(delta: Long, temp: Double, unit: Long, rng: java.util.Random): Boolean {
        if (delta <= 0) return true
        if (delta >= unit) return false   // 必須が増える手は取らない
        return rng.nextDouble() < Math.exp(-delta / temp)
    }
}
