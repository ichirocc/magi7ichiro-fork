package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.Range

/**
 * [S6] 「設定を緩めたら」試算（`docs/s6_relax_trial.md` §3・§4）。
 * 残る必須違反 1 件（起点）について、個人の上限 0（`mayPlace` の除外）のどれが壁かを
 * 「全部外して解けるか → 使った壁だけ残す → 1 つずつ外して要らない壁を落とす」で見つけ、
 * 残った組を実際に緩めた state で同じ探索を回して、設定を残したままの対照との差を出す。
 * 入力は書かない。結果は数値と手順（盤面は持たない＝仮盤禁止）。設定値を自動で変えない（HF77）。
 */
object RelaxTrial {
    /** 個人の上限を [newHi] へ上げる 1 件（いまの上限は 0）。下限は触らない。 */
    data class Relax(val staff: Int, val shift: Int, val newHi: Int)

    /** 試算の手順 1 セル分（盤面は持たない）。 */
    data class Move(val staff: Int, val day: Int, val from: Int, val to: Int)

    sealed interface Outcome
    /**
     * [staff]/[day]＝起点、[window]＝手順を言葉で並べる日の範囲（外の手は畳む）。
     * [prerequisite]＝いまの勤務表に手で置いてある上限 0 の勤務（本実行の入口の clear が外す）に合わせる分。
     * [relaxes]＝それとは別に、起点の違反を解くのに要る上限 0。数値は必須違反の件数:
     * h0＝いま、rk＝設定そのままでもう一度つくった見込み、rkH＝[prerequisite] だけ緩めた見込み、rr＝両方緩めた見込み。
     */
    data class Result(
        val staff: Int, val day: Int, val window: IntRange,
        val prerequisite: List<Relax>,
        val relaxes: List<Relax>,
        val h0: Int, val rk: Int, val rkH: Int, val rr: Int,
        val moves: List<Move>,
    ) : Outcome {
        val pKeep: Int get() = minOf(h0, rk)
        val pPrereq: Int get() = minOf(h0, rkH)
        val pRelax: Int get() = minOf(h0, rr)
        /** 設定をどれも変えずにもう一度つくる場合と比べて減る見込み。 */
        val att: Int get() = pKeep - pRelax
        /** そのうち [relaxes]（手置きに合わせる分を除く）に帰属する分。 */
        val attWalls: Int get() = pPrereq - pRelax
    }
    /** 上限 0 を全部外しても、起点の違反を解く手が見つからなかった（上限 0 は壁でない見込み。0 件の証拠ではない）。 */
    data object NoWall : Outcome
    data class Unavailable(val reason: String) : Outcome
    data object Stopped : Outcome

