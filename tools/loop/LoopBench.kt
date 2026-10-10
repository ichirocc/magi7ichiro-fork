package probe
import com.magi.app.model.*
import com.magi.app.v6.*
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import kotlin.math.ceil
import kotlin.random.Random

/** ケース生成: 42 ケース＝規模(小/中/大)×分類(正常/過密/充足不能/希望集中/禁止連集中/c2不足2件以上/c42違反あり)×2。
 *  seed 固定で決定的。[3.524.0/backlog#12(b)] c2deficit・c42pair は C2Polish/C42FlowPolish の「検証不能」
 *  （旧: cons2不足最大1件・cons42空）を解消するための追加（既存5分類は不変。経緯は docs/history/3.4xx.md）。 */
object Cases {
    data class Spec(val id: String, val size: String, val cat: String, val s: Int, val t: Int, val seed: Long, val budgetMs: Long)
    val specs: List<Spec> = buildList {
        val sizes = listOf(Triple("small", 8, 14), Triple("medium", 16, 28), Triple("large", 30, 31))
        val cats = listOf("normal", "dense", "infeasible", "wishheavy", "forbidden", "c2deficit", "c42pair")
        var n = 0
        for ((sz, s, t) in sizes) for (c in cats) repeat(2) { k ->
            n++
            val budget = when (sz) { "small" -> 10_000L; "medium" -> 20_000L; else -> 40_000L }
            add(Spec("c%02d-%s-%s%d".format(n, sz, c, k), sz, c, s, t, 0xBE11C0DEL + n * 7919L, budget))
        }
    }

    fun build(sp: Spec): MagiState {
        val rng = Random(sp.seed)
        val S = sp.s; val T = sp.t
        val shifts = listOf(Shift("休", "休", "", "", role = ShiftRole.Rest), Shift("日勤", "A", "", ""), Shift("早番", "C", "", ""), Shift("夜勤", "B", "", ""))
        val groups = listOf(Group("ベテラン", "V"), Group("一般", "N"))
        val staff = (0 until S).map { Staff("職員%02d".format(it + 1), if (it % 3 == 0) 0 else 1) }
        // 一般は早番不可（担当可否に差を作る）
        val groupShift = listOf(listOf(1, 1, 1, 1), listOf(1, 1, 0, 1))
        val dense = sp.cat == "dense"; val infeasible = sp.cat == "infeasible"
        val f = if (dense) 1.3 else 1.0
        val needA = ceil(S * 0.33 * f).toInt(); val needC = ceil(S * 0.10 * f).toInt(); val needB = ceil(S * 0.12 * f).toInt()
        val shifts2 = listOf(shifts[0], shifts[1].copy(need1 = "$needA"), shifts[2].copy(need1 = "$needC"), shifts[3].copy(need1 = "$needB"))
        val needDay1 = HashMap<String, String>()
        if (infeasible) {
            // 夜勤の必要数を担当可能人数超えに（充足不能）
            for (j in 0 until T step 5) needDay1["3,$j"] = "${S + 2}"
        }
        val wishes = HashMap<String, Int>()
        val wishRate = if (sp.cat == "wishheavy") 0.35 else 0.10
        for (i in 0 until S) for (j in 0 until T) {
            val weekend = (j % 7 == 5 || j % 7 == 6)
            val p = if (sp.cat == "wishheavy" && weekend) 0.7 else wishRate
            if (rng.nextDouble() < p) wishes["$i,$j"] = if (rng.nextDouble() < 0.8) 0 else 1 + rng.nextInt(3)
        }
        val staffRange = HashMap<String, Range>()
        val restLo = T / 4; val restHi = T / 3 + 1
        for (i in 0 until S) staffRange["$i,0"] = Range("$restLo", "$restHi")
        for (i in 0 until S step 4) staffRange["$i,3"] = Range("1", "${maxOf(2, T / 6)}")
        val cons1 = listOf(C1Row("7", "休", "1"), C1Row("14", "休", "3"))
        val cons3n = ArrayList<C3Row>()
        cons3n.add(C3Row(listOf("B", "A"))); cons3n.add(C3Row(listOf("B", "C")))
        if (sp.cat == "forbidden") { cons3n.add(C3Row(listOf("C", "B"))); cons3n.add(C3Row(listOf("B", "B", "B"))); cons3n.add(C3Row(listOf("A", "B", "A"))); cons3n.add(C3Row(listOf("C", "C", "C"))) }
        val cons3mn = listOf(C3Row(listOf("B", "休", "B")))
        val cons41 = listOf(C41Row("V", "B", "1", "2"))
        // [3.524.0/backlog#12(b)] c2deficit: 平均達成数(needB*T/S)より3高い目標にし、大半の職員で不足2件以上を作る
        //   （既定の "1" は最大不足1件でC2Polishのバッチ化優位性を試せなかった＝iter15検証不能）。
        val c2Count = if (sp.cat == "c2deficit") maxOf(3, needB * T / maxOf(S, 1) + 3) else 1
        val cons2 = listOf(C2Row("B", "$c2Count"))
        // [3.524.0/backlog#12(b)] c42pair: V/早番(C) と N/夜勤(B) の同日共起を禁止（両方担当可・cons41のV/B強制とは
        //   独立のシフト対）にして実際の c42 違反を作る（既定は cons42 空＝C42FlowPolishを一度も検証できなかった＝iter18検証不能）。
        val cons42 = if (sp.cat == "c42pair") listOf(C42Row("V", "N", "C", "B")) else emptyList()
        val start = "2026-10-01"
        val end = java.time.LocalDate.parse(start).plusDays((T - 1).toLong()).toString()
        val schedule = List(S) { List(T) { 0 } }
        return MagiState(startDate = start, endDate = end, shifts = shifts2, groups = groups, staff = staff, use2Patterns = false,
            groupShift = groupShift, groupShiftApt = List(2) { List(4) { "" } }, schedule = schedule, wishes = wishes, staffRange = staffRange,
            needDay1 = needDay1, needDay2 = emptyMap(), cons1 = cons1, cons2 = cons2, cons3 = emptyList(), cons3n = cons3n,
            cons3m = emptyList(), cons3mn = cons3mn, cons41 = cons41, cons42 = cons42)
    }
}

