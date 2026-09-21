package com.magi.app.v6

import com.magi.app.model.MagiState

/**
 * 休の必要人数を明示した日に休が余るとき、その前後の窓（前 4 日・後 2 日）を各職員の窓内シフトの並べ替えで組み直す（回数は職員ごとに不変）。
 * 外側で夜勤型シフト（翌日が自分か休に限られる）の担当列を列挙し、窓の外の同日交換で夜勤を人から人へ移す候補も試し、
 * 内側で残りをビーム探索、完成盤面を正式チェッカーで keep-best 判定する。経緯と実データの測定は history 3.555.0。
 */
internal object RestZeroWindowLns {
    data class Result(val newSchedule: Array<IntArray>, val applied: Int, val logs: List<MirrorLog>, val report: ViolationReport? = null)

    data class Config(
        val before: Int = 4,
        val after: Int = 2,
        val maxWindow: Int = 8,
        val beamWidth: Int = 32,
        val maxChildren: Int = 160,
        val maxLeafChecks: Int = 6,
        val maxEvaluations: Int = 60_000,
        val maxNightSequences: Int = 24,
        /** 夜勤の人間移動（窓の外の同じ日で A: x→夜勤・B: 夜勤→x を入れ替え、窓内で B が夜勤を持つ）の候補数の上限。 */
        val maxTransfers: Int = 12,
        val seed: Long = 0x5E57L,
    )

    private class Node(val board: Array<IntArray>, val remain: Array<IntArray>, val score: Long)

