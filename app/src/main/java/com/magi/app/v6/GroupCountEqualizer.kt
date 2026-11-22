package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * グループ内の回数を、採点に関係なくそろえる（画面の「グループ内の回数をそろえる」、希望の反映と同じく利用者が押して上書きする操作）。
 * 手は同じ日・同じ勤務グループの 2 人のシフトの入れ替えだけ＝日ごとのシフト別人数とグループのシフト別合計は変わらない。
 * 各人の目標（置ける人の平均を個人の下限・上限へ収めた値）からのずれの 2 乗和が最も減る入れ替えから当てる。当てない手:
 * 希望・手動固定のマス、置けないシフト（`mayPlaceAt`）、個人の下限・上限から外れる（すでに外れている側はそれ以上悪くしない）、必須違反が増える。
 * 要調整（c1・並び・曜日など）は悪くなってもよい（利用者決定）。[shifts] を渡すとそのシフトの回数だけをそろえる（ほかのシフトの回数は
 * 入れ替えで動くことがあるが、個人の下限・上限は守る）。1 組ずつ当てて止まったら、月全体の入れ替えを焼きなましで探す（[anneal]、
 * 別のグループの人との入れ替えも使う＝グループの合計が動くので、ずれはそのときの平均から測る [groupDev]）。
 */
object GroupCountEqualizer {
    data class Result(val schedule: Array<IntArray>, val swaps: Int, val spreadBefore: Long, val spreadAfter: Long)

