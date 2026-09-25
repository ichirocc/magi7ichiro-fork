package com.magi.app.v6

/**
 * 候補盤面の HARD 正味差分（groupViol/pref/c3w/c3n/covU）を変わったセル・行・日だけから厳密に数える。
 * `check(cand).hard == check(base).hard + delta(...)` が [UnifiedViolationChecker.check] と同じ意味論で成り立つ
 * （各族の式は checker の当該ループの局所化）。呼出側は「このΔなら必ず却下する」候補だけ checker を省く
 * ＝採用集合・盤面は不変の速度専用（[PolishGate.hardDeltaPrefilter]）。
 */
internal object HardDelta {
    private fun v(p: Problem, x: Int): Int = if (x in 0 until p.K) x else -1

    /** 1 セル (i,j)=k の、セル単位 HARD 族（groupViol/pref/c3w）の件数。 */
    private fun cellHard(p: Problem, i: Int, j: Int, k: Int): Int {
        var h = 0
        if (k >= 0 && !p.canDo(i, k)) h++
        val w = p.wish[i][j]
        if (w in 0 until p.K && p.canDo(i, w) && k != w) h++
        if (p.c3wBanned(i, j, k)) h++
        return h
    }

    /** 行 row の cons3n fire のうち、窓が列 j を含むものの数（j を含まない窓は j の書換えで変わらない）。 */
    private fun c3nFiresCovering(p: Problem, row: IntArray, j: Int): Int {
        var fires = 0
        for (c in p.cons3n) {
            val seq = c.seq
            val d = seq.size
            if (d == 0 || d > p.T) continue
            for (s in (j - d + 1).coerceAtLeast(0)..j.coerceAtMost(p.T - d)) {
                var z = 0
                for (l in 0 until d) if (row[s + l] == seq[l]) z++
                if (z == d) fires++
            }
        }
        return fires
    }

    /** 任意の多セル変更 base→cand の HARD 正味差分。covU は変わった日の (日,シフト) 人数の到着・離脱の両方を数える。 */
    fun delta(p: Problem, base: Array<IntArray>, cand: Array<IntArray>): Int {
        var d = 0
        var rows: BooleanArray? = null
        var days: BooleanArray? = null
        for (i in 0 until p.S) {
            val br = base[i]; val cr = cand[i]
            for (j in 0 until p.T) {
                val o = v(p, br[j]); val n = v(p, cr[j])
                if (o == n) continue
                d += cellHard(p, i, j, n) - cellHard(p, i, j, o)
                (rows ?: BooleanArray(p.S).also { rows = it })[i] = true
                (days ?: BooleanArray(p.T).also { days = it })[j] = true
            }
        }
        val rs = rows ?: return d
        if (p.cons3n.isNotEmpty()) for (i in 0 until p.S) if (rs[i]) {
            d += C1DeltaPrefilter.staffC3nFires(p, IntArray(p.T) { v(p, cand[i][it]) }) -
                C1DeltaPrefilter.staffC3nFires(p, IntArray(p.T) { v(p, base[i][it]) })
        }
        val ds = days!!
        val cb = IntArray(p.K); val cc = IntArray(p.K)
        for (j in 0 until p.T) if (ds[j]) {
            cb.fill(0); cc.fill(0)
            for (i in 0 until p.S) { v(p, base[i][j]).let { if (it >= 0) cb[it]++ }; v(p, cand[i][j]).let { if (it >= 0) cc[it]++ } }
            for (k in 0 until p.K) if (cb[k] != cc[k]) d += p.covUCell(k, j, cc[k]) - p.covUCell(k, j, cb[k])
        }
        return d
    }

    /**
     * 同日 j 内の置換（職員 staff[t] の旧値 old[t] → 現在の work[staff[t]][j]）の HARD 正味差分。work は適用後。
     * 健全性: 置換は日 j の値の多重集合を保つので (j,k) 人数が不変＝covU の差は 0。groupViol/pref/c3w はセル単位
     * （c3wBan は静的表）、c3n は変わった行の j を含む窓だけが変わる＝この和は [delta] と一致する。
     */
    fun sameDayPermutationDelta(p: Problem, work: Array<IntArray>, j: Int, staff: IntArray, old: IntArray): Int {
        var d = 0
        for (t in staff.indices) {
            val i = staff[t]; val row = work[i]
            val n = v(p, row[j]); val o = v(p, old[t])
            if (n == o) continue
            d += cellHard(p, i, j, n) - cellHard(p, i, j, o)
            if (p.cons3n.isNotEmpty()) {
                val after = c3nFiresCovering(p, row, j)
                val keep = row[j]; row[j] = old[t]
                d += after - c3nFiresCovering(p, row, j)
                row[j] = keep
            }
        }
        return d
    }
}
