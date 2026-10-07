package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.random.Random

/**
 * [c1 月全体の玉突き連鎖, 測定中・既定 OFF＝`PolishGate.c1EjectionChain`] c1 不足窓のセルに不足シフトを置き、
 * その手が作った歪みを「直前に触った職員の行（全日）」と「直前に触った日の列（全職員）」の中から直す手を
 * 継ぎ足す深さ優先探索。途中の盤面は HARD が一時的に増えてよく（終点だけ採否）、評価は [DeltaEvaluator] の
 * apply/undo。連鎖全体を正式 checker の `adoptionGate`（betterReport＋厳密ピン）で 1 回だけ採否する。
 *
 * 広域ビーム（[C1WindowPolish.applyC1BeamPolish]）との違い: ビームは各段で HARD≦開始を要求し、継ぎ足しは同日の
 * covU 玉突き（`findCovUChain`）に限る。ここは継ぎ足し先を日をまたいで職員の行にも広げ、途中の HARD 増を許す。
 *
 * [Origin.ALL, 測定中・既定 OFF＝`PolishGate.allFamilyEjectionChain`] 起点を正式評価器が場所を返す全族の違反へ広げる
 * （修復は v2 と同じ残存負債＝開始から増えた族）。採否・保護条件は C1 起点と同じ。
 */
internal object C1EjectionChainPolish {

    data class Config(
        val maxDepth: Int = defaultMaxDepth,
        /** 深さごとの分岐数（尽きたら最後の値）。 */
        val branching: IntArray = intArrayOf(4, 3, 2, 2, 1),
        /** 途中の盤面で許す HARD の増分。 */
        val hardSlack: Int = 2,
        val maxMillis: Long = 3_000L,
        /** 0 以下＝無制限。決定的モードではこちらで止める。 */
        val maxEvaluations: Long = 0L,
        val maxRounds: Int = 4,
        /** true＝v2（族越え）: 起点から増えている族（残存負債）を加重値の大きい順に修復対象とし、一か月全日×全職員から次の手を探す。false＝v1（直前の行と列）。 */
        val crossFamily: Boolean = defaultCrossFamily,
        /** v2: Δ評価の上位から族別差分まで調べる候補数。 */
        val familyProbe: Int = 48,
        /** 起点。C1＝c1 不足窓、ALL＝正式評価器が場所を返す全族の違反（`PolishGate.allFamilyEjectionChain`）。 */
        val origin: Origin = Origin.C1,
        /** ALL: 違反 1 箇所あたり連鎖を始める初手の数（Δ評価の良い順）。 */
        val seedBranch: Int = 2,
        /** ALL: 1 巡で起点にする違反箇所の族ごとの上限（巡ごとにずらす）。起点づくりの評価で予算を使い切らない。 */
        val unitsPerFamily: Int = 6,
        /** 起点 1 つの連鎖に使う評価数の上限（0 以下＝無制限）。1 つの起点が全予算を使わないため。 */
        val perSeedEvaluations: Long = 30_000L,
        /** 族越え（v2/ALL）で、1 セルの変更に加えて同日の 2 人・同じ職員の 2 日の入れ替えも 1 手として探す。 */
        val swapMoves: Boolean = true,
        /** 0 以下＝無制限。重複・HARD 超過で捨てた候補も数える（決定的モードの停止条件）。 */
        val maxCandidates: Long = 0L,
        /** 2 手目以降の候補を、ここまでに動かしたセルの「穴」（同じ日の全職員・同じ職員の前後 [holeRadius] 日）に絞る。
         *  false は月全体を総当たり。既定 [defaultHoleFocus]。 */
        val holeFocus: Boolean = defaultHoleFocus,
        val holeRadius: Int = 7,
        /** [holeFocus] を必須以外の族の起点だけに使う（必須の起点は月全体）。既定 [defaultHoleSoftOnly]。 */
        val holeSoftOnly: Boolean = defaultHoleSoftOnly,
    )

