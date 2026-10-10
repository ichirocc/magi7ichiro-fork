package com.magi.app.v6

import com.magi.app.model.MagiState

/** 玉突き連鎖パイプライン: 残差があるときだけ安い予察（深さ 2→4）を行い、採用ゲートを通る手順が見つかった起点にだけ深い探索の予算を回す（当たりが
 *  無ければ深く探さない）。予察・深い探索は盤面を変えずに手順を集め、採用はいまの盤面で取り直して採用ゲートを通るものだけ（段の数値と測定は docs/history 3.656.0）。 */
internal object EjectionChainPipeline {
    enum class Focus { OFF, C1, SOFT, BOTH }

    /** 測定用（受け入れ条件 A2/A3 の比較腕）: 中段と深い探索を走らせず、浅い予察の当たりだけを採る。本番は false。 */
    @Volatile internal var shallowOnly: Boolean = false


    data class Config(
        val focus: Focus,
        /** 時間でなく評価回数で止める（ベンチと再現性の検証用）。 */
        val deterministic: Boolean = false,
        val shallowEvaluations: Long = 3_000L,
        val shallowMillis: Long = 120L,
        val midEvaluations: Long = 8_000L,
        val midMillis: Long = 200L,
        /** 中段を走らせる残差の下限。 */
        val midResidual: Int = 3,
        /** 深い探索の時間の上限（焦点の合計）。既定は玉突きの上限秒。 */
        val deepMaxMillis: Long = PolishGate.ejectionChainMaxMillis,
        /** 決定的モードの深い探索の評価回数（焦点の合計）。 */
        val deepEvaluations: Long = 360_000L,
        /** 入れ替え（同日の 2 人・同じ人の 2 日）も 1 手に使う。設定タブの「入れ替えも1手として使う」。 */
        val swapMoves: Boolean = PolishGate.ejectionChainSwapMoves,
        /** 採用があった巡のあと索引を作り直してもう一巡（従来の玉突きと同じ最大 [C1EjectionChainPolish.Config.maxRounds] 巡・
         *  族ごとの起点は巡ごとにずらす）。深い探索の予算は巡をまたいで共有。 */
        val repeatRounds: Boolean = PolishGate.ejectionPipelineRounds,
        /** BOTH のとき、必須の族の違反を起点にする焦点を先頭に足す（採用は同じ採用ゲート）。 */
        val hardLeg: Boolean = PolishGate.ejectionPipelineHardLeg,
    )

    /** 焦点 1 つぶんの記録。[deepRan] が真なら必ず当たり（浅＋中）が 1 件以上ある。 */
    class Telemetry(val focus: String, val round: Int = 1) {
        var residual = 0
        var skip = ""
        var seeds = 0
        var shallowEvaluations = 0L; var shallowMs = 0L; var shallowHits = 0
        var midRan = false; var midEvaluations = 0L; var midMs = 0L; var midHits = 0
        var deepRan = false; var deepBudgetMs = 0L; var deepBudgetEvaluations = 0L
        var deepEvaluations = 0L; var deepMs = 0L; var deepCandidates = 0
        var committed = 0
        var endReason = ""
        var before: ViolationReport? = null
        var after: ViolationReport? = null

        fun line(): String {
            val b = before; val a = after ?: before
            val mid = if (midRan) "評価$midEvaluations/${midMs}ms/当たり$midHits" else "なし"
            val deep = if (deepRan) "予算${if (deepBudgetEvaluations > 0) "${deepBudgetEvaluations}評価" else "${deepBudgetMs}ms"}/評価$deepEvaluations/${deepMs}ms/候補$deepCandidates" else "なし"
            return "玉突きパイプライン[${if (round > 1) "巡$round " else ""}焦点=$focus 残差=$residual 起点=$seeds 浅=評価$shallowEvaluations/${shallowMs}ms/当たり$shallowHits 中=$mid 深=$deep " +
                "採用=${committed}件 終了=$endReason]" +
                (if (b != null && a != null) ": HARD ${b.hard}->${a.hard} 合計 ${b.total}->${a.total} 重み ${b.weightedScore.toLong()}->${a.weightedScore.toLong()}" else "")
        }
    }

    private class LegOut(val work: Array<IntArray>, val report: ViolationReport, val committedCells: Int, val deepMs: Long, val deepEvaluations: Long)

