package probe.hard
// [2026-10-03] 最適化後に残る HARD（covU/c3n/c3w/pref/groupViol）を族・職員・日ごとに並べ、既存の診断
// （structuralHardFloor・wishConflictFloorParts・ForbiddenDiag・PreRunCheck）の床と突き合わせて
// 「構造的に消せない」か「床より多い」かに分ける調査用ハーネス。床より多い盤面には FixSuggester と
// ViolationComponentRepair を当てて、消せる一手があるかを見る。採点・探索は呼ぶだけで変えない。
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import com.magi.app.toHankakuKigou
import kotlinx.coroutines.runBlocking
import java.io.File

class Row(val case: String, val state: MagiState, val schedule: Array<IntArray>)

fun fixtures(resDir: String): List<Row> = listOf(
    "golden_state.json", "sample_state_v6.json", "blocked_covu_state.json", "sept2026_state.json",
    "oct2026_grid_state.json", "full_coverage_state.json",
).mapNotNull { f ->
    val file = File(resDir, f); if (!file.exists()) return@mapNotNull null
    val st = StateParser.parse(file.readText())
    Row(f.removeSuffix(".json").removeSuffix("_state").removeSuffix("_state_v6"), st, st.schedule.map { it.toIntArray() }.toTypedArray())
}

fun synth(prefix: String): List<Row> = probe.Cases.specs.filter { it.id.contains(prefix) }.map { sp ->
    val st = probe.Cases.build(sp)
    Row(sp.id, st, st.schedule.map { it.toIntArray() }.toTypedArray())
}

