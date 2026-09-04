package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [3.494.0/ユーザー指示「汎用性を重視する。特定のシフトを特別扱いしない」] 連交換研磨。
 * 3.493.0 の夜勤連交換研磨（cons3n の先頭要素＝夜勤・翌日が希望固定、という前提つき）を置き換える汎用版。
 *
 * 対象（アンカー）＝**あらゆる違反**:
 *  - セル違反 (i,j)（`cellFamilies`）: そのセルの前日・当日・翌日を含む**同一シフトの最大連**（シフトの種類を問わない）。
 *  - 回数違反 (i,k)（`countViolations`）: 職員 i の行にある**全ての最大連**。
 *
 * 手＝連 R1 を、同じシフト・同じ長さ・日が重ならない他職員の連 R2 と**窓ごと丸ごと交換**（両窓の全セルを2者で
 * 入れ替える＝日別人数と、その連のシフトの両者の回数を保存。他シフトの回数だけが窓の中身ぶん入れ替わる）。
 * 交換のみ／交換＋違反セル（セル違反は当日、回数違反は超過シフトの各セル）を担当可能な別シフトへ付替え、を候補に、
 * 正式チェッカーの keep-best（betterReport＝hard→weighted→total、厳密ピン保護）で採用＝退化不能。
 *
 * これで解ける典型＝「前日の連（夜勤など）と翌日の固定セルに挟まれ、禁止の並びのせいで置けるシフトが無い日」
 * （1セル付替え・同日入替では前日の連が動かず既存パスが全部却下する穴）。だが判定にシフトの意味は一切使わない。
 * 枝刈り: 両窓に希望固定なし・相互に担当可・交換後の窓の境界（両端と外側1日）に禁止の並びができる組は正式評価前に
 * 落とす。正式評価は `maxEvaluations` で上限（後処理予算を食い潰さない）。
 */
internal object RunSwapPolish {