    fun apply(
        state: MagiState, schedule: Array<IntArray>, config: Config,
        previousImproved: Boolean = false, deadlineMs: Long = Long.MAX_VALUE,
        shouldStop: () -> Boolean = { false }, quantitativeRangeEval: Boolean = false,
        telemetry: MutableList<Telemetry>? = null,
    ): V6HotfixPasses.CyclicSwapResult {
        val p = cachedProblem(state, quantitativeRangeEval)
        var work = normalizeSchedule(schedule, p)
        val rep0 = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        var rep = rep0
        val legs = when (config.focus) {
            Focus.OFF -> emptyList()
            Focus.C1 -> listOf(C1EjectionChainPolish.Origin.C1 to false)
            Focus.SOFT -> listOf(C1EjectionChainPolish.Origin.SOFT to false)
            Focus.BOTH -> (if (config.hardLeg) listOf(C1EjectionChainPolish.Origin.HARD to false) else emptyList()) +
                listOf(C1EjectionChainPolish.Origin.C1 to false, C1EjectionChainPolish.Origin.SOFT to true)
        }
        val pinBlocks = PinBlockAttribution()
        var deepMsLeft = config.deepMaxMillis
        var deepEvaluationsLeft = config.deepEvaluations
        var applied = 0
        val logs = ArrayList<MirrorLog>()
        val maxRounds = if (config.repeatRounds) C1EjectionChainPolish.Config().maxRounds else 1
        for (round in 1..maxRounds) {
            var committed = 0
            for ((n, leg) in legs.withIndex()) {
                val (origin, skipC1) = leg
                val share = (legs.size - n).toLong()
                val tel = Telemetry(focusName(origin), round)
                val out = runLeg(state, p, work, rep, origin, skipC1, config, previousImproved, deadlineMs, shouldStop,
                    quantitativeRangeEval, pinBlocks, deepMsLeft / share, deepEvaluationsLeft / share, tel, round - 1)
                work = out.work; rep = out.report; applied += out.committedCells; committed += tel.committed
                deepMsLeft -= out.deepMs; deepEvaluationsLeft -= out.deepEvaluations
                telemetry?.add(tel)
                logs.add(MirrorLog(tag = "EjectionPipeline", message = tel.line()))
            }
            if (committed == 0 || shouldStop()) break
        }
        return V6HotfixPasses.CyclicSwapResult(work, rep0.total, rep.total, applied, logs, pinBlocks = pinBlocks, report = rep)
    }

    private fun focusName(origin: C1EjectionChainPolish.Origin): String = when (origin) {
        C1EjectionChainPolish.Origin.C1 -> "C1"
        C1EjectionChainPolish.Origin.HARD -> "HARD"
        else -> "SOFT"
    }

    private fun residualOf(rep: ViolationReport, origin: C1EjectionChainPolish.Origin, skipC1: Boolean): Int = when (origin) {
        C1EjectionChainPolish.Origin.C1 -> rep.breakdown["c1"] ?: 0
        C1EjectionChainPolish.Origin.HARD -> rep.hard
        else -> rep.total - rep.hard - (if (skipC1) rep.breakdown["c1"] ?: 0 else 0)
    }