fun main(args: Array<String>) {
    val resDir = args[0]
    val outDir = File(args[1]).apply { mkdirs() }
    val which = args[2]                      // fixtures | synth:<prefix>
    val budgets = args[3].split(",").map { it.toInt() }
    val seeds = args[4].split(",").map { it.toLong() }
    val workers = args.getOrNull(5)?.toInt() ?: 4
    val rows = if (which == "fixtures") fixtures(resDir) else synth(which.removePrefix("synth:"))
    val csv = File(outDir, "runs.csv")
    val done = if (csv.exists()) csv.readLines().drop(1).map { it.split(",").take(3).joinToString(",") }.toSet() else emptySet()
    if (!csv.exists()) csv.writeText("case,budget,seed,ms,hard,weighted,total,covU,c3n,c3w,pref,groupViol,covUFloor,wishFloor,dayStrict,dayMp,wishHard,a_covU,a_wish,a_day,a_zeroCapDay,b_rest,fixHardDrop,vcrHard,hash\n")
    val detail = File(outDir, "detail.txt")
    for (r in rows) {
        val p = cachedProblem(r.state)
        val forced = V6SanityPort.forcedCovU(r.state, p)
        val covUFloor = forced.sumOf { it.amount }
        val (wishF, dayS) = V6SanityPort.wishConflictFloorParts(p)
        val (_, dayMp) = V6SanityPort.wishConflictFloorParts(p, zeroCapBinding = true)
        val pre = PreRunCheck.build(r.state, r.schedule)
        for (b in budgets) for (seed in seeds) {
            if ("${r.case},$b,$seed" in done) continue
            val t0 = System.currentTimeMillis()
            val res = runBlocking {
                V6FinalPort.handleOptimize(r.state, r.schedule.map { it.clone() }.toTypedArray(), secondsRaw = b, workers = workers,
                    allowImpossible = true, seed = seed)
            }
            val ms = System.currentTimeMillis() - t0
            val s = res.schedule
            val rep = UnifiedViolationChecker.check(r.state, s)
            val bd = rep.breakdown
            fun fam(k: String) = bd[k] ?: 0
            val sb = StringBuilder()
            val nm = { i: Int -> r.state.staff.getOrNull(i)?.name ?: "#$i" }
            val sym = { k: Int -> if (k >= 0) toHankakuKigou(r.state.shifts.getOrNull(k)?.kigou ?: "$k") else "—" }
            sb.append("=== ${r.case} budget=$b seed=$seed ms=$ms hard=${rep.hard} w=${rep.weightedScore.toLong()} total=${rep.total}\n")
            sb.append("  床: covU=$covUFloor（${forced.joinToString { "${it.shiftSymbol}:${it.amount}" + if (V6SanityPort.zeroCapInShortfall(p, it)) "[上限0絡み]" else "" }}）" +
                " 希望衝突=$wishF 日の証明(strict)=$dayS 日の証明(上限0込み)=$dayMp | PreRun 消えない=${pre.floorCount}" +
                "（希望どうし${pre.wishConflicts.size}・反映不可${pre.impossibleWishes.size}・配布不可${pre.forcedShortfalls.size}・日${pre.dayProofs.size}・本人${pre.staffProofs.size}）" +
                " 外れる=${pre.rerunClears.size} 上限0の日=${pre.zeroCapProofDays.sorted().map { it + 1 }}" +
                " 日の証明の日(strict)=${ConstraintMus.dayProofsWithoutZeroCap(p).sorted().map { it + 1 }} 本人の証明=${pre.staffProofs.map { nm(it.staff) }}\n")
            // 族ごとの一覧
            val covUBy = HashMap<Int, Int>()
            for ((key, fams) in rep.needFamilies) if ("vio-covU" in fams) {
                val (k, j) = key.split(",").map { it.toInt() }
                val got = (0 until p.S).count { s[it][j] == k }
                val amt = p.covUCell(k, j, got)
                covUBy[k] = (covUBy[k] ?: 0) + amt
                sb.append("  covU ${sym(k)} d${j + 1} 不足$amt\n")
            }
            for ((key, fams) in rep.cellFamilies) for (f in fams) if (f in listOf("vio-c3w", "vio-pref", "vio-groupViol")) {
                val (i, j) = key.split(",").map { it.toInt() }
                sb.append("  ${f.removePrefix("vio-")} ${nm(i)} d${j + 1}=${sym(s[i][j])}" +
                    (if (f == "vio-pref") " 希望=${sym(p.wish[i][j])}" else "") + "\n")
            }
            val diag = V6PortAnalyzer.diagnoseForbiddenRuns(r.state, s)
            for (run in diag.runs) sb.append("  c3n ${run.staffName} d${run.startDay + 1} ${run.seqLabel} [${run.cells.joinToString { "${it.dayLabel}=${it.shiftSymbol}:${it.escape}" }}]\n")
            // 分類（既存の床だけを使う）
            val aCovU = forced.sumOf { f -> minOf(f.amount, covUBy[f.shiftIndex] ?: 0) }
            val w = V6SanityPort.wishConflictHard(p, s)
            val wishHard = w.values.sum()
            val aWish = minOf(wishHard, wishF)
            var rest = rep.hard - aCovU - aWish
            val aDay = minOf(maxOf(rest, 0), dayS); rest -= aDay
            val aZero = minOf(maxOf(rest, 0), dayMp - dayS); rest -= aZero
            val bRest = maxOf(rest, 0)
            sb.append("  分類: a_covU=$aCovU a_wish=$aWish(wishConflictHard=$w) a_day=$aDay a_zeroCapDay=$aZero b=$bRest" +
                " allWishOrigin=${V6SanityPort.hardAllWishOrigin(p, s, rep, dayS)} ForbiddenDiag allBlocked=${diag.allBlocked}\n")
            var fixDrop = 0; var vcrHard = -1
            if (bRest > 0) {
                val sugs = FixSuggester.suggest(r.state, s, maxResults = 8, deadlineMs = 20_000L)
                fixDrop = sugs.minOfOrNull { it.deltaHard } ?: 0
                for (sg in sugs.filter { it.deltaHard < 0 }) sb.append("  FixSuggester ${sg.kind} ΔH=${sg.deltaHard} ${sg.label}\n")
                val vcr = ViolationComponentRepair.repair(r.state, s, emptyList())
                vcrHard = vcr.report?.hard ?: -1
                sb.append("  VCR: hard ${rep.hard}→$vcrHard applied=${vcr.applied}\n")
            }
            val hash = s.contentDeepHashCode()
            File(outDir, "${r.case}_${b}_$seed.json").writeText(StateParser.serialize(r.state, s))
            val line = listOf(r.case, b, seed, ms, rep.hard, rep.weightedScore.toLong(), rep.total, fam("covU"), fam("c3n"), fam("c3w"), fam("pref"), fam("groupViol"),
                covUFloor, wishF, dayS, dayMp, wishHard, aCovU, aWish, aDay, aZero, bRest, fixDrop, vcrHard, Integer.toHexString(hash)).joinToString(",")
            csv.appendText(line + "\n"); detail.appendText(sb.toString())
            System.err.println(line)
        }
    }
}