    fun applyRunSwapPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, maxEvaluations: Int = 600,
        shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        val rejectCulprits = RejectCulpritStats()
        var anchorsSeen = 0; var candidates = 0; var evaluations = 0; var prunedC3n = 0; var noPartner = 0
        val stuck = LinkedHashSet<String>()
        fun nameOf(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"

        fun runOf(i: Int, j: Int): IntRange {
            val n = work[i][j]; var a = j; var b = j
            while (a > 0 && work[i][a - 1] == n) a--
            while (b < p.T - 1 && work[i][b + 1] == n) b++
            return a..b
        }
        fun swapWindows(i: Int, o: Int, r1: IntRange, r2: IntRange) {
            for (t in r1) { val a = work[i][t]; work[i][t] = work[o][t]; work[o][t] = a }
            for (t in r2) { val a = work[i][t]; work[i][t] = work[o][t]; work[o][t] = a }
        }
        fun windowsExchangeable(i: Int, o: Int, r: IntRange): Boolean =
            r.all { t -> !p.wishLocked(i, t) && !p.wishLocked(o, t) && p.canDo(o, work[i][t]) && p.canDo(i, work[o][t]) }
        // 交換後の窓の境界に禁止の並びができていれば正式評価の前に落とす（チェッカーが最終判定＝見逃しは無害）。
        fun boundaryForbidden(i: Int, o: Int, r1: IntRange, r2: IntRange): Boolean {
            for (st in intArrayOf(i, o)) for (r in arrayOf(r1, r2)) {
                for (t in intArrayOf(r.first - 1, r.first, r.last, r.last + 1)) {
                    if (t !in 0 until p.T) continue
                    val v = work[st][t]
                    if (v in 0 until p.K && p.makesForbiddenRun(work, st, t, v)) return true
                }
            }
            return false
        }
        /** 職員 i の連 r1（シフト n）を他職員の同型の連と交換し、続けて reassign のセルを付け替える候補を試す。採用なら true。 */
        fun tryExchange(i: Int, r1: IntRange, reassign: List<Int>): Boolean {
            val n = work[i][r1.first]
            if (n !in 0 until p.K || r1.any { p.wishLocked(i, it) }) return false
            var partners = 0
            for (o in 0 until p.S) {
                if (o == i || !p.canDo(o, n)) continue
                var d = 0
                while (d < p.T) {
                    if (shouldStop() || evaluations >= maxEvaluations) return false
                    if (work[o][d] != n) { d++; continue }
                    val r2 = runOf(o, d); d = r2.last + 1
                    if (r2.count() != r1.count() || r2.first <= r1.last && r1.first <= r2.last) continue
                    if (!windowsExchangeable(i, o, r1) || !windowsExchangeable(i, o, r2)) continue
                    partners++
                    val snapshot = work.copy2D()
                    swapWindows(i, o, r1, r2)
                    val pruned = boundaryForbidden(i, o, r1, r2)
                    swapWindows(i, o, r1, r2)
                    if (pruned) { prunedC3n++; continue }
                    // 候補列: 交換のみ → 交換＋各付替えセルを担当可能な別シフトへ（禁止の並びを作らないもの）
                    val moves = ArrayList<Pair<Int, Int>?>(); moves.add(null)
                    for (j in reassign) {
                        // 付替えは希望固定でなく、かつ元シフトの被覆をその日で欠かさないセルだけ（欠く手は covU で必ず負ける）。
                        if (j in r1 || j in r2 || p.wishLocked(i, j)) continue
                        val fromK = work[i][j]; if (fromK !in 0 until p.K) continue
                        var cnt = 0; for (s in 0 until p.S) if (work[s][j] == fromK) cnt++
                        if (p.covUCell(fromK, j, cnt - 1) > p.covUCell(fromK, j, cnt)) continue
                        for (alt in p.allowedShiftsForStaff(i)) if (alt != fromK) moves.add(j to alt)
                    }
                    for (mv in moves) {
                        if (shouldStop() || evaluations >= maxEvaluations) return false
                        swapWindows(i, o, r1, r2)
                        if (mv != null) {
                            if (p.makesForbiddenRun(work, i, mv.first, mv.second)) { swapWindows(i, o, r1, r2); continue }
                            work[i][mv.first] = mv.second
                        }
                        candidates++; evaluations++
                        val rep = UnifiedViolationChecker.check(state, work)
                        val pinBad = exactPinRegression(p, snapshot, work)
                        if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, snapshot, work)
                        if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
                        rejectCulprits.record(rep, bestRep, pinBad)
                        for (s in 0 until p.S) System.arraycopy(snapshot[s], 0, work[s], 0, p.T)
                    }
                }
            }
            if (partners == 0) noPartner++
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop() || evaluations >= maxEvaluations) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            // アンカー: (職員, 交換候補の連, 付替え候補セル)。セル違反→前日/当日/翌日を含む連＋当日、回数違反→行の全連＋超過シフトのセル。
            data class Anchor(val i: Int, val runs: List<IntRange>, val reassign: List<Int>)
            val anchors = ArrayList<Anchor>()
            for ((key, _) in rep0.cellFamilies) {
                val parts = key.split(","); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (i !in 0 until p.S || j !in 0 until p.T) continue
                val runs = listOfNotNull(j - 1, j, j + 1).filter { it in 0 until p.T && work[i][it] in 0 until p.K }.map { runOf(i, it) }.distinct()
                anchors.add(Anchor(i, runs, listOf(j)))
            }
            for ((key, cls) in rep0.countViolations) {
                val parts = key.split(","); val i = parts.getOrNull(0)?.toIntOrNull() ?: continue; val k = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (i !in 0 until p.S) continue
                val runs = ArrayList<IntRange>(); var d = 0
                while (d < p.T) { if (work[i][d] !in 0 until p.K) { d++; continue }; val r = runOf(i, d); runs.add(r); d = r.last + 1 }
                // 超過（high/aptHigh）はそのシフトのセルを付替え候補に。不足（low/aptLow）は交換だけで回数が動くのを狙う。
                val reassign = if (cls == "vio-high" || cls == "vio-aptHigh") (0 until p.T).filter { work[i][it] == k } else emptyList()
                anchors.add(Anchor(i, runs, reassign))
            }
            if (anchors.isEmpty()) break
            for (a in anchors) {
                if (shouldStop() || evaluations >= maxEvaluations) break
                anchorsSeen++
                var done = false
                for (r in a.runs) { if (done) break; if (tryExchange(a.i, r, a.reassign)) { done = true; improved = true } }
                if (!done) stuck.add(nameOf(a.i))
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "RunSwapPolish",
            message = "連交換研磨(違反に隣接する同一シフトの連を他職員の同じ長さの連と窓ごと交換): 対象${anchorsSeen}件 候補${candidates}手 正式評価${evaluations}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && anchorsSeen > 0) " [頭打ち=改善手なし]" else "") +
                (if (evaluations >= maxEvaluations) " 評価上限${maxEvaluations}到達" else "") +
                (if (noPartner > 0) " 交換相手なし${noPartner}連" else "") +
                (if (prunedC3n > 0) " 境界の禁止の並びで枝刈り${prunedC3n}組" else "") +
                rejectCulprits.summary() +
                (if (stuck.isNotEmpty()) " 残存: ${stuck.take(8).joinToString(", ")}${if (stuck.size > 8) " ほか${stuck.size - 8}名" else ""}" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }
}