    private fun runLeg(
        state: MagiState, p: Problem, work0: Array<IntArray>, rep0: ViolationReport,
        origin: C1EjectionChainPolish.Origin, skipC1: Boolean, cfg: Config, previousImproved: Boolean,
        deadlineMs: Long, shouldStop: () -> Boolean, q: Boolean, pinBlocks: PinBlockAttribution,
        deepCapMs: Long, deepCapEvaluations: Long, tel: Telemetry, roundOffset: Int,
    ): LegOut {
        tel.before = rep0
        tel.residual = residualOf(rep0, origin, skipC1)
        fun skip(why: String): LegOut { tel.skip = why; tel.endReason = why; tel.after = rep0; return LegOut(work0, rep0, 0, 0L, 0L) }
        if (tel.residual == 0) return skip("no_residual")
        fun timeLeft() = EngineClock.remainingMs(deadlineMs)
        if (!cfg.deterministic && timeLeft() < cfg.shallowMillis) return skip("no_budget")
        val stop: () -> Boolean = if (cfg.deterministic) shouldStop else ({ shouldStop() || timeLeft() <= 0L })

        var seeds: List<C1EjectionChainPolish.SeedKey> = emptyList()
        C1EjectionChainPolish.apply(state, work0, C1EjectionChainPolish.Config(origin = origin, skipC1Seeds = skipC1, swapMoves = cfg.swapMoves, maxMillis = Long.MAX_VALUE,
            roundOffset = roundOffset), shouldStop = stop, quantitativeRangeEval = q, indexOnly = { seeds = it })
        tel.seeds = seeds.size
        val cands = ArrayList<C1EjectionChainPolish.PathCandidate>()
        fun probe(list: List<C1EjectionChainPolish.SeedKey>, depth: Int, evaluations: Long, millis: Long): C1EjectionChainPolish.Stats {
            val st = C1EjectionChainPolish.Stats()
            if (list.isEmpty()) return st
            C1EjectionChainPolish.apply(state, work0, C1EjectionChainPolish.Config(origin = origin, skipC1Seeds = skipC1, swapMoves = cfg.swapMoves, seedList = list,
                maxDepth = depth, branching = intArrayOf(2, 1), maxEvaluations = evaluations, maxMillis = millis,
                timeWithEvaluations = !cfg.deterministic, maxRounds = 1), shouldStop = stop, quantitativeRangeEval = q, stats = st,
                collect = { cands.add(it) })
            return st
        }

        val t1 = EngineClock.nowMs()
        val shallow = probe(seeds, 2, cfg.shallowEvaluations, cfg.shallowMillis)
        tel.shallowEvaluations = shallow.evaluations; tel.shallowMs = EngineClock.nowMs() - t1; tel.shallowHits = shallow.hitSeeds.size
        val hits = LinkedHashSet(shallow.hitSeeds)

        val midTime = cfg.deterministic || timeLeft() >= cfg.midMillis
        if (!shallowOnly && !stop() && midTime && (tel.residual >= cfg.midResidual || (hits.isEmpty() && previousImproved))) {
            val t2 = EngineClock.nowMs()
            val mid = probe(seeds.filter { it !in hits }, 4, cfg.midEvaluations, cfg.midMillis)
            tel.midRan = true; tel.midEvaluations = mid.evaluations; tel.midMs = EngineClock.nowMs() - t2; tel.midHits = mid.hitSeeds.size
            hits.addAll(mid.hitSeeds)
        }

        var deepMs = 0L
        var deepEvaluations = 0L
        if (hits.isEmpty()) {
            tel.endReason = if (stop()) "stopped" else "probe_miss"
            tel.after = rep0
            return LegOut(work0, rep0, 0, 0L, 0L)
        }
        val budgetMs = if (cfg.deterministic) 0L else minOf(deepCapMs, timeLeft() / 4)
        val budgetEvaluations = if (cfg.deterministic) deepCapEvaluations else 0L
        if (!shallowOnly && !stop() && (budgetMs > 0L || budgetEvaluations > 0L)) {
            tel.deepRan = true; tel.deepBudgetMs = budgetMs; tel.deepBudgetEvaluations = budgetEvaluations
            val before = cands.size
            val st = C1EjectionChainPolish.Stats()
            val t3 = EngineClock.nowMs()
            C1EjectionChainPolish.apply(state, work0, C1EjectionChainPolish.Config(origin = origin, skipC1Seeds = skipC1, swapMoves = cfg.swapMoves,
                seedList = seeds.filter { it in hits }, maxRounds = 1,
                maxMillis = if (cfg.deterministic) Long.MAX_VALUE else budgetMs, maxEvaluations = budgetEvaluations),
                shouldStop = stop, quantitativeRangeEval = q, stats = st, collect = { cands.add(it) })
            deepMs = EngineClock.nowMs() - t3; deepEvaluations = st.evaluations
            tel.deepMs = deepMs; tel.deepEvaluations = deepEvaluations; tel.deepCandidates = cands.size - before
        }

        // 採用: 正式評価の良い順。いまの盤面で取り直して採用ゲートを通るものだけ。
        cands.sortWith(compareBy({ it.report.hard }, { it.report.weightedScore }, { it.report.total }))
        var cur = work0
        var curRep = rep0
        var cells = 0
        for (c in cands) {
            if (shouldStop()) break
            if (c.path.any { m -> cur[m[0]][m[1]] != m[2] }) continue
            val next = Array(p.S) { cur[it].copyOf() }
            for (m in c.path) next[m[0]][m[1]] = m[3]
            val r = UnifiedViolationChecker.check(state, next, q)
            if (!adoptionGate(p, cur, next, r, curRep, pinBlocks).accepted) continue
            cur = next; curRep = r; cells += c.path.size; tel.committed++
        }
        tel.after = curRep
        tel.endReason = when {
            tel.committed > 0 -> "committed"
            stop() -> "stopped"
            else -> "deep_done"
        }
        return LegOut(cur, curRep, cells, deepMs, if (cfg.deterministic) deepEvaluations else 0L)
    }
}
