package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [つくる前の確認] 本実行の前に「計算では消えない」「もう一度つくると外れる」を数える（`docs/business-logic.md` の診断の節）。
 * 表示と誘導だけ＝探索・評価・重みには触れない。希望も上限も自動で変えない（HF77）。
 */
object PreRunCheck {
    /** 手で置いてある上限 0 の勤務 1 セル（希望で固定したセルは除く）。本実行の入口の clear が外す。 */
    data class HandPlacedCell(val staff: Int, val day: Int, val shift: Int)

    /** 個人の上限（0回）の密度。[topStaff] は組の数が最も多い職員（同数は番号の小さい方）。 */
    data class WallHint(val pairs: Int, val staffCount: Int, val topStaff: Int, val topPairs: Int)

    /** 本人の希望の件数が個人の上限を超える（[V6SanityPort.buildGuidance] の 6e と同じ判定）。 */
    data class WishOverCap(val staff: Int, val shift: Int, val wished: Int, val hi: Int)

    data class Summary(
        val wishConflicts: List<WishSelfConflict>,
        val impossibleWishes: List<ImpossibleWish>,
        val forcedShortfalls: List<V6SanityPort.ForcedCovU>,
        /** 証明つきの矛盾（buildGuidance の 9 と同じく、コアに希望を含むものだけ）。 */
        val dayProofs: List<ConstraintMus.DayConflict>,
        val staffProofs: List<ConstraintMus.StaffConflict>,
        val wishOverCaps: List<WishOverCap>,
        val rerunClears: List<HandPlacedCell>,
        val wallHint: WallHint?,
    ) {
        val floorCount: Int get() = wishConflicts.size + impossibleWishes.size + forcedShortfalls.size +
            dayProofs.size + staffProofs.size + wishOverCaps.size
        /** 利用者決定 2026-09-28: どちらかの節に 1 件でもあるときだけシートを出す。 */
        val needsSheet: Boolean get() = floorCount > 0 || rerunClears.isNotEmpty()
    }

    fun build(state: MagiState, schedule: Array<IntArray>): Summary {
        val p = cachedProblem(state)
        val s = normalizeSchedule(schedule, p)
        return Summary(
            wishConflicts = V6SanityPort.wishSelfConflicts(p),
            impossibleWishes = V6SanityPort.detectImpossibleWishes(state, p),
            forcedShortfalls = V6SanityPort.forcedCovU(state, p),
            dayProofs = ConstraintMus.analyzeDayConflicts(p).filter { hasWish(it.core) }.sortedBy { it.day },
            staffProofs = ConstraintMus.analyzeStaffConflicts(p).filter { hasWish(it.core) }.sortedBy { it.staff },
            wishOverCaps = wishOverCaps(p),
            rerunClears = handPlacedCells(p, s),
            wallHint = wallHint(RelaxTrial.upperZeroWalls(state)),
        )
    }

    private fun hasWish(core: List<ConstraintMus.Item>) = core.any { it is ConstraintMus.WishPin }

    fun wishOverCaps(p: Problem): List<WishOverCap> {
        val out = ArrayList<WishOverCap>()
        for (i in 0 until p.S) for (k in 0 until p.K) {
            val hi = p.rangeHi[i][k]
            if (hi == Int.MAX_VALUE || !p.canDo(i, k)) continue
            val wished = (0 until p.T).count { j -> p.wishFixed(i, j) && p.wish[i][j] == k }
            if (wished > hi) out += WishOverCap(i, k, wished, hi)
        }
        return out
    }

    /** [V6SanityPort.handPlacedUpperZeroIssue] と同じ判定のセル一覧（日→職員の順）。 */
    fun handPlacedCells(p: Problem, s: Array<IntArray>): List<HandPlacedCell> {
        val out = ArrayList<HandPlacedCell>()
        for (j in 0 until p.T) for (i in 0 until minOf(p.S, s.size)) {
            val k = s[i].getOrNull(j) ?: continue
            if (k !in 0 until p.K || k == p.restIdx || !p.canDo(i, k) || p.rangeHi[i][k] != 0) continue
            if (p.wishFixed(i, j) && p.wish[i][j] == k) continue
            out += HandPlacedCell(i, j, k)
        }
        return out
    }

    fun wallHint(walls: List<Pair<Int, Int>>): WallHint? {
        if (walls.isEmpty()) return null
        val per = walls.groupingBy { it.first }.eachCount()
        val top = per.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first()
        return WallHint(walls.size, per.size, top.key, top.value)
    }

    /** 設定・希望・盤面の指紋。「このままつくる」の後は、これが変わるまで同じ理由で止めない。 */
    fun fingerprint(state: MagiState, schedule: Array<IntArray>): Long {
        var h = StateFingerprint.of(state)
        for (row in schedule) for (v in row) h = h * 31L + v
        return h
    }
}
