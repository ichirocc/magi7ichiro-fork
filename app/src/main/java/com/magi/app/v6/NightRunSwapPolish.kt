package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * [3.493.0/ユーザー指示「夜勤を他職員と交換する違反研磨のアルゴリズムを考える」] 夜勤連交換研磨。
 *
 * 対象＝**挟まれセル**: 前日が「後続に禁止の並びを持つシフト」（cons3n の先頭要素＝典型は夜勤 Dﾃ）で、翌日が
 * 本人希望で固定されているセル。禁止の並び（例 Dﾃ→A4/Aｱ/B4、Cｱ→Aｱ）のせいで、この日に置けるシフトが
 * 休か上限0のシフトしか残らず、回数違反（high/low/apt）や c3 の違反が**この日に押し込まれる**
 * （2026-10 データのモニカ 10/4＝Pｼ、古泉 10/17＝休 の実例）。1セルの付替えや同日の入替では前日の夜勤が
 * 動かないので既存パスは全部却下される。
 *
 * 手: 前日を含む夜勤の連（最大run）R1 を、同じ長さの他職員の連 R2 と**窓ごと丸ごと交換**する
 * （両窓の全セルを2者で入れ替える＝日別人数は完全保存・両者の夜勤回数も保存。他シフトの回数だけが
 * 窓の中身ぶん入れ替わる）。交換だけ／交換＋挟まれ日を担当可能な別シフトへ付替え、の両方を候補にし、
 * 正式チェッカーの keep-best（betterReport＝hard→weighted→total、厳密ピン保護つき）で最良の1手だけ採用＝退化不能。
 *
 * 実測（2026-10 データ、挟まれセル3件・候補21手）では**改善手0**＝c1（窓）・休/Dﾃ の固定回数・c3 の
 * 「Dﾃは3連」が壁で、夜勤を動かす手は全部 45（high 1回）より高くつく。ユーザー判断で「keep-best で入れる」
 * （効かないデータでは採用0＝無害、効くデータがあれば拾う）。候補生成は窓・希望固定・担当可否で枝刈りし、
 * 正式評価は `maxEvaluations` で上限を切る（後処理予算を食い潰さない）。
 */
internal object NightRunSwapPolish {

    fun applyNightRunSwapPolish(
        state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, maxEvaluations: Int = 400,
        shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        // 「夜勤らしさ」＝後続に禁止の並びを持つシフト（cons3n の先頭要素）。データに依らず一般化。
        val nightShifts = p.cons3n.mapNotNull { it.seq.firstOrNull() }.filter { it in 0 until p.K }.toSet()
        if (nightShifts.isEmpty()) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, 0,
                listOf(MirrorLog(tag = "NightRunSwapPolish", message = "夜勤連交換研磨: 後続禁止の並びを持つシフトなし=スキップ")))
        }
        val rejectCulprits = RejectCulpritStats()
        var anchorsSeen = 0; var candidates = 0; var evaluations = 0; var lockedOut = 0; var prunedC3n = 0
        val stuck = LinkedHashSet<String>()

        fun runOf(i: Int, j: Int, n: Int): IntRange {
            var a = j; var b = j
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
        // 交換後の窓の境界（窓の両端とその外側1日）に禁止の並びができていれば正式評価の前に落とす。
        //   実データの初回計測では 45 候補中 39 が「必須増(c3n)」で却下＝評価予算の大半を境界の禁止の並びに使っていた。
        //   チェッカーが最終判定なので枝刈りの見逃しは無害（安全側）。
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

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop() || evaluations >= maxEvaluations) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            // アンカー: 前日=夜勤 / 翌日=希望固定 / 当日に違反（回数 or セル）。当日が夜勤そのものなら対象外（連の内側）。
            val anchors = ArrayList<Pair<Int, Int>>()
            for (i in 0 until p.S) for (j in 1 until p.T - 1) {
                val k = work[i][j]
                if (k !in 0 until p.K || k in nightShifts) continue
                if (work[i][j - 1] !in nightShifts || !p.wishLocked(i, j + 1)) continue
                val vio = rep0.countViolations.containsKey("$i,$k") || rep0.cellFamilies.containsKey("$i,$j")
                if (vio) anchors.add(i to j)
            }
            if (anchors.isEmpty()) break
            for ((i, j) in anchors) {
                if (shouldStop() || evaluations >= maxEvaluations) break
                anchorsSeen++
                val n = work[i][j - 1]
                val r1 = runOf(i, j - 1, n)
                if (r1.any { p.wishLocked(i, it) }) { lockedOut++; stuck.add(state.staff.getOrNull(i)?.name ?: "#$i"); continue }
                val k = work[i][j]
                val alts = p.allowedShiftsForStaff(i).filter { it != k }
                var done = false
                for (o in 0 until p.S) {
                    if (done || o == i || !p.canDo(o, n)) continue
                    var d = 0
                    while (d < p.T && !done) {
                        if (work[o][d] != n) { d++; continue }
                        val r2 = runOf(o, d, n); d = r2.last + 1
                        if (r2.count() != r1.count() || r2.first <= r1.last && r1.first <= r2.last) continue
                        if (!windowsExchangeable(i, o, r1) || !windowsExchangeable(i, o, r2)) continue
                        val snapshot = work.copy2D()
                        // 候補列: 交換のみ → 交換＋挟まれ日の付替え（担当可能・禁止の並びを作らないもの）
                        val altList = listOf<Int?>(null) + alts.map { it }
                        swapWindows(i, o, r1, r2)
                        val pruned = boundaryForbidden(i, o, r1, r2)
                        swapWindows(i, o, r1, r2)
                        if (pruned) { prunedC3n++; continue }
                        for (alt in altList) {
                            if (shouldStop() || evaluations >= maxEvaluations) break
                            swapWindows(i, o, r1, r2)
                            if (alt != null) {
                                if (p.makesForbiddenRun(work, i, j, alt)) { swapWindows(i, o, r1, r2); continue }
                                work[i][j] = alt
                            }
                            candidates++; evaluations++
                            val rep = UnifiedViolationChecker.check(state, work)
                            val pinBad = exactPinRegression(p, snapshot, work)
                            if (pinBad && betterReport(rep, bestRep)) pinBlocks.record(p, snapshot, work)
                            if (betterReport(rep, bestRep) && !pinBad) { bestRep = rep; applied++; improved = true; done = true; break }
                            rejectCulprits.record(rep, bestRep, pinBad)
                            // 戻す（snapshot から復元＝alt と2窓を一度に）
                            for (s in 0 until p.S) System.arraycopy(snapshot[s], 0, work[s], 0, p.T)
                        }
                    }
                }
                if (!done) stuck.add(state.staff.getOrNull(i)?.name ?: "#$i")
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "NightRunSwapPolish",
            message = "夜勤連交換研磨(前日夜勤×翌日希望固定の挟まれセル): 対象${anchorsSeen}件 候補${candidates}手 正式評価${evaluations}" +
                " / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && anchorsSeen > 0) " [頭打ち=改善手なし]" else "") +
                (if (lockedOut > 0) " 夜勤連が希望固定で交換不可${lockedOut}件" else "") +
                (if (prunedC3n > 0) " 境界の禁止の並びで枝刈り${prunedC3n}組" else "") +
                rejectCulprits.summary() +
                (if (stuck.isNotEmpty()) " 残存: ${stuck.joinToString(", ")}" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }
}