    /** 緩める候補の母集団: 担当できて、休み以外で、個人の上限が 0 の (職員, シフト)。 */
    fun upperZeroWalls(state: MagiState): List<Pair<Int, Int>> {
        val p = cachedProblem(state, false)
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until p.S) for (k in 0 until p.K)
            if (k != p.restIdx && p.canDo(i, k) && p.rangeHi[i][k] == 0) out += i to k
        return out
    }

    /** 勤務表に手で置いてある上限 0 の勤務（希望で固定したセルは除く）。本実行の入口の clear がこれを外す。 */
    fun handPlaced(state: MagiState, schedule: Array<IntArray>): List<Relax> {
        val p = cachedProblem(state, false)
        return usedWalls(p, upperZeroWalls(state), schedule)
    }

    /** [relaxes] を当てた state（確定操作と同じ関数）。上限だけを書き換え、下限の欄はそのまま。 */
    fun apply(state: MagiState, relaxes: List<Relax>): MagiState {
        if (relaxes.isEmpty()) return state
        val m = state.staffRange.toMutableMap()
        for (r in relaxes) {
            val key = "${r.staff},${r.shift}"
            val cur = m[key] ?: Range("", "")
            m[key] = Range(cur.lo, r.newHi.toString())
        }
        return state.copy(staffRange = m)
    }

    /**
     * 起点 (staff, day) の必須違反について、壁になっている上限 0 の組を探し、その組で試算する。
     * 起点がいま必須違反のセルでなければ null（対象外）。
     */
    fun discover(
        state: MagiState,
        schedule: Array<IntArray>,
        staff: Int,
        day: Int,
        maxRelaxes: Int = 3,
        shouldStop: () -> Boolean = { false },
    ): Outcome? {
        unavailableReason(state, schedule)?.let { return Unavailable(it) }
        val rep0 = UnifiedViolationChecker.check(state, schedule.copy2D())
        val window = anchorWindow(state, rep0, staff, day) ?: return null
        val walls = upperZeroWalls(state)
        if (walls.isEmpty()) return NoWall
        val p0 = cachedProblem(state, false)
        val pre = usedWalls(p0, walls, schedule)
        val control = search(state, schedule, window, shouldStop) ?: return unavailableOrStopped(shouldStop)
        val stH = apply(state, pre)
        val controlH = if (pre.isEmpty()) control else search(stH, schedule, window, shouldStop) ?: return unavailableOrStopped(shouldStop)
        // 上限 0 を全部外した state（上限の欄を空＝未設定）で解けるか。解けた盤面が使った上限 0 だけを候補に残す。
        val wallKeys = walls.mapTo(HashSet()) { "${it.first},${it.second}" }
        val oracle = state.copy(staffRange = state.staffRange.mapValues { (key, r) -> if (key in wallKeys) Range(r.lo, "") else r })
        val best = search(oracle, schedule, window, shouldStop) ?: return unavailableOrStopped(shouldStop)
        val target = minOf(rep0.hard, best.hard)
        if (target >= minOf(rep0.hard, controlH.hard) || hardAt(oracle, best.board, staff, day)) return NoWall
        val preKeys = pre.mapTo(HashSet()) { it.staff to it.shift }
        var set = usedWalls(p0, walls, best.board).filter { (it.staff to it.shift) !in preKeys }
        // 1 つずつ外して、同じだけ減るなら要らない壁（順序は職員→シフトで固定＝決定的）。
        for (r in set.toList()) {
            if (shouldStop()) return Stopped
            val without = set - r
            val s = search(apply(stH, without), schedule, window, shouldStop) ?: return unavailableOrStopped(shouldStop)
            if (minOf(rep0.hard, s.hard) <= target && !hardAt(stH, s.board, staff, day)) set = without
        }
        if (set.size > maxRelaxes) return NoWall
        val r = search(apply(stH, set), schedule, window, shouldStop) ?: return unavailableOrStopped(shouldStop)
        if (shouldStop()) return Stopped
        val moves = if (r.hard < rep0.hard) diff(schedule, r.board) else emptyList()
        return Result(staff, day, window, pre, set, rep0.hard, control.hard, controlH.hard, r.hard, moves)
    }

    /**
     * 背景探索の起点（§2.2・§8）: 必須違反セル（希望どうしの衝突に入るセルを除く）を職員→日の順に、連続した必須セルは
     * 先頭の 1 つだけ、最大 [max] 件。
     */
    fun anchors(state: MagiState, schedule: Array<IntArray>, max: Int = 3): List<Pair<Int, Int>> {
        val rep = UnifiedViolationChecker.check(state, schedule.copy2D())
        val self = V6SanityPort.wishSelfConflicts(state).flatMapTo(HashSet()) { it.wishKeys }
        val p = cachedProblem(state, false)
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until p.S) {
            var j = 0
            while (j < p.T && out.size < max) {
                fun ok(d: Int) = rep.cellFamilies["$i,$d"]?.any { it in HARD_CELL } == true && "$i,$d" !in self
                if (!ok(j)) { j++; continue }
                out += i to j
                while (j < p.T && ok(j)) j++
            }
        }
        return out
    }

    /** [anchors] を順に試算し、必須違反が減る見込み（att > 0）の最初の組。どれも無ければ NoWall。 */
    fun firstWall(state: MagiState, schedule: Array<IntArray>, maxAnchors: Int = 3, shouldStop: () -> Boolean = { false }): Outcome {
        unavailableReason(state, schedule)?.let { return Unavailable(it) }
        for ((i, j) in anchors(state, schedule, maxAnchors)) {
            if (shouldStop()) return Stopped
            when (val o = discover(state, schedule, i, j, shouldStop = shouldStop)) {
                is Result -> if (o.att > 0 && o.relaxes.isNotEmpty()) return o
                Stopped -> return Stopped
                is Unavailable -> return o
                else -> {}
            }
        }
        return if (shouldStop()) Stopped else NoWall
    }

    /** 確定の盤面（§6 の 3）: 試算時の盤面に手順を当てる。どれかのセルが手順の from と違えば null。入力は書かない。 */
    fun applyMoves(board: Array<IntArray>, moves: List<Move>): Array<IntArray>? {
        val nb = board.copy2D()
        for (m in moves) {
            if (nb.getOrNull(m.staff)?.getOrNull(m.day) != m.from) return null
            nb[m.staff][m.day] = m.to
        }
        return nb
    }

    private fun hardAt(state: MagiState, board: Array<IntArray>, staff: Int, day: Int): Boolean =
        UnifiedViolationChecker.check(state, board.copy2D()).cellFamilies["$staff,$day"]?.any { it in HARD_CELL } == true

    /** 起点の窓: 起点の職員の、起点の日を含む連続した必須違反セルの日の範囲 ±1。起点が必須違反でなければ null。 */
    internal fun anchorWindow(state: MagiState, rep: ViolationReport, staff: Int, day: Int): IntRange? {
        val p = cachedProblem(state, false)
        fun isHard(j: Int) = rep.cellFamilies["$staff,$j"]?.any { it in HARD_CELL } == true
        if (staff !in 0 until p.S || day !in 0 until p.T || !isHard(day)) return null
        var a = day; var b = day
        while (a - 1 >= 0 && isHard(a - 1)) a--
        while (b + 1 < p.T && isHard(b + 1)) b++
        return maxOf(0, a - 1)..minOf(p.T - 1, b + 1)
    }

    private val HARD_CELL = setOf("vio-c3n", "vio-c3w", "vio-pref", "vio-groupViol")

    internal class Found(val hard: Int, val board: Array<IntArray>)

    /**
     * 探索（決定的・止められる）: 本実行の入口と同じ clear → VCR（既定 Params）と、窓の日ごとの同日 2 人交換で必須を増やさないもの
     * 1 つを先に当ててから VCR。いちばん良い盤面（keep-best と同じ比較）を返す。clear が例外なら null。
     */
    internal fun search(state: MagiState, schedule: Array<IntArray>, window: IntRange, shouldStop: () -> Boolean): Found? {
        val p = cachedProblem(state, false)
        val base = try { HardRepairCore.clearCappedCells(state, schedule.copy2D()).first } catch (e: Exception) { return null }
        val baseHard = UnifiedViolationChecker.check(state, base.copy2D()).hard
        var best = vcr(state, base, shouldStop)
        var bestRep = UnifiedViolationChecker.check(state, best.copy2D())
        for (j in window) for (x in 0 until p.S) for (y in x + 1 until p.S) {
            if (shouldStop()) return null
            val kx = base[x][j]; val ky = base[y][j]
            if (kx == ky || p.wishLocked(x, j) || p.wishLocked(y, j) || !p.mayPlace(x, ky) || !p.mayPlace(y, kx)) continue
            val nb = base.copy2D(); nb[x][j] = ky; nb[y][j] = kx
            if (UnifiedViolationChecker.check(state, nb.copy2D()).hard > baseHard) continue
            val r = vcr(state, nb, shouldStop)
            val rep = UnifiedViolationChecker.check(state, r.copy2D())
            if (betterReport(rep, bestRep)) { best = r; bestRep = rep }
        }
        return Found(bestRep.hard, best)
    }

    private fun vcr(state: MagiState, board: Array<IntArray>, shouldStop: () -> Boolean): Array<IntArray> =
        ViolationComponentRepair.repair(state, board.copy2D(), emptyList(), ViolationComponentRepair.Params(), shouldStop, quantitativeRangeEval = false).newSchedule

    /** [board] が上限 0 の (職員, シフト) を使っている組（希望で固定したセルは除く）。上げ幅は最小の 1（`mayPlace` は上限 0 だけを外す）。 */
    private fun usedWalls(p: Problem, walls: List<Pair<Int, Int>>, board: Array<IntArray>): List<Relax> =
        walls.filter { (i, k) -> (0 until p.T).any { j -> board[i][j] == k && !(p.wishLocked(i, j) && p.wish[i][j] == k) } }
            .map { (i, k) -> Relax(i, k, 1) }

    private fun diff(a: Array<IntArray>, b: Array<IntArray>): List<Move> {
        val out = ArrayList<Move>()
        for (j in a[0].indices) for (i in a.indices) if (a[i][j] != b[i][j]) out += Move(i, j, a[i][j], b[i][j])
        return out
    }

    private fun unavailableOrStopped(shouldStop: () -> Boolean): Outcome =
        if (shouldStop()) Stopped else Unavailable("休みシフトが設定されていません")

    private fun unavailableReason(state: MagiState, schedule: Array<IntArray>): String? {
        val p = cachedProblem(state, false)
        if (schedule.size != p.S || schedule.any { it.size != p.T }) return "勤務表の大きさが設定と合いません"
        if (schedule.any { row -> row.any { it !in 0 until p.K } }) return "未割当のセルがあります"
        return null
    }
}