    /**
     * 各人の目標回数: グループ×シフトごとに、そのシフトを置ける人（`mayPlace`）の平均を、その人の下限・上限へ収めた値。
     * 置けない人は対象外（NaN）。入れ替えはグループ内・置ける人どうしの合計を変えないので、目標は途中で動かない。
     */
    private fun targets(p: Problem, cnt: Array<IntArray>, shifts: Set<Int>?): Array<DoubleArray> {
        val t = Array(p.S) { DoubleArray(p.K) { Double.NaN } }
        for (m in p.groupMembers) {
            if (m.size < 2) continue
            for (k in 0 until p.K) {
                if (shifts != null && k !in shifts) continue
                val el = m.filter { p.mayPlace(it, k) }
                if (el.size < 2) continue
                val mean = el.sumOf { cnt[it][k] }.toDouble() / el.size
                for (i in el) {
                    val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }.toDouble()
                    val hi = p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) Double.MAX_VALUE else it.toDouble() }
                    t[i][k] = mean.coerceIn(lo, maxOf(lo, hi))
                }
            }
        }
        return t
    }

    /** グループ×シフトごとの対象（置ける人・2 人以上）。シフトを選ばない・置ける人が 1 人以下なら null。 */
    private fun eligible(p: Problem, shifts: Set<Int>?): Array<Array<IntArray?>> =
        Array(p.groupMembers.size) { g ->
            val m = p.groupMembers[g]
            Array(p.K) { k ->
                if (m.size < 2 || (shifts != null && k !in shifts)) null
                else m.filter { p.mayPlace(it, k) }.toIntArray().takeIf { it.size >= 2 }
            }
        }

    /** グループ g・シフト k のずれ＝各人の「いまの平均を個人の下限・上限へ収めた値」からの 2 乗和（別のグループとの入れ替えで平均が動くので毎回計算）。 */
    private fun groupDev(p: Problem, el: IntArray, k: Int, cnt: Array<IntArray>): Double {
        var sum = 0
        for (i in el) sum += cnt[i][k]
        val mean = sum.toDouble() / el.size
        var d = 0.0
        for (i in el) {
            val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }.toDouble()
            val hi = p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) Double.MAX_VALUE else it.toDouble() }
            val e = cnt[i][k] - mean.coerceIn(lo, maxOf(lo, hi))
            d += e * e
        }
        return d
    }

    private fun totalDev(p: Problem, elig: Array<Array<IntArray?>>, cnt: Array<IntArray>): Long {
        var d = 0.0
        for (g in elig.indices) for (k in 0 until p.K) elig[g][k]?.let { d += groupDev(p, it, k, cnt) }
        return Math.round(d * 100)
    }

    private const val ANNEAL_ITERS = 400_000
    private const val ANNEAL_RUNS = 3

    /**
     * 1 組ずつの入れ替えでは範囲や禁止の並びにかかって進めないとき用の焼きなまし（月全体、同じグループの 2 人の入れ替え・3 人の回し・前後 2 日をまとめた入れ替え）。
     * 途中は個人の範囲を外れても必須が増えてもよい（罰として数える）が、採るのは「各 (職員,シフト) の範囲からの外れ量が元以下・
     * 必須が元以下」の盤面のうち、目標からのずれがいちばん小さいものだけ。よりそろった盤面が見つかれば [work] を書き換えて true。
     */
    private fun anneal(p: Problem, work: Array<IntArray>, cnt0: Array<IntArray>, elig: Array<Array<IntArray?>>, iters: Int, seed: Long): Boolean {
        // 手は 1 手ごとに無作為に作る（全部を先に並べると 30 人・31 日で数十万件になる）: 2 人の同じ日の入れ替え・3 人の回し・
        // 同じ 2 人の前後 2 日をまとめた入れ替えを等しい割合で。1 人目はグループから、相手は半分は同じグループ・半分は誰でも
        // （同じグループの中だけでは禁止の並びや希望で動けない回数を、別のグループの人との入れ替えで動かす。12 月の実データで
        // 厳密に解くと、同じグループだけの最小のずれ 719 に対し別のグループとも入れ替えると 119）。希望・手動固定のマスに当たる手は捨てる。日ごとの人数は変わらない。
        val groups = p.groupMembers.filter { it.size >= 2 }
        if (groups.isEmpty() || p.T == 0) return false
        val pairW = groups.map { it.size * (it.size - 1) / 2 }
        val pairTotal = pairW.sum()
        val cnt = Array(p.S) { cnt0[it].copyOf() }
        fun lo(i: Int, k: Int) = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }
        fun out(i: Int, k: Int, n: Int) = maxOf(0, lo(i, k) - n) + p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) 0 else maxOf(0, n - it) }
        val out0 = Array(p.S) { i -> IntArray(p.K) { k -> out(i, k, cnt[i][k]) } }
        val groupOf = IntArray(p.S) { -1 }.also { a -> p.groupMembers.forEachIndexed { g, m -> for (i in m) a[i] = g } }
        /** 範囲の外れ量のうち元を超えた分。 */
        fun excess(i: Int, k: Int, n: Int) = maxOf(0, out(i, k, n) - out0[i][k])
        var devCur = 0.0
        for (g in elig.indices) for (k in 0 until p.K) elig[g][k]?.let { devCur += groupDev(p, it, k, cnt) }
        val tg = IntArray(24); val tk = IntArray(24)   // この手で変わる (グループ,シフト)
        var exCur = 0
        var hardCur = 0   // 元からの必須の増減
        val cur = work.copy2D()
        val bits = C3nBitScan.usable(p)   // 64 日以内＝禁止の並びをビットで数える（30 人・31 日が業務の上限）
        val masks = if (bits) Array(p.S) { C3nBitScan.buildRowMask(p, cur[it]) } else emptyArray()
        val old2 = Array(2) { IntArray(2) }; val old3 = Array(2) { IntArray(3) }   // 日ごとの元の値（2 人・3 人）
        var bestDev = devCur
        var best: Array<IntArray>? = null
        val rnd = java.util.Random(seed)
        val t0 = 1.0; val t1 = 0.02
        val cool = Math.pow(t1 / t0, 1.0 / iters); var temp = t0
        val wEx = 2.0; val wHard = 20.0
        val ci = IntArray(12); val ck = IntArray(12); val cd = IntArray(12)   // この手での (職員,シフト) ごとの回数の増減
        val locked = BooleanArray(p.S * p.T) { p.wishLocked(it / p.T, it % p.T) }   // 希望・手動固定（毎手引くと重い）
        val who2 = IntArray(2); val who3 = IntArray(3); val day1 = IntArray(1); val day2 = IntArray(2)
        for (step in 0 until iters) {
            temp *= cool
            var gi = rnd.nextInt(pairTotal); var g = 0
            while (gi >= pairW[g]) { gi -= pairW[g]; g++ }
            val m = groups[g]
            val kind = rnd.nextInt(3)
            val n = if (kind == 1 && m.size >= 3) 3 else 2
            val who = if (n == 2) who2 else who3
            who[0] = m[rnd.nextInt(m.size)]
            val wide = rnd.nextBoolean()   // 相手を誰からでも選ぶ
            fun pick() = if (wide) rnd.nextInt(p.S) else m[rnd.nextInt(m.size)]
            do { who[1] = pick() } while (who[1] == who[0])
            if (n == 3) do { who[2] = pick() } while (who[2] == who[0] || who[2] == who[1])
            val d1 = rnd.nextInt(p.T)
            val days = if (kind == 2 && p.T >= 2) {
                var d2: Int
                do { d2 = rnd.nextInt(p.T) } while (d2 == d1)
                day2[0] = d1; day2[1] = d2; day2
            } else { day1[0] = d1; day1 }
            var lockHit = false
            for (d in days) for (t in 0 until n) if (locked[who[t] * p.T + d]) lockHit = true
            if (lockHit) continue
            var ok = true; var moved = false; var nc = 0
            for (d in days) {
                for (t in 0 until n) {
                    val from = cur[who[t]][d]; val to = cur[who[(t + 1) % n]][d]
                    if (from !in 0 until p.K || to !in 0 until p.K) { ok = false; break }
                    if (from == to) continue
                    if (!p.mayPlaceAt(who[t], d, to)) { ok = false; break }
                    for (q in 0..1) {
                        val i = who[t]; val k = if (q == 0) from else to; val dd = if (q == 0) -1 else 1
                        var e = 0
                        while (e < nc && !(ci[e] == i && ck[e] == k)) e++
                        if (e == nc) { ci[nc] = i; ck[nc] = k; cd[nc] = 0; nc++ }
                        cd[e] += dd
                    }
                    moved = true
                }
                if (!ok) break
            }
            if (!ok || !moved) continue
            var dDev = 0.0; var dEx = 0
            for (e in 0 until nc) {
                val i = ci[e]; val k = ck[e]; val dd = cd[e]
                if (dd == 0) continue
                dEx += excess(i, k, cnt[i][k] + dd) - excess(i, k, cnt[i][k])
            }
            var nt = 0
            for (e in 0 until nc) {
                val g = groupOf[ci[e]]; val k = ck[e]
                if (cd[e] == 0 || g < 0 || elig[g][k] == null) continue
                var seen = false
                for (q in 0 until nt) if (tg[q] == g && tk[q] == k) seen = true
                if (!seen) { tg[nt] = g; tk[nt] = k; nt++ }
            }
            if (nt > 0) {
                for (q in 0 until nt) dDev -= groupDev(p, elig[tg[q]][tk[q]]!!, tk[q], cnt)
                for (e in 0 until nc) cnt[ci[e]][ck[e]] += cd[e]
                for (q in 0 until nt) dDev += groupDev(p, elig[tg[q]][tk[q]]!!, tk[q], cnt)
                for (e in 0 until nc) cnt[ci[e]][ck[e]] -= cd[e]
            }
            // その場で当てて、必須の差は変わった日だけ数える（同じ日の並べ替え＝日の人数は不変。2 日は 1 日ずつ当てて足す）
            var dHard = 0
            for ((di, d) in days.withIndex()) {
                val o = (if (n == 2) old2 else old3)[di]
                for (t in 0 until n) o[t] = cur[who[t]][d]
                for (t in 0 until n) {
                    cur[who[t]][d] = o[(t + 1) % n]
                    if (bits) C3nBitScan.move(p, masks[who[t]], d, o[t], o[(t + 1) % n])
                }
                dHard += if (bits) HardDelta.sameDayPermutationDeltaBits(p, cur, masks, d, who, o)
                    else HardDelta.sameDayPermutationDelta(p, cur, d, who, o)
            }
            val df = dDev + wEx * dEx + wHard * dHard
            if (df <= 0 || rnd.nextDouble() < Math.exp(-df / temp)) {
                for (e in 0 until nc) cnt[ci[e]][ck[e]] += cd[e]
                devCur += dDev; exCur += dEx; hardCur += dHard
                if (exCur == 0 && hardCur <= 0 && devCur < bestDev - 1e-9) {
                    bestDev = devCur
                    best = (best ?: Array(p.S) { IntArray(p.T) }).also { bb -> for (i in 0 until p.S) cur[i].copyInto(bb[i]) }
                }
            } else for (di in days.indices.reversed()) {
                val d = days[di]; val o = (if (n == 2) old2 else old3)[di]
                for (t in 0 until n) {
                    if (bits) C3nBitScan.move(p, masks[who[t]], d, cur[who[t]][d], o[t])
                    cur[who[t]][d] = o[t]
                }
            }
            temp *= cool
        }
        val bb = best ?: return false
        for (i in 0 until p.S) bb[i].copyInto(work[i])
        return true
    }

    private fun counts(p: Problem, s: Array<IntArray>) = Array(p.S) { i -> IntArray(p.K).also { c -> for (k in s[i]) if (k in 0 until p.K) c[k]++ } }


    fun apply(state: MagiState, schedule: Array<IntArray>, shifts: Set<Int>? = null, maxSwaps: Int = 2_000,
              annealIters: Int = ANNEAL_ITERS, seed: Long = 1L): Result {
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val cnt = counts(p, work)
        val tgt = targets(p, cnt, shifts)
        val elig = eligible(p, shifts)
        val before = totalDev(p, elig, cnt)
        if (tgt.all { row -> row.all { it.isNaN() } }) return Result(work, 0, before, before)   // そろえる (職員,シフト) が無い
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)   // 希望固定と手動固定
        /** 回数 n→n+d で、個人の範囲からの外れ量が増えないか。 */
        fun rangeOk(i: Int, k: Int, d: Int): Boolean {
            val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) 0 else it }
            val hi = p.rangeHi[i][k]
            fun out(n: Int) = maxOf(0, lo - n) + (if (hi == Int.MAX_VALUE) 0 else maxOf(0, n - hi))
            val n = cnt[i][k]
            return out(n + d) <= out(n)
        }
        /** (i,k) の回数を d だけ変えたときのずれの 2 乗の変化（対象外は 0）。 */
        fun dsq(i: Int, k: Int, d: Int): Double {
            val t = tgt[i][k]
            if (t.isNaN()) return 0.0
            val a = cnt[i][k] - t; val b = a + d
            return b * b - a * a
        }
        data class Cand(val gain: Double, val a: Int, val b: Int, val d: Int)
        var swaps = 0
        while (swaps < maxSwaps) {
            val cands = ArrayList<Cand>()
            for (m in p.groupMembers) {
                if (m.size < 2) continue
                for (ai in m.indices) for (bi in ai + 1 until m.size) {
                    val a = m[ai]; val b = m[bi]
                    for (d in 0 until p.T) {
                        val x = work[a][d]; val y = work[b][d]
                        if (x == y || x !in 0 until p.K || y !in 0 until p.K || !movable(a, d) || !movable(b, d)) continue
                        if (!p.mayPlaceAt(a, d, y) || !p.mayPlaceAt(b, d, x)) continue
                        // a: x−1・y+1、b: x+1・y−1（負＝目標へ近づく）
                        val gain = dsq(a, x, -1) + dsq(a, y, 1) + dsq(b, x, 1) + dsq(b, y, -1)
                        if (gain > -1e-9) continue
                        if (!rangeOk(a, x, -1) || !rangeOk(a, y, 1) || !rangeOk(b, x, 1) || !rangeOk(b, y, -1)) continue
                        cands.add(Cand(gain, a, b, d))
                    }
                }
            }
            if (cands.isEmpty()) break
            cands.sortWith(compareBy<Cand> { it.gain }.thenBy { it.a }.thenBy { it.b }.thenBy { it.d })
            var done = false
            for (c in cands) {
                val x = work[c.a][c.d]; val y = work[c.b][c.d]
                val prev = work.copy2D()
                work[c.a][c.d] = y; work[c.b][c.d] = x
                if (HardDelta.delta(p, prev, work) > 0) { work[c.a][c.d] = x; work[c.b][c.d] = y; continue }
                cnt[c.a][x]--; cnt[c.a][y]++; cnt[c.b][x]++; cnt[c.b][y]--
                swaps++; done = true
                break
            }
            if (!done) break
        }
        // 焼きなましは乱数で行き着く先が揺れるので、種を変えて ANNEAL_RUNS 回走らせ、いちばんそろった盤面を採る
        var bestRun: Array<IntArray>? = null; var bestRunDev = totalDev(p, elig, cnt)
        if (annealIters > 0) for (run in 0 until ANNEAL_RUNS) {
            val w = work.copy2D()
            if (!anneal(p, w, cnt, elig, annealIters, seed + run)) continue
            val dv = totalDev(p, elig, counts(p, w))
            if (dv < bestRunDev) { bestRunDev = dv; bestRun = w }
        }
        bestRun?.let { bb ->
            var changed = 0
            for (i in 0 until p.S) for (j in 0 until p.T) if (bb[i][j] != work[i][j]) changed++
            swaps += (changed + 1) / 2   // 変わったマスの数から数えた入れ替えの組数（3 人の回しは 2 組と数える）
            for (i in 0 until p.S) bb[i].copyInto(work[i])
            for (i in 0 until p.S) { cnt[i].fill(0); for (k in work[i]) if (k in 0 until p.K) cnt[i][k]++ }
        }
        return Result(work, swaps, before, totalDev(p, elig, cnt))
    }

    /** ［そろえて組み直す］の結果。[board]＝組み直した盤面（改善が無ければ開始の盤面）、[accepted]＝CP-SAT で採った部分の数。 */
    class Resolved(val board: Array<IntArray>, val before: ViolationReport, val after: ViolationReport, val accepted: Long, val wallMs: Long)

    /** [board] の [shifts]（null は全シフト）の各人の回数を固定して CP-SAT で解き直す（`magi_cpsat::Options::fixCounts`）。
     *  開始の盤面より悪くはならない。CP-SAT が使えないビルド・作れない問題では null。 */
    fun resolveKeepingCounts(state: MagiState, board: Array<IntArray>, shifts: Set<Int>?, maxSeconds: Double,
                             threads: Int, seed: Long = 1L, shouldStop: () -> Boolean): Resolved? {
        val p = cachedProblem(state)
        val start = normalizeSchedule(board, p)
        val fix = IntArray(p.S * p.K) { -1 }
        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = start[i][j]
            if (k !in 0 until p.K || (shifts != null && k !in shifts)) continue
            if (fix[i * p.K + k] < 0) fix[i * p.K + k] = 0
            fix[i * p.K + k]++
        }
        // 選んだシフトで 0 回の人も 0 回に固定する（新しく入れると回数が変わる）。
        for (i in 0 until p.S) for (k in 0 until p.K) if ((shifts == null || k in shifts) && fix[i * p.K + k] < 0) fix[i * p.K + k] = 0
        val before = UnifiedViolationChecker.check(state, start)
        val t0 = EngineClock.nowMs()
        val out = CpsatResolve.run(p, start, maxSeconds, threads, seed, fixCounts = fix, shouldStop = shouldStop) ?: return null
        val cand = out.board
        val after = UnifiedViolationChecker.check(state, cand)
        // CP-SAT の採否はチェッカーと同値の採点だが、回数の一致と悪化なしをここでも確かめる（多重防御）。
        val keeps = (0 until p.S).all { i ->
            val c = IntArray(p.K); for (k in cand[i]) if (k in 0 until p.K) c[k]++
            (0 until p.K).all { k -> fix[i * p.K + k] < 0 || c[k] == fix[i * p.K + k] }
        }
        return if (keeps && !betterReport(before, after)) Resolved(cand, before, after, out.accepted, EngineClock.nowMs() - t0)
        else Resolved(start, before, before, 0L, EngineClock.nowMs() - t0)
    }
}