data class Case(val id: String, val size: String, val cat: String, val seed: Long, val budgetMs: Long, val state: MagiState)

/** 玉突き系の段（後処理の段のキー）。CSV の ejMs はこれらの所要の合計、ejApplied は採用した連鎖で動かしたマスの数の合計（0＝空振り）。 */
val EJ_KEYS = setOf("玉突きパイプライン", "C1玉突き連鎖", "前段玉突き連鎖", "必須再玉突き連鎖", "最終段玉突き連鎖", "修復後玉突き連鎖")

/** 初期解＝最適化器（探索本体のみ・後処理なし・1 ワーカー・短い予算）の出力。両方式・全 seed で同一。 */
fun initialFor(c: Case): Array<IntArray> = kotlinx.coroutines.runBlocking {
    val sec = when (c.size) { "small" -> 2; "medium" -> 3; else -> 4 }
    V6NativeOptimizer.optimize(c.state, options = V6OptimizerOptions(algorithm = V6Algorithm.V5, totalBudgetSec = sec, workers = 1,
        softPolish = false, restarts = 0, seed = c.seed, postPolish = false)).schedule
}

fun main(args: Array<String>) {
    val out = File(args[0]); val seeds = args.getOrNull(1)?.toInt() ?: 10
    val onlyPrefix = args.getOrNull(2) ?: ""
    val resDir = args.getOrNull(3)
    val cases = ArrayList<Case>()
    for (sp in Cases.specs) cases.add(Case(sp.id, sp.size, sp.cat, sp.seed, sp.budgetMs, Cases.build(sp)))
    if (resDir != null) for ((n, f) in listOf("golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json").withIndex()) {
        val st = com.magi.app.model.StateParser.parse(File(resDir, f).readText())
        cases.add(Case("r%02d-%s".format(n + 1, f.removeSuffix("_state.json").removeSuffix("_state_v6.json")), "large", "real", 0x5EA1L + n, 40_000L, st))
    }
    val reproEvery = 1
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
    fun peakHeap(): Long = pools.sumOf { it.peakUsage?.used ?: 0L }
    fun resetPeak() = pools.forEach { it.resetPeakUsage() }
    // Iteration 2: 旧＝3.504.x のチェーン（成分修復なし）、新＝巡末尾に違反起点のトランザクション修復を足したもの（3.505.1 で既定）。
    // [Iteration 7] MAGI_BENCH_DETERMINISTIC=1 で両腕とも決定的モード（回数上限で止める＝再現性を仕様にする）。
    val det = System.getenv("MAGI_BENCH_DETERMINISTIC") == "1"
    // [3.510.0] MAGI_BENCH_FEATURE で比較する機能を選ぶ。既定（未設定）は Iteration 2 以来の「成分修復の有無」。
    val feature = System.getenv("MAGI_BENCH_FEATURE") ?: ""
    val (oldP, newP) = when (feature) {
        "c3nmargin" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c3nMarginLnsEnabled = true)
        "lnsadaptive" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, lnsAdaptive = true)
        "debtexplore" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, componentRepair = ViolationComponentRepair.Params(debtExploration = true))
        "cyclicn" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, cyclicSwapMaxK = 5)
        "restzero" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, restZeroWindowLnsEnabled = false) to V6HotfixPasses.PostOptimizationParams(deterministic = det)
        "covorelief" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, covOReliefEnabled = false) to V6HotfixPasses.PostOptimizationParams(deterministic = det)
        "c2polish" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c2PolishEnabled = true)
        "c42flow" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c42FlowPolishEnabled = true)
        "c1component" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c1ComponentRepair = true)
        "quantrange" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, quantitativeRangeEval = true)
        "combineexhaust" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, combineExhaustPairs = true)
        "aptfairtol" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true)
        // [2026-10-08] 許容 ON 同士で、無害化②（重い SOFT の 1 件増を拒む）の有無。大域フラグなので腕ごとに切り替える（下のループ）。
        "aptfairtolunguarded" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true) to V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true)
        "countchain" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, countChainEnabled = true)
        // [3.580.0/backlog#26] 既定OFFの専用修復腕を「対象違反が残る局面でだけ」再活性化する新フラグの
        //   A/B。旧=フラグ全OFF（現行既定）、新=xxxReactivateだけON（xxxEnabled自体は既定のfalseのまま）。
        "c2polishreactivate" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c2PolishReactivate = true)
        "c42flowreactivate" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c42FlowPolishReactivate = true)
        "c1componentreactivate" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c1ComponentRepairReactivate = true)
        "c3nmarginreactivate" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, c3nMarginLnsReactivate = true)
        "countchainreactivate" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, countChainReactivate = true)
        // [3.590.0/backlog#27] FairPolishの候補分類をfairTarget(生回数平均)からfairDevOfBucket黒箱観測へ揃える。
        "fairachievementdirection" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, fairAchievementDirection = true)
        // [3.597.0/backlog#30] 日ごと厳密割当の対角(自分の現シフト)を常に有限にし、置けない職員/スロットが
        //   ある日も残りを研磨できるようにする。
        "dayassignidentityfallback" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det, dayAssignIdentityFallback = true)
        // [3.608.0/backlog#34] チェーン内走行keep-best。パス単体の改善が同一チェーン内の別パスの
        //   悪化に道連れで捨てられる問題への対処＝postChainRunningKeepBestのA/B。
        "runningkeepbest" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, postChainRunningKeepBest = false) to V6HotfixPasses.PostOptimizationParams(deterministic = det, postChainRunningKeepBest = true)
        //   既定設定では各パスが自前の keep-best でチェーンが単調＝巻き戻しが構造的に起きない（230/230 盤面一致）。
        //   非単調になるのは aptFairSoftTolerance（他ソフト悪化を許容）ON のとき＝実機で道連れ棄却が起きた設定。
        "runningkeepbestaft" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true, postChainRunningKeepBest = false) to
            V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true, postChainRunningKeepBest = true)
        //   2026-09-22: 構造床>0 の盤面では keep-best が働かない実装。許容 ON 同士。
        "rkbstruct" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true, postChainRunningKeepBest = false) to
            V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true, postChainRunningKeepBest = true)
        //   N9: 巻き戻したパスの採用数を 0 と数えるか。巻き戻しは許容 ON でだけ起きるので両腕とも許容 ON。
        "n9rollback" -> V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true, postChainRollbackCountsZero = false) to
            V6HotfixPasses.PostOptimizationParams(deterministic = det, aptFairSoftTolerance = true, postChainRollbackCountsZero = true)
        // [2026-10-10] 玉突き連鎖パイプライン（利用者提示の仕様）。PolishGate の大域フラグなので腕ごとに切り替える（下のループ）。
        //   pipeoff*＝OFF（3.655.0 までの既定）対パイプライン、pipevsfull*＝従来の常時フル対パイプライン（A1）、pipevsshallow＝浅い予察だけ対全段（A2/A3）。
        //   *end＝パイプラインを最終の違反起点修復の後に置く（pipevsfullallend の旧腕は従来の「修復後の玉突き」）。
        "pipeoff", "pipeoffc1", "pipeoffsoft", "pipevsfullc1", "pipevsfullall", "pipevsshallow",
        //   *endk＝*end を探す範囲の既定（必須は月全体・他は前後7日）で測る（*end までは下のループが月全体にしていた）。
        "pipeoffend", "pipeoffendc1", "pipeoffendsoft", "pipevsfullallend", "pipevsshallowend",
        "pipeoffendk", "pipevsfullallendk", "pipevsshallowendk",
        //   [2026-10-10] 改善案（両腕ともパイプライン既定の構成）: rounds＝新腕だけ 2 巡目以降あり、hard＝新腕だけ必須の焦点あり、combo＝両方。
        //   combovsfull＝従来の常時フル（修復後の全族の玉突き）対 両方入りのパイプライン。
        "piperoundsendk", "pipehardendk", "pipecomboendk", "pipecombovsfullendk", "pipehardvsroundsendk" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det)
        // [3.618.1] 全族起点の玉突き連鎖。PolishGate の大域フラグなので腕ごとに切り替える（下のループ）。
        "ejectionall" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det)
        // [2026-10-06] 前段（HF66 直後）の玉突き連鎖。起点＝必須だけ／全族。大域フラグなので腕ごとに切り替える（下のループ）。
        "ejectionearlyhard", "ejectionearlyall", "ejectionfinalall", "ejectionearlyallretry", "ejectionafterrepair", "ejectionafterrepairhole", "ejectionafterrepairholesoft" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det)
        // [2026-10-07] 後処理チェーン先頭の局所降下（PolishGate.prePostDescent）。大域フラグなので腕ごとに切り替える（下のループ）。
        "prepostdescent" -> V6HotfixPasses.PostOptimizationParams(deterministic = det) to V6HotfixPasses.PostOptimizationParams(deterministic = det)
        else -> V6HotfixPasses.PostOptimizationParams(componentRepairEnabled = false, deterministic = det) to V6HotfixPasses.PostOptimizationParams(componentRepairEnabled = true, deterministic = det)
    }
    System.err.println("feature=${feature.ifEmpty { "componentRepair" }} deterministic=$det")
    // [3.507.6] 再開可能: 既存 CSV の (case,seed,arm) を読み、済みの行は飛ばして追記する（VM 再起動で JVM が消えても続きから）。
    val done = HashSet<String>()
    if (out.exists()) out.readLines().drop(1).forEach { l -> val c = l.split(","); if (c.size > 4) done.add(c[0] + "|" + c[3] + "|" + c[4]) }
    val fresh = !out.exists() || done.isEmpty()
    val w = java.io.FileWriter(out, !fresh).buffered()
    if (fresh) w.write("case,size,cat,seed,arm,ms,timeout,exception,oob,mismatch,hard,hardW,softW,wishRate,changed,total,weighted,peakMB,hash,repro,ejMs,ejApplied\n")
    if (done.isNotEmpty()) System.err.println("resume: ${done.size} rows already done")
    // ウォームアップ
    run { val c = cases[0]; val init = initialFor(c)
        repeat(2) { V6HotfixPasses.runPostOptimization(c.state, init.copy2D(), "warm", seed = 1L, deadlineMs = EngineClock.nowMs() + c.budgetMs, params = newP) } }
    for (sp in cases) {
        if (onlyPrefix.isNotEmpty() && !sp.id.startsWith(onlyPrefix)) continue
        val st = sp.state
        val init = initialFor(sp)
        System.err.println("init " + sp.id + " hard=" + UnifiedViolationChecker.check(st, init).hard)
        val p = Problem(st)
        val wishN = st.wishes.size
        for (seed in 0 until seeds) {
            for ((arm, prm) in listOf("old" to oldP, "new" to newP)) {
                if ("${sp.id}|$seed|$arm" in done) continue
                val byKind = feature.startsWith("pipe") && feature.endsWith("endk")
                val pf = if (byKind) feature.dropLast(1) else feature
                PolishGate.allFamilyEjectionChain = (feature == "ejectionall" && arm == "new") || (feature == "pipevsfullall" && arm == "old")
                PolishGate.c1EjectionChain = feature == "pipevsfullc1" && arm == "old"
                PolishGate.ejectionPipelineFocus = when {
                    arm == "new" && pf in setOf("pipeoff", "pipevsfullall", "pipevsshallow", "pipeoffend", "pipevsfullallend", "pipevsshallowend") -> EjectionChainPipeline.Focus.BOTH
                    pf == "pipevsshallow" || pf == "pipevsshallowend" -> EjectionChainPipeline.Focus.BOTH
                    arm == "new" && pf in setOf("pipeoffc1", "pipevsfullc1", "pipeoffendc1") -> EjectionChainPipeline.Focus.C1
                    arm == "new" && pf in setOf("pipeoffsoft", "pipeoffendsoft") -> EjectionChainPipeline.Focus.SOFT
                    pf in setOf("piperoundsend", "pipehardend", "pipecomboend", "pipehardvsroundsend") || (arm == "new" && pf == "pipecombovsfullend") -> EjectionChainPipeline.Focus.BOTH
                    // 比較の旧腕と従来の玉突きの腕はパイプラインを切る（有効だと従来の玉突きは走らない）。
                    pf.startsWith("pipe") || feature.startsWith("ejection") -> EjectionChainPipeline.Focus.OFF
                    // [3.656.0] それ以外の機能は既定（パイプライン BOTH・修復の後）のまま測る。
                    else -> EjectionChainPipeline.Focus.BOTH
                }
                PolishGate.ejectionPipelineAfterRepair = !pf.startsWith("pipe") || pf.endsWith("end")
                EjectionChainPipeline.shallowOnly = (pf == "pipevsshallow" || pf == "pipevsshallowend") && arm == "old"
                // [3.657.0] パイプラインの腕は 1 巡だけ・必須の焦点なしを基準に新腕だけ足す（3.656.0 までの腕の再現）。それ以外の機能は既定（探し直し ON）のまま測る。
                //   pipehardvsroundsendk だけは両腕とも探し直し ON（既定）で、新腕に必須の焦点を足す。
                PolishGate.ejectionPipelineRounds = if (pf.startsWith("pipe")) pf == "pipehardvsroundsend" || (arm == "new" && pf in setOf("piperoundsend", "pipecomboend", "pipecombovsfullend")) else true
                PolishGate.ejectionPipelineHardLeg = arm == "new" && pf in setOf("pipehardend", "pipecomboend", "pipecombovsfullend", "pipehardvsroundsend")
                PolishGate.hardEjectionChainEarly = feature == "ejectionearlyhard" && arm == "new"
                PolishGate.allEjectionChainEarly = (feature == "ejectionearlyall" || feature == "ejectionearlyallretry") && arm == "new"
                PolishGate.hardEjectionChainRetry = feature == "ejectionearlyallretry" && arm == "new"
                PolishGate.allEjectionChainFinal = feature == "ejectionfinalall" && arm == "new"
                PolishGate.allEjectionChainAfterRepair = (feature == "ejectionafterrepair" && arm == "new") || feature == "ejectionafterrepairhole" || feature == "ejectionafterrepairholesoft" ||
                    ((pf == "pipevsfullallend" || pf == "pipecombovsfullend") && arm == "old")
                // ejectionafterrepairhole: 両腕とも修復後の玉突きを ON にし、新腕だけ穴に絞った候補生成（旧腕＝月全体の総当たり）。
                //   従来の玉突きの腕と *end までのパイプラインの腕は月全体で測った（再現のため据え置き）。それ以外は範囲の既定のまま。
                val monthScope = feature.startsWith("ejection") || (feature.startsWith("pipe") && !byKind)
                C1EjectionChainPolish.defaultHoleFocus = !monthScope || ((feature == "ejectionafterrepairhole" || feature == "ejectionafterrepairholesoft") && arm == "new")
                C1EjectionChainPolish.defaultHoleSoftOnly = !monthScope || feature == "ejectionafterrepairholesoft"
                PolishGate.prePostDescent = feature == "prepostdescent" && arm == "new"
                AptFairPolish.heavySoftGuard = !(feature == "aptfairtolunguarded" && arm == "new")
                fun once(): List<Any> {
                    resetPeak(); System.gc()
                    val t0 = System.nanoTime()
                    var exc = 0; var res: V6PostOptimizationResult? = null
                    try { res = V6HotfixPasses.runPostOptimization(st, init.copy2D(), "bench", seed = seed.toLong() * 1000003L + 17, deadlineMs = EngineClock.nowMs() + sp.budgetMs, params = prm) }
                    catch (e: Throwable) { exc = 1 }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val peak = peakHeap() / (1024 * 1024)
                    if (res == null) return listOf(ms, if (ms > sp.budgetMs) 1 else 0, exc, 0, 0, -1, -1.0, -1.0, -1.0, -1, -1, -1.0, peak, 0, "", -1, -1)
                    val b = res.schedule
                    val oob = b.sumOf { row -> row.count { it < 0 || it >= st.shiftCount } }
                    val rep = UnifiedViolationChecker.check(st, b)
                    val mismatch = if (rep.hard != res.report.hard || rep.total != res.report.total || rep.weightedScore != res.report.weightedScore) 1 else 0
                    var hardW = 0.0; var softW = 0.0
                    for ((k, v) in rep.breakdown) { val c = v * MirrorKeys.weightOf(k); if (k in MirrorKeys.hard) hardW += c else softW += c }
                    var wishOk = 0; for ((key, k) in st.wishes) { val (i, j) = key.split(",").map { it.toInt() }; if (b[i][j] == k) wishOk++ }
                    val wishRate = if (wishN == 0) 1.0 else wishOk.toDouble() / wishN
                    var changed = 0; for (i in 0 until st.staffCount) for (j in 0 until st.dayCount) if (b[i][j] != init[i][j]) changed++
                    val ejStages = res.stageRecords.filter { it.key in EJ_KEYS }
                    // MAGI_BENCH_LOGTAGS=EjectionPipeline,POST のように指定すると、その tag の後処理ログを腕ごとに標準エラーへ出す（負けた対の経路を読む用）。
                    val logTags = System.getenv("MAGI_BENCH_LOGTAGS")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                    for (l in res.logs) if (l.tag in logTags) System.err.println("  [${sp.id} seed=$seed $arm] ${l.tag}: ${l.message}")
                    return listOf(ms, if (ms > sp.budgetMs) 1 else 0, exc, oob, mismatch, rep.hard, hardW, softW, "%.4f".format(wishRate), changed, rep.total, rep.weightedScore, peak, b.contentDeepHashCode(), "", ejStages.sumOf { it.ms }, ejStages.sumOf { it.applied })
                }
                val r = once().toMutableList()
                if (arm == "new" && seed % reproEvery == 0 && seed == 0) { val r2 = once(); r[14] = if (r2[13] == r[13]) "same" else "DIFF" }
                w.write("${sp.id},${sp.size},${sp.cat},$seed,$arm," + r.joinToString(",") + "\n"); w.flush()
                System.err.println("${sp.id} seed=$seed $arm ms=${r[0]} hard=${r[5]} w=${r[11]}")
            }
        }
    }
    w.close()
}