    /** HARD＝必須の族（c3n・covU・c3w・pref・groupViol）の違反だけを起点にする（測定中・後処理の前段で使う）。 */
    enum class Origin { C1, ALL, HARD }

    /** 測定用: 連鎖に入る直前の盤面を受け取る（前段の揺れと連鎖の効果を切り分ける）。本番では null。 */
    @Volatile internal var entryProbe: ((Array<IntArray>) -> Unit)? = null

    /** 測定用の切替（v1/v2 を同条件で比べる）。 */
    @Volatile internal var defaultCrossFamily: Boolean = true

    private val HARD_FAMILIES = setOf("c3n", "covU", "c3w", "pref", "groupViol")

    /** 測定用の切替（穴に絞った候補生成を同条件で比べる）。 */
    @Volatile internal var defaultHoleFocus: Boolean = true

    @Volatile internal var defaultHoleSoftOnly: Boolean = true

    /** 測定用の切替（深さ 2〜3 の短い連鎖を同条件で比べる）。 */
    @Volatile internal var defaultMaxDepth: Int = 8

    class Stats {
        var seeds = 0; var candidates = 0L; var evaluations = 0L; var chainsTried = 0; var accepted = 0
        var acceptedMaxDepth = 0; var dedup = 0L; var timeouts = 0; var endReason = "完了"
        var generated = 0L
        /** 起点の族 → [起点数, 評価数, 採用数, 加重スコアの減少量]。 */
        val byFamily = LinkedHashMap<String, LongArray>()
        var mismatch: String? = null
    }