    fun apply(
        state: MagiState, schedule: Array<IntArray>, config: Config = Config(),
        shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false,
    ): Result {
        val p = Problem(state, quantitativeRangeEval)
        // [3.603.0] 休シフト未設定ならno-op（このパス自体が「休が余る窓」を扱うので前提が成立しない）。
        val rest = p.restIdx ?: return Result(schedule, 0, emptyList())
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var bestRep = before
        val eval = Evaluator(p)
        // 途中盤面の推定: 未割当の日は旧値のままなので、境界をまたぐ禁止連続や回数系（低/高・apt・fair・weekly・c2）は
        // 子ごとに歪む。日内で決まる族（被覆・c1・Want 連・群ペア）だけを重み付きで足し、必須は正式検証に任せる。
        val estBd = HashMap<String, Long>()
        val estFams = listOf("c1", "c3", "c3m", "c3mn", "c41", "c42", "c41s", "c42s", "covO", "covU")
        fun estimate(board: Array<IntArray>): Long {
            eval.fullEvalParts(board, estBd)
            var s = 0L
            for (f in estFams) s += ((estBd[f] ?: 0L) * MirrorKeys.weightOf(f)).toLong()
            return s
        }
        var evaluations = 0
        var applied = 0
        val notes = ArrayList<String>()

        if (rest !in 0 until p.K) return Result(work, 0, listOf(MirrorLog(tag = "RestZeroLNS", message = "休0日の窓LNS: 休シフトなし=スキップ")), report = before)
        fun restCount(board: Array<IntArray>, j: Int): Int { var c = 0; for (i in 0 until p.S) if (board[i][j] == rest) c++; return c }
        fun explicitRestNeed(j: Int): Boolean = p.need1[rest][j] >= 0 || (p.use2 && p.need2[rest][j] >= 0)
        val targets = (0 until p.T).filter { j ->
            explicitRestNeed(j) && p.covOCell(rest, j, restCount(work, j)) > 0 && (0 until p.S).any { work[it][j] == rest && !p.wishLocked(it, j) }
        }
        if (targets.isEmpty()) return Result(work, 0, listOf(MirrorLog(tag = "RestZeroLNS", message = "休0日の窓LNS: 対象日なし（休の必要人数が明示された日に非希望の休の過剰なし）")), report = before)

        // 「翌日が自分か休に限られる」シフト＝夜勤型（2 長の禁止連続 [k, m] から求める）。
        val nightLike = (0 until p.K).filter { k ->
            k != rest && (0 until p.K).all { m -> m == k || m == rest || p.cons3n.any { c -> c.seq.size == 2 && c.seq[0] == k && c.seq[1] == m } }
        }

        fun backwardForbidden(board: Array<IntArray>, i: Int, j: Int, k: Int): Boolean {
            if (p.c3wBanned(i, j, k)) return true
            for (c in p.cons3n) {
                val seq = c.seq; val d = seq.size
                if (d == 0 || j - d + 1 < 0) continue
                var z = 0
                for (l in 0 until d) { val v = if (l == d - 1) k else board[i][j - d + 1 + l]; if (v == seq[l]) z++ }
                if (z == d) return true
            }
            return false
        }

        // 窓の結合: 隣接・重複する対象日は 1 つの窓にまとめ、長すぎる窓は前側を削る。
        val windows = ArrayList<IntRange>()
        for (d in targets) {
            val lo = (d - config.before).coerceAtLeast(0); val hi = (d + config.after).coerceAtMost(p.T - 1)
            val last = windows.lastOrNull()
            if (last != null && lo <= last.last + 1) windows[windows.size - 1] = last.first..maxOf(last.last, hi) else windows.add(lo..hi)
        }
        val trimmed = windows.map { w -> if (w.last - w.first + 1 > config.maxWindow) (w.last - config.maxWindow + 1)..w.last else w }

        for (w in trimmed) {
            if (shouldStop()) break
            val days = w.toList()
            val rng = kotlin.random.Random(config.seed xor (w.first * 131L + w.last))
            val free0 = Array(p.S) { i -> days.filter { j -> !p.wishLocked(i, j) && work[i][j] in 0 until p.K } }
            val remainAll = Array(p.S) { i -> IntArray(p.K).also { r -> for (j in free0[i]) r[work[i][j]]++ } }
            val windowTargets = targets.filter { it in w }

            /** 夜勤型シフト k の担当列（窓の各日に誰が k を持つか）を列挙し、対象日に強制される休が少ない順に返す。 */
            fun nightSequences(k: Int, remainBase: Array<IntArray>, boardBase: Array<IntArray>): List<IntArray> {
                val out = ArrayList<Pair<IntArray, Int>>()
                // 窓内の自由セルは未割当（-1）から始める＝同じ人の古い夜勤セルが「4 連」などの後ろ向き判定を誤らせないため。
                val seqBoard = boardBase.copy2D()
                for (i in 0 until p.S) for (j in free0[i]) seqBoard[i][j] = -1
                val remain = Array(p.S) { remainBase[it].copyOf() }
                val holder = IntArray(days.size) { -1 }
                fun needOn(j: Int): Int { var n = 0; while (n < p.S && p.covUCell(k, j, n) > 0) n++; return n }
                fun rec(idx: Int) {
                    if (out.size >= 400) return
                    if (idx == days.size) {
                        // 強制休: ブロック末尾の翌日に休が要る。対象日に落ちる数を数える。
                        var forced = 0
                        for (t in windowTargets) {
                            val ti = days.indexOf(t); if (ti <= 0) continue
                            val h = holder[ti - 1]
                            if (h >= 0 && holder[ti] != h) forced++
                        }
                        out.add(holder.copyOf() to forced); return
                    }
                    val j = days[idx]
                    val fixed = (0 until p.S).firstOrNull { i -> j !in free0[i] && boardBase[i][j] == k }
                    if (fixed != null) { holder[idx] = fixed; rec(idx + 1); holder[idx] = -1; return }
                    if (needOn(j) <= 0) { holder[idx] = -1; rec(idx + 1); return }
                    val prev = if (idx > 0) holder[idx - 1] else -1
                    val cands = (0 until p.S).filter { i -> j in free0[i] && remain[i][k] > 0 && !backwardForbidden(seqBoard, i, j, k) }
                    for (i in cands) {
                        // 前日の担当者のブロックがここで終わるなら、その人はこの日に休が要る（持ち分に休が無ければ不成立）。
                        val restFor = if (prev >= 0 && prev != i && j in free0[prev]) prev else -1
                        if (restFor >= 0 && (remain[restFor][rest] <= 0 || backwardForbidden(seqBoard, restFor, j, rest))) continue
                        val old = seqBoard[i][j]
                        seqBoard[i][j] = k; remain[i][k]--; holder[idx] = i
                        if (restFor >= 0) { seqBoard[restFor][j] = rest; remain[restFor][rest]-- }
                        rec(idx + 1)
                        if (restFor >= 0) { seqBoard[restFor][j] = -1; remain[restFor][rest]++ }
                        seqBoard[i][j] = old; remain[i][k]++; holder[idx] = -1
                    }
                }
                rec(0)
                return out.sortedWith(compareBy({ it.second }, { seq -> days.indices.count { seq.first[it] >= 0 && boardBase[seq.first[it]][days[it]] != k } })).map { it.first }
            }

            /** 固定済みセル（夜勤列）以外を日ごとにビームで組み直し、完成盤面を返す。 */
            fun runBeam(fixedCells: Map<Pair<Int, Int>, Int>, remainBase: Array<IntArray>, boardBase: Array<IntArray>): List<Node> {
                val start = boardBase.copy2D()
                for ((cell, k) in fixedCells) start[cell.first][cell.second] = k
                val free = Array(p.S) { i -> free0[i].filter { j -> (i to j) !in fixedCells } }
                val remain0 = Array(p.S) { i -> remainBase[i].copyOf().also { r -> for ((cell, k) in fixedCells) if (cell.first == i) r[k]-- } }
                var beam = listOf(Node(start, remain0, estimate(start)))
                for (j in days) {
                    if (shouldStop() || evaluations >= config.maxEvaluations) break
                    val persons = (0 until p.S).filter { i -> j in free[i] }
                    val need = IntArray(p.K) { k ->
                        var lo = 0
                        while (lo < p.S && p.covUCell(k, j, lo) > 0) lo++
                        val fixedCnt = (0 until p.S).count { i -> i !in persons && start[i][j] == k }
                        (lo - fixedCnt).coerceAtLeast(0)
                    }
                    val next = ArrayList<Node>()
                    for (node in beam) {
                        if (shouldStop() || evaluations >= config.maxEvaluations) break
                        val board = node.board
                        val assign = IntArray(p.S) { -1 }
                        val cnt = IntArray(p.K)
                        var children = 0
                        fun feasibleTail(idx: Int): Boolean {
                            for (k in 0 until p.K) {
                                val short = need[k] - cnt[k]
                                if (short <= 0) continue
                                var can = 0
                                for (q in idx until persons.size) if (node.remain[persons[q]][k] > 0) can++
                                if (can < short) return false
                            }
                            return true
                        }
                        fun rec(idx: Int) {
                            if (children >= config.maxChildren || evaluations >= config.maxEvaluations) return
                            if (idx == persons.size) {
                                for (k in 0 until p.K) if (cnt[k] < need[k]) return
                                val nb = board.copy2D(); val nr = Array(p.S) { node.remain[it].copyOf() }
                                for (i in persons) { nb[i][j] = assign[i]; nr[i][assign[i]]-- }
                                evaluations++; children++
                                next.add(Node(nb, nr, estimate(nb)))
                                return
                            }
                            if (!feasibleTail(idx)) return
                            val i = persons[idx]
                            // 子の上限で打ち切るので、選択肢の順は乱択（決定的 seed）＝先頭の人の第一候補ばかりを深掘りしない。
                            for (k in (0 until p.K).filter { k -> node.remain[i][k] > 0 }.shuffled(rng)) {
                                if (backwardForbidden(board, i, j, k)) continue
                                assign[i] = k; cnt[k]++
                                rec(idx + 1)
                                cnt[k]--; assign[i] = -1
                                if (children >= config.maxChildren) return
                            }
                        }
                        rec(0)
                    }
                    if (next.isEmpty()) return emptyList()
                    val seen = HashSet<String>()
                    beam = next.sortedBy { it.score }.filter { n -> seen.add(days.joinToString(",") { d -> (0 until p.S).joinToString("") { i -> n.board[i][d].toString() + "." } }) }.take(config.beamWidth)
                }
                return beam
            }

            // 夜勤の人間移動: 窓の外の同じ日 g で A: x→k, B: k→x を n 日ぶん入れ替える（回数・被覆は不変）。
            // 窓内では A の持ち分から k を n 減らして x を n 増やし、B は逆＝B が窓内で夜勤ブロックを持てる。
            class Transfer(val a: Int, val b: Int, val x: Int, val outside: List<Int>)
            val transfers = ArrayList<Transfer?>()
            transfers.add(null)
            if (nightLike.isNotEmpty()) {
                val k = nightLike[0]
                val cands = ArrayList<Transfer>()
                for (a in 0 until p.S) {
                    if (remainAll[a][k] <= 0) continue
                    for (b in 0 until p.S) {
                        if (b == a || !p.canDo(b, k) || free0[b].isEmpty()) continue
                        for (x in 0 until p.K) {
                            if (x == k || remainAll[b][x] <= 0 || !p.canDo(a, x)) continue
                            val gs = (0 until p.T).filter { g -> g !in w && work[a][g] == x && work[b][g] == k && !p.wishLocked(a, g) && !p.wishLocked(b, g) }
                                .sortedBy { g -> minOf(kotlin.math.abs(g - w.first), kotlin.math.abs(g - w.last)) }
                            for (n in 1..minOf(3, remainAll[a][k], remainAll[b][x], gs.size)) cands.add(Transfer(a, b, x, gs.take(n)))
                        }
                    }
                }
                // 1〜3 日の移動を混ぜて試す（近い日を優先）＝1 日だけでは夜勤ブロックを作れない盤面がある。
                val perSize = (config.maxTransfers / 3).coerceAtLeast(1)
                for (n in 1..3) transfers.addAll(cands.filter { it.outside.size == n }
                    .sortedBy { it.outside.sumOf { g -> minOf(kotlin.math.abs(g - w.first), kotlin.math.abs(g - w.last)) } }.take(perSize))
            }
            var adoptedHere = false
            var checked = 0
            var tried = 0
            var transfersTried = 0
            var bestEst = Long.MAX_VALUE
            val baseEst = estimate(work)
            var bestVerified: ViolationReport? = null
            var bestVerifiedRests = ""
            for (tr in transfers) {
                if (shouldStop() || evaluations >= config.maxEvaluations || adoptedHere) break
                val boardBase = work.copy2D()
                val remainBase = Array(p.S) { remainAll[it].copyOf() }
                if (tr != null) {
                    val k = nightLike[0]
                    for (g in tr.outside) { boardBase[tr.a][g] = k; boardBase[tr.b][g] = tr.x }
                    val n = tr.outside.size
                    remainBase[tr.a][k] -= n; remainBase[tr.a][tr.x] += n
                    remainBase[tr.b][k] += n; remainBase[tr.b][tr.x] -= n
                    transfersTried++
                }
                val fixedSets: List<Map<Pair<Int, Int>, Int>> = if (nightLike.isEmpty()) listOf(emptyMap()) else {
                    val k = nightLike[0]   // 夜勤型が複数ある盤面は先頭だけ列挙（残りはビームが扱う）
                    nightSequences(k, remainBase, boardBase).take(config.maxNightSequences).map { seq ->
                        val m = HashMap<Pair<Int, Int>, Int>()
                        for (idx in days.indices) { val h = seq[idx]; if (h >= 0 && days[idx] in free0[h]) m[h to days[idx]] = k }
                        m
                    }
                }
                for (fixed in fixedSets) {
                    if (shouldStop() || evaluations >= config.maxEvaluations) break
                    tried++
                    var checkedHere = 0
                    for (leaf in runBeam(fixed, remainBase, boardBase).sortedBy { it.score }) {
                        if (checkedHere >= config.maxLeafChecks) break
                        if (leaf.score < bestEst) bestEst = leaf.score
                        if (leaf.board.contentDeepEquals(work)) continue
                        checked++; checkedHere++
                        val rep = UnifiedViolationChecker.check(state, leaf.board, quantitativeRangeEval)
                        if (bestVerified == null || betterReport(rep, bestVerified!!)) {
                            bestVerified = rep
                            bestVerifiedRests = windowTargets.joinToString("/") { "${it + 1}:${restCount(leaf.board, it)}" }
                        }
                        if (betterReport(rep, bestRep)) {
                            for (i in 0 until p.S) for (d in 0 until p.T) work[i][d] = leaf.board[i][d]
                            bestRep = rep; applied++; adoptedHere = true
                            break
                        }
                    }
                    if (adoptedHere) break
                }
            }
            val restNow = windowTargets.joinToString("/") { "${it + 1}:${restCount(work, it)}" }
            if (adoptedHere) notes.add("窓${w.first + 1}-${w.last + 1}: 採用（休@対象日 $restNow・夜勤列${tried}本・人間移動${transfersTried}組・正式${checked}件）")
            else notes.add("窓${w.first + 1}-${w.last + 1}: 改善なし（夜勤列${tried}本・人間移動${transfersTried}組・正式${checked}件・最良推定${if (bestEst == Long.MAX_VALUE) "-" else (bestEst - baseEst).toString()}" +
                (bestVerified?.let { "・正式最良 必須${"%+d".format(it.hard - bestRep.hard)} 重み${"%+d".format((it.weightedScore - bestRep.weightedScore).toInt())}（休@対象日 $bestVerifiedRests）" } ?: "") + "）")
        }
        val msg = "休0日の窓LNS: covO ${before.breakdown["covO"] ?: 0}->${bestRep.breakdown["covO"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}窓 対象日 " +
            targets.joinToString("/") { "${it + 1}" } + (if (nightLike.isNotEmpty()) " 夜勤型=" + nightLike.joinToString("/") { state.shifts[it].kigou } else "") + " " + notes.joinToString(" | ") +
            (if (evaluations >= config.maxEvaluations) " [評価上限]" else "")
        return Result(work, applied, listOf(MirrorLog(tag = "RestZeroLNS", message = msg)), report = bestRep)
    }
}