    private val CAND_ORDER = compareBy<LongArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }, { it[4] }, { it[5] }, { it[6] })

    private class Seed(val family: String, val i: Int, val j: Int, val k: Int)

    /** 違反クラス名（`vio-aptLow` 等）→ 族名。 */
    private fun familyOfClass(cls: String): String = cls.removePrefix("vio-").let { if (it == "aptLow" || it == "aptHigh") "apt" else it }

    private fun familyWeighted(de: DeltaEvaluator): LongArray {
        val raw = de.familyRaw()
        val out = LongArray(FAMILIES.size + 2)
        for ((idx, f) in FAMILIES.withIndex()) out[idx] = ((raw[f] ?: 0L) * MirrorKeys.weightOf(f)).toLong()
        val (lo, hi) = de.rangeRaw()
        out[FAMILIES.size] = (lo * MirrorKeys.weightOf("low")).toLong()
        out[FAMILIES.size + 1] = (hi * MirrorKeys.weightOf("high")).toLong()
        return out
    }
    private val FAMILIES = listOf("c1", "c2", "c41", "c42", "c41s", "c42s", "c3", "c3n", "c3m", "c3mn",
        "pref", "groupViol", "c3w", "apt", "fair", "weekly", "covO", "covU")

    fun apply(
        state: MagiState, schedule: Array<IntArray>, config: Config = Config(),
        shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false, stats: Stats = Stats(),
    ): V6HotfixPasses.CyclicSwapResult {
        val t0 = EngineClock.nowMs()
        val p = Problem(state, quantitativeRangeEval)
        val work = normalizeSchedule(schedule, p)
        var rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        val before = rep
        entryProbe?.invoke(Array(p.S) { work[it].copyOf() })
        val all = config.origin != Origin.C1
        val hardOnly = config.origin == Origin.HARD
        val crossFamily = all || config.crossFamily
        val hasUnassigned = work.any { row -> row.any { it !in 0 until p.K } }
        if (hasUnassigned || (if (hardOnly) rep.hard == 0 else if (all) rep.total == 0 else p.cons1.isEmpty() || (rep.breakdown["c1"] ?: 0) == 0)) {
            return V6HotfixPasses.CyclicSwapResult(work, rep.total, rep.total, 0,
                listOf(MirrorLog(tag = "C1EjectionChain", message = "対象なし=スキップ")), report = rep)
        }
        val de = DeltaEvaluator(p)
        de.reset(work)
        val zob = Random(0xE1EC7L).let { r -> Array(p.S) { Array(p.T) { LongArray(p.K) { r.nextLong() } } } }
        // 2 本目の独立なハッシュ。1 本目だけが一致した別盤面（衝突）を既訪と誤認して枝を捨てないため。
        val zob2 = Random(0x5EC0DL).let { r -> Array(p.S) { Array(p.T) { LongArray(p.K) { r.nextLong() } } } }
        var hash = 0L
        var hash2 = 0L
        for (i in 0 until p.S) for (j in 0 until p.T) { hash = hash xor zob[i][j][work[i][j]]; hash2 = hash2 xor zob2[i][j][work[i][j]] }
        val pinBlocks = PinBlockAttribution()

        fun out(): Boolean {
            val why = when {
                shouldStop() -> "中断"
                config.maxEvaluations > 0 && stats.evaluations >= config.maxEvaluations -> "評価上限"
                config.maxCandidates > 0 && stats.generated >= config.maxCandidates -> "生成上限"
                config.maxEvaluations <= 0 && config.maxCandidates <= 0 && EngineClock.nowMs() - t0 >= config.maxMillis -> "時間切れ"
                else -> return false
            }
            stats.endReason = why
            return true
        }

        fun hardOf(score: Long) = score / SCORE_HARD_UNIT

        fun move(i: Int, j: Int, k: Int) {
            hash = hash xor zob[i][j][work[i][j]] xor zob[i][j][k]
            hash2 = hash2 xor zob2[i][j][work[i][j]] xor zob2[i][j][k]
            work[i][j] = k
            de.apply(i, j, k)
        }

        var applied = 0
        var round = 0

        fun c1Seeds(): List<Seed> {
            val seeds = ArrayList<Seed>()
            for (c in p.cons1) {
                val x = c.shiftIdx
                if (x !in 0 until p.K || c.day1 <= 0) continue
                for (i in 0 until p.S) {
                    if (!p.mayPlace(i, x)) continue
                    for (j in 0 until p.T) {
                        if (work[i][j] == x || p.wishLocked(i, j)) continue
                        if (inDeficientC1Window(p, work, i, x, c.day1, c.day2, j)) seeds.add(Seed("c1", i, j, x))
                    }
                }
            }
            return seeds
        }

        /**
         * 全族の起点: 正式評価器の場所（セル・回数(職員,シフト)・被覆(シフト,日)・偏り(職員)）ごとに関与セルを集め、
         * その中の Δ評価が良い初手を [Config.seedBranch] 個とる。族は HARD→重みの大きい順に並べ、族をまたいで
         * 1 件ずつ交互に並べる（一部の族だけで予算を使い切らない）。
         */
        fun allSeeds(): List<Seed> {
            val perFamily = LinkedHashMap<String, ArrayList<List<IntArray>>>()   // 族 → 違反 1 箇所ごとの関与セル
            fun addUnit(fam: String, cells: List<IntArray>) {
                if (hardOnly && fam !in HARD_FAMILIES) return
                perFamily.getOrPut(fam) { ArrayList() }.add(cells)
            }
            val row = { i: Int -> (0 until p.T).map { intArrayOf(i, it) } }
            val col = { j: Int -> (0 until p.S).map { intArrayOf(it, j) } }
            for ((key, classes) in rep.cellFamilies) {
                val (i, j) = key.split(",").map { it.toInt() }
                for (c in classes) { val f = familyOfClass(c); if (f != "c1") addUnit(f, listOf(intArrayOf(i, j))) }
            }
            for ((key, classes) in rep.countFamilies) {
                val i = key.split(",")[0].toInt()
                for (c in classes) addUnit(familyOfClass(c), row(i))
            }
            for ((key, classes) in rep.needFamilies) {
                val j = key.split(",")[1].toInt()
                for (c in classes) addUnit(familyOfClass(c), col(j))
            }
            for ((f, locs) in rep.distLocations) for (l in locs) addUnit(f, row(l[0]))
            val lists = ArrayList<Pair<String, List<Seed>>>()
            val c1 = c1Seeds()
            if (c1.isNotEmpty()) lists.add("c1" to c1)
            for ((f, us0) in perFamily) {
                val out = ArrayList<Seed>()
                val off = ((round - 1) * config.unitsPerFamily) % us0.size
                val us = (us0.drop(off) + us0.take(off)).take(config.unitsPerFamily)
                for (cells in us) {
                    val cand = ArrayList<LongArray>()
                    for (c in cells) {
                        val i = c[0]; val j = c[1]
                        if (p.wishLocked(i, j)) continue
                        for (k in p.allowedShiftsForStaff(i)) {
                            if (k == work[i][j]) continue
                            val sc = de.previewMove(i, j, k); stats.evaluations++; stats.generated++
                            if (hardOf(sc) > hardOf(de.score()) + config.hardSlack) continue
                            cand.add(longArrayOf(sc, i.toLong(), j.toLong(), k.toLong()))
                        }
                    }
                    cand.sortWith(compareBy<LongArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }))
                    for (c in cand.take(config.seedBranch)) out.add(Seed(f, c[1].toInt(), c[2].toInt(), c[3].toInt()))
                }
                if (out.isNotEmpty()) lists.add(f to out.distinctBy { Triple(it.i, it.j, it.k) })
            }
            lists.sortWith(compareBy<Pair<String, List<Seed>>>({ if (it.first in MirrorKeys.hard) 0 else 1 }, { -MirrorKeys.weightOf(it.first) }, { it.first }))
            val merged = ArrayList<Seed>()
            var n = 0
            while (true) {
                var any = false
                for ((_, l) in lists) if (n < l.size) { merged.add(l[n]); any = true }
                if (!any) break
                n++
            }
            return merged
        }

        while (round < config.maxRounds && !out()) {
            round++
            var improvedThisRound = false
            val seeds = if (all) allSeeds() else c1Seeds()
            for (seed in seeds) {
                if (out()) break
                val si = seed.i; val sj = seed.j; val sx = seed.k
                if (work[si][sj] == sx) continue
                if (!all && p.cons1.none { it.shiftIdx == sx && it.day1 > 0 && inDeficientC1Window(p, work, si, sx, it.day1, it.day2, sj) }) continue
                stats.seeds++
                val famStat = stats.byFamily.getOrPut(seed.family) { LongArray(4) }
                famStat[0]++
                val evalAtSeed = stats.evaluations
                fun seedSpent() = config.perSeedEvaluations > 0 && stats.evaluations - evalAtSeed >= config.perSeedEvaluations
                val baseScore = de.score()
                val baseHard = hardOf(baseScore)
                // 盤面ハッシュ→(2 本目のハッシュ, 到達した最浅の深さ)。より浅く再到達したときは残りの探索余地が増えるので再展開する。
                val visited = HashMap<Long, LongArray>()
                fun seenDepth(h: Long, h2: Long): Int? = visited[h]?.takeIf { it[0] == h2 }?.get(1)?.toInt()
                val path = ArrayList<IntArray>()   // (i, j, old, new)
                var bestScore = baseScore
                var bestPath: List<IntArray> = emptyList()
                val touched = HashSet<Int>()

                val useHole = config.holeFocus && !(config.holeSoftOnly && seed.family in HARD_FAMILIES)
                val famStart = if (crossFamily) familyWeighted(de) else LongArray(0)
                fun dfs(depth: Int, li: Int, lj: Int) {
                    val s = de.score()
                    if (s < bestScore) { bestScore = s; bestPath = path.map { it.copyOf() } }
                    if (depth >= config.maxDepth || out() || seedSpent()) return
                    // 候補 = [Δ後スコア, i, j, k, i2, j2, k2]。i2<0 は 1 セルの変更、そうでなければ 2 セルの交換。
                    val cand = ArrayList<LongArray>()
                    fun free(i: Int, j: Int) = !touched.contains(i * p.T + j) && !p.wishLocked(i, j)
                    fun keyAfter(c: LongArray): Pair<Long, Long> {
                        var h = hash; var h2 = hash2
                        var x = 1
                        while (x < 7 && c[x] >= 0) {
                            val i = c[x].toInt(); val j = c[x + 1].toInt(); val k = c[x + 2].toInt()
                            h = h xor zob[i][j][work[i][j]] xor zob[i][j][k]; h2 = h2 xor zob2[i][j][work[i][j]] xor zob2[i][j][k]
                            x += 3
                        }
                        return h to h2
                    }
                    fun admit(c: LongArray) {
                        if (hardOf(c[0]) > baseHard + config.hardSlack) return
                        val (h, h2) = keyAfter(c)
                        val seen = seenDepth(h, h2)
                        if (seen != null && seen <= depth + 1) { stats.dedup++; return }
                        stats.candidates++
                        cand.add(c)
                    }
                    fun consider(i: Int, j: Int) {
                        if (!free(i, j)) return
                        val cur = work[i][j]
                        for (k in p.allowedShiftsForStaff(i)) {
                            if (k == cur) continue
                            stats.generated++
                            val sc = de.previewMove(i, j, k); stats.evaluations++
                            admit(longArrayOf(sc, i.toLong(), j.toLong(), k.toLong(), -1, -1, -1))
                        }
                    }
                    /** 2 セルを入れ替える手（同日の 2 人／同じ職員の 2 日）。被覆や回数を保ったまま並びだけを動かせる。 */
                    fun considerSwap(i: Int, j: Int, i2: Int, j2: Int) {
                        val x = work[i][j]; val y = work[i2][j2]
                        if (x == y || !free(i, j) || !free(i2, j2) || !p.mayPlace(i, y) || !p.mayPlace(i2, x)) return
                        stats.generated++
                        de.apply(i, j, y); work[i][j] = y
                        val sc = de.previewMove(i2, j2, x)
                        de.apply(i, j, x); work[i][j] = x
                        stats.evaluations++
                        admit(longArrayOf(sc, i.toLong(), j.toLong(), y.toLong(), i2.toLong(), j2.toLong(), x.toLong()))
                    }
                    fun applyCand(c: LongArray): IntArray {
                        val olds = IntArray(2) { -1 }
                        var x = 1; var n = 0
                        while (x < 7 && c[x] >= 0) {
                            val i = c[x].toInt(); val j = c[x + 1].toInt(); val k = c[x + 2].toInt()
                            olds[n++] = work[i][j]
                            path.add(intArrayOf(i, j, work[i][j], k)); touched.add(i * p.T + j)
                            move(i, j, k)
                            x += 3
                        }
                        return olds
                    }
                    fun undoCand(c: LongArray, olds: IntArray) {
                        var x = if (c[4] >= 0) 4 else 1; var n = if (c[4] >= 0) 1 else 0
                        while (x >= 1) {
                            val i = c[x].toInt(); val j = c[x + 1].toInt()
                            move(i, j, olds[n]); path.removeAt(path.size - 1); touched.remove(i * p.T + j)
                            x -= 3; n--
                        }
                    }
                    if (crossFamily) {
                        if (useHole) {
                            // 穴＝動かしたセルの同じ日（被覆の受け皿）と同じ職員の前後の日（並び・期間の制約）。
                            val hole = java.util.BitSet(p.S * p.T)
                            for (m in path) {
                                val mi = m[0]; val mj = m[1]
                                for (s2 in 0 until p.S) hole.set(s2 * p.T + mj)
                                for (d in maxOf(0, mj - config.holeRadius)..minOf(p.T - 1, mj + config.holeRadius)) hole.set(mi * p.T + d)
                            }
                            var c = hole.nextSetBit(0)
                            while (c >= 0) { consider(c / p.T, c % p.T); c = hole.nextSetBit(c + 1) }
                            if (out() || seedSpent()) return
                            if (config.swapMoves) {
                                c = hole.nextSetBit(0)
                                while (c >= 0) {
                                    val ci = c / p.T; val cj = c % p.T
                                    // 相手も穴なら番号の小さい側からだけ数える（同じ組を二度評価しない）。
                                    for (b in 0 until p.S) if (b != ci && (!hole.get(b * p.T + cj) || b > ci)) considerSwap(ci, cj, b, cj)
                                    for (d in 0 until p.T) if (d != cj && (!hole.get(ci * p.T + d) || d > cj)) considerSwap(ci, cj, ci, d)
                                    c = hole.nextSetBit(c + 1)
                                }
                            }
                        } else {
                            for (i2 in 0 until p.S) { for (j2 in 0 until p.T) consider(i2, j2); if (out() || seedSpent()) return }
                            if (config.swapMoves) {
                                for (j2 in 0 until p.T) { for (a in 0 until p.S) for (b in a + 1 until p.S) considerSwap(a, j2, b, j2); if (out() || seedSpent()) return }
                                for (i2 in 0 until p.S) { for (a in 0 until p.T) for (b in a + 1 until p.T) considerSwap(i2, a, i2, b); if (out() || seedSpent()) return }
                            }
                        }
                        cand.sortWith(CAND_ORDER)
                        val famNow = familyWeighted(de)
                        val worse = LongArray(famNow.size) { maxOf(0L, famNow[it] - famStart[it]) }
                        if (worse.any { it > 0 }) {
                            // 残存負債が最大の族（同点は族の並び順）を第一に、負債全体の回収量を第二に並べる。
                            var focus = 0
                            for (x in worse.indices) if (worse[x] > worse[focus]) focus = x
                            val top = cand.take(config.familyProbe)
                            val gain = HashMap<LongArray, Long>()
                            val gainFocus = HashMap<LongArray, Long>()
                            for (c in top) {
                                val olds = applyCand(c)
                                val f2 = familyWeighted(de)
                                undoCand(c, olds)
                                var g = 0L
                                for (x in worse.indices) if (worse[x] > 0) g += minOf(worse[x], maxOf(0L, famNow[x] - f2[x]))
                                gain[c] = g
                                gainFocus[c] = minOf(worse[focus], maxOf(0L, famNow[focus] - f2[focus]))
                            }
                            cand.clear(); cand.addAll(top.sortedWith(compareBy<LongArray>({ -(gainFocus[it] ?: 0L) }, { -(gain[it] ?: 0L) }).then(CAND_ORDER)))
                        }
                    } else {
                        for (j2 in 0 until p.T) if (j2 != lj) consider(li, j2)
                        for (i2 in 0 until p.S) if (i2 != li) consider(i2, lj)
                        cand.sortWith(CAND_ORDER)
                    }
                    val b = config.branching[minOf(depth, config.branching.size - 1)]
                    var taken = 0
                    for (c in cand) {
                        if (taken >= b || out() || seedSpent()) break
                        val (nh, nh2) = keyAfter(c)
                        val seen = seenDepth(nh, nh2)
                        if (seen != null && seen <= depth + 1) { stats.dedup++; continue }
                        visited[nh] = longArrayOf(nh2, (depth + 1).toLong())
                        taken++
                        stats.chainsTried++
                        val olds = applyCand(c)
                        dfs(depth + 1, c[1].toInt(), c[2].toInt())
                        undoCand(c, olds)
                    }
                }

                val old0 = work[si][sj]
                path.add(intArrayOf(si, sj, old0, sx)); touched.add(si * p.T + sj)
                visited[hash] = longArrayOf(hash2, 0L)
                move(si, sj, sx)
                visited[hash] = longArrayOf(hash2, 1L)
                dfs(1, si, sj)
                move(si, sj, old0)
                path.clear()
                famStat[1] += stats.evaluations - evalAtSeed

                if (bestPath.isEmpty() || bestScore >= baseScore) continue
                val prev = Array(p.S) { work[it].copyOf() }
                for (m in bestPath) move(m[0], m[1], m[3])
                val rep2 = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
                deltaMismatch(de, rep2)?.let { diff ->
                    // 差分評価が正式評価と食い違った＝この経路の判断は信用できない。採らずに止め、最後の正式評価済み盤面を返す。
                    stats.mismatch = "起点=${seed.family}(${si},${sj}->${sx}) 手順=" + bestPath.joinToString(";") { "${it[0]},${it[1]}:${it[2]}->${it[3]}" } + " " + diff
                    for (m in bestPath.asReversed()) move(m[0], m[1], m[2])
                    stats.endReason = "差分不一致"
                }
                if (stats.mismatch != null) break
                if (adoptionGate(p, prev, work, rep2, rep, pinBlocks).accepted) {
                    famStat[2]++; famStat[3] += (rep.weightedScore - rep2.weightedScore).toLong()
                    rep = rep2; applied += bestPath.size; stats.accepted++; improvedThisRound = true
                    stats.acceptedMaxDepth = maxOf(stats.acceptedMaxDepth, bestPath.size)
                } else {
                    for (m in bestPath.asReversed()) move(m[0], m[1], m[2])
                }
            }
            if (!improvedThisRound || stats.mismatch != null) break
        }
        if (stats.endReason == "時間切れ") stats.timeouts++
        val label = if (all) "全族起点" else "期間要件(c1)起点${if (crossFamily) "v2" else "v1"}"
        val logs = listOf(MirrorLog(tag = "C1EjectionChain",
            message = "${label}玉突き連鎖[起点${stats.seeds}/生成${stats.generated}/候補${stats.candidates}/評価${stats.evaluations}/試行${stats.chainsTried}/採用${stats.accepted}/採用深さ最大${stats.acceptedMaxDepth}/重複除外${stats.dedup}/深さ${config.maxDepth}/終了${stats.endReason}/時間切れ${stats.timeouts}/${EngineClock.nowMs() - t0}ms]: " +
                "c1 ${before.breakdown["c1"] ?: 0}->${rep.breakdown["c1"] ?: 0} score ${before.weightedScore.toLong()}->${rep.weightedScore.toLong()} HARD ${before.hard}->${rep.hard} total ${before.total}->${rep.total} 族差 " +
                (before.breakdown.keys + rep.breakdown.keys).sorted().mapNotNull { f -> val d = (rep.breakdown[f] ?: 0) - (before.breakdown[f] ?: 0); if (d != 0) "$f${if (d > 0) "+" else ""}$d" else null }.joinToString(" ") +
                " 起点族別[" + stats.byFamily.entries.joinToString(" ") { (f, v) -> "$f:起点${v[0]}/評価${v[1]}/採用${v[2]}/減${v[3]}" } + "]" +
                (stats.mismatch?.let { " 差分不一致: $it" } ?: "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, rep.total, applied, logs, pinBlocks = pinBlocks, report = rep)
    }

    /** 差分評価と正式評価の全族・必須数の食い違い（一致なら null）。族は名前で突き合わせる。 */
    internal fun deltaMismatch(de: DeltaEvaluator, rep: ViolationReport): String? {
        val diffs = ArrayList<String>()
        for ((f, raw) in de.familyRaw()) if ((rep.breakdown[f] ?: 0).toLong() != raw) diffs.add("$f Δ=$raw 正式=${rep.breakdown[f] ?: 0}")
        val (lo, hi) = de.rangeRaw()
        if ((rep.breakdown["low"] ?: 0).toLong() != lo) diffs.add("low Δ=$lo 正式=${rep.breakdown["low"] ?: 0}")
        if ((rep.breakdown["high"] ?: 0).toLong() != hi) diffs.add("high Δ=$hi 正式=${rep.breakdown["high"] ?: 0}")
        if (de.score() / SCORE_HARD_UNIT != rep.hard.toLong()) diffs.add("HARD Δ=${de.score() / SCORE_HARD_UNIT} 正式=${rep.hard}")
        return diffs.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
