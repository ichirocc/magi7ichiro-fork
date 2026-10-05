package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.RelaxTrial
import com.magi.app.v6.WishTrial
import com.magi.app.v6.formatDay

/** 日付の表記の単一ソース（利用者決定 2026-09-26）: 既定「10/8(木)」、密な所（手順・一覧）「10/8」、範囲「10/8〜10/9」。開始日が読めなければ「8日」。 */
internal object DayText {
    fun full(startDate: String, j: Int): String = formatDay(startDate, j)
    fun short(startDate: String, j: Int): String = formatDay(startDate, j).substringBefore('(')
    fun range(startDate: String, a: Int, b: Int): String = if (a == b) full(startDate, a) else "${short(startDate, a)}〜${short(startDate, b)}"
}

/** 違反マップのキーの符号化。文字列なのは保存データ互換のため。組立と分解はここだけ＝
 *  各所で `split(",")` を書くと、どちらの添字が日かを取り違えても誰も気づけない。 */
internal object VioKey {
    /** 職員×日。 */
    fun cell(staff: Int, day: Int) = "$staff,$day"
    /** 職員×シフト。 */
    fun count(staff: Int, shift: Int) = "$staff,$shift"
    /** シフト×日。 */
    fun need(shift: Int, day: Int) = "$shift,$day"

    fun first(key: String): Int? = key.substringBefore(",").toIntOrNull()
    fun second(key: String): Int? = key.substringAfter(",").toIntOrNull()

    /** セルキー・被覆キーのどちらでも「日」は後ろ側。回数キー("i,k")には日が無いので渡さないこと。 */
    fun dayOf(key: String): Int? = second(key)
}

/** 違反クラスが必須(HARD)か。**族名の完全一致**で決める＝唯一の判定規則
 *  （部分文字列一致だと `covUx` のような族が将来入ったとき静かに誤判定する）。 */
internal fun isHardCellViolation(v: String?): Boolean =
    v != null && familyOfVioClass(v) in MirrorKeys.hard

/** 破線枠にする「重いソフト族」。重み階層（low=120 / c1=50 / c3mn=90 が high=25 を上回る）と表示強度を揃える。 */
internal val heavySoftFamilies = setOf("low", "c1", "c3mn")

internal fun isHeavySoftCellViolation(v: String?): Boolean =
    v != null && (familyOfVioClass(v) in heavySoftFamilies || v == ZERO_CAP_CLASS)

/** セル("i,j")の全違反クラス（重み降順）。families 未充填の経路では最重1クラスへフォールバック。 */
internal fun cellVioClasses(ui: UiState, key: String): List<String> =
    ui.violationCellFamilies[key] ?: listOfNotNull(ui.violationCells[key])

/** フィルタを通過する最重の違反クラス。最重1クラスだけで判定すると、最重族のバケツを OFF にしたときに
 *  表示中の族が同セルに残っていても枠ごと消える。 */
internal fun visibleCellVio(ui: UiState, key: String, enabled: Set<String>): String? =
    cellVioClasses(ui, key).firstOrNull { vioVisible(it, enabled) }

/** 各バケットの「違反ロケーション数」(=箇所数、見出し『要確認 N件』と同単位)。
 *  被覆キーもセルと同じく重なった全クラスから数える（最重1クラスだけだと c41s に隠れた covO が「人員 0」になる）。 */
internal fun vioBucketLocCounts(ui: UiState): Map<String, Int> {
    val out = HashMap<String, Int>()
    fun tallyKey(classes: List<String>) {
        classes.mapNotNull { bucketOfFamily(familyOfVioClass(it)) }.toSet().forEach { b -> out[b] = (out[b] ?: 0) + 1 }
    }
    ui.violationCells.keys.forEach { key -> tallyKey(cellVioClasses(ui, key)) }
    (if (ui.needFamilies.isNotEmpty()) ui.needFamilies.keys else ui.needViolations.keys).forEach { key ->
        tallyKey(ui.needFamilies[key] ?: listOfNotNull(ui.needViolations[key]))
    }
    ui.countViolations.values.forEach { tallyKey(listOf(it)) }
    return out
}

/** 被覆キー(k,j)に重なる違反クラス全部（フィルタ通過分）。`needViolations` は**最重1クラスだけ**なので、
 *  これを使わないと同じ重みの族に隠れた covO/covU を取りこぼす（実測: history 3.559.0）。 */
internal fun visibleNeedClasses(ui: UiState, shift: Int, day: Int, enabled: Set<String>): List<String> {
    val key = VioKey.need(shift, day)
    return (ui.needFamilies[key] ?: listOfNotNull(ui.needViolations[key])).filter { vioVisible(it, enabled) }
}

/** そのシフト×日の人員の状態を1語で。不足(HARD)を過剰より優先。どちらも無ければ null。 */
internal fun coverageVioAt(ui: UiState, shift: Int, day: Int, enabled: Set<String>): String? {
    val fams = visibleNeedClasses(ui, shift, day, enabled).map { familyOfVioClass(it) }
    return when { "covU" in fams -> "vio-covU"; "covO" in fams -> "vio-covO"; else -> null }
}

/** 日ヘッダの下線と同じ段階。必須が1つでもあれば実線、無くて要調整があれば破線、どちらも無ければ何も引かない。 */
internal enum class DayMark { None, Soft, Hard }

/** ある日の人員の状態＝**「不足/過剰があるか」を答える唯一の場所**。有無はチェッカー（source of
 *  truth）から、人数は診断から取り、両者が食い違わないことを `MagiViewStateTest` が固定する。 */
internal data class DayStaffing(
    val day: Int,
    val shortage: Int,
    val surplus: Int,
    val hasShortage: Boolean,
    val hasSurplus: Boolean,
    val hardVioCount: Int,
    val softVioCount: Int,
) {
    val mark: DayMark get() = when {
        hardVioCount > 0 -> DayMark.Hard
        softVioCount > 0 -> DayMark.Soft
        else -> DayMark.None
    }
}

/** 盤面から数えるだけの集計。旧実装は職員×シフトの回数を 5 箇所、日×シフトの人数を 3 箇所で個別に数えていた。 */
internal class ScheduleCounts(schedule: List<List<Int>>, staffCount: Int, dayCount: Int, shiftCount: Int) {
    /** perStaff[i][k] = 職員 i がシフト k を担当した回数。 */
    val perStaff: Array<IntArray> = Array(staffCount) { IntArray(shiftCount) }
    /** perDay[j][k] = 日 j にシフト k へ配置された人数。 */
    val perDay: Array<IntArray> = Array(dayCount) { IntArray(shiftCount) }

    init {
        for (i in 0 until staffCount) {
            val row = schedule.getOrNull(i) ?: continue
            for (j in 0 until dayCount) {
                val k = row.getOrNull(j) ?: continue
                if (k in 0 until shiftCount) { perStaff[i][k]++; perDay[j][k]++ }
            }
        }
    }

    fun staffTotal(shift: Int): Int = perStaff.sumOf { it.getOrElse(shift) { 0 } }
}

/** 画面が描くのに要る派生値を一度だけ算出して配る。各 Composable が自前で `remember{…}` すると
 *  同じ値の別々の版が並び、片方だけ壊れても誰も気づけない（3.557.0）。 */
internal class MagiViewState(val ui: UiState, val vioEnabled: Set<String> = allVioBucketKeys) {
    // 構造編集の直後は `ui.staff`（宣言上の人数）と `ui.schedule.size`（表示中の盤面）が一瞬ずれ得る。
    //   表を共有する以上、大きい方で確保しないと添字が範囲外になる（画面ごとに作っていた頃は各自が
    //   自分の基準で確保していたので起きなかった）。
    private val staffCount = maxOf(ui.staff, ui.schedule.size)
    private val dayCount = maxOf(ui.days, ui.schedule.firstOrNull()?.size ?: 0)

    val counts = ScheduleCounts(ui.schedule, staffCount, dayCount, ui.shifts)

    val c1Marks: Set<String> = c1DisplayMarks(ui)

    /** c1Band[i][j] = 職員 i の行の j 日の下に期間の制約の帯を引くか（「期間の制約」チップが OFF なら引かない）。 */
    val c1Band: Array<BooleanArray> = Array(staffCount) { BooleanArray(dayCount) }.also { b ->
        if (vioVisible("vio-c1", vioEnabled)) for (sh in ui.c1Shortages) if (sh.band && sh.staff < staffCount) {
            for (d in sh.from..minOf(sh.to, dayCount - 1)) b[sh.staff][d] = true
        }
    }

    /** 勤務表だけでは期間の制約を満たせない職員（行末の内訳を開けるようにする）。 */
    val c1Stuck: Set<Int> = ui.c1Shortages.filter { it.stuck }.map { it.staff }.toSet()

    /** セルに出す違反クラスのうちフィルタを通るもの（重み降順・c1 は表示専用の印に置き換え）。 */
    private val cellVisible: Array<Array<List<String>>> = Array(staffCount) { i ->
        Array(dayCount) { j -> displayCellClasses(ui, VioKey.cell(i, j), c1Marks).filter { vioVisible(it, vioEnabled) } }
    }

    /** cellVio[i][j] = そのセルで表示する最重の違反クラス（フィルタ通過後。無ければ null）。 */
    val cellVio: Array<Array<String?>> = Array(staffCount) { i -> Array(dayCount) { j -> cellVisible[i][j].firstOrNull() } }

    /** cellSecond[i][j] = 最重の族に隠れた 2 番目の族のクラス（セルの小さな点。無ければ null）。 */
    val cellSecond: Array<Array<String?>> = Array(staffCount) { i -> Array(dayCount) { j ->
        val top = cellVio[i][j]?.let { familyOfVioClass(it) }
        cellVisible[i][j].firstOrNull { familyOfVioClass(it) != top }
    } }

    val countBadges: Map<Int, CountBadge> = countBadges(ui, vioEnabled)

    val coverageMarks: List<List<CoverageMark>> = coverageHeaderMarks(ui, vioEnabled, dayCount)

    val days: List<DayStaffing> = buildDays()

    fun coverageAt(shift: Int, day: Int): String? = coverageVioAt(ui, shift, day, vioEnabled)

    /** 違反ナビ（＜前/次＞）が巡回する日。日ヘッダの印が付く日と同じ集合であること。 */
    val violationDays: List<Int> get() = days.filter { it.mark != DayMark.None }.map { it.day }

    private fun buildDays(): List<DayStaffing> {
        val hard = IntArray(dayCount); val soft = IntArray(dayCount)
        val short = BooleanArray(dayCount); val over = BooleanArray(dayCount)
        val keys = if (ui.needFamilies.isNotEmpty()) ui.needFamilies.keys else ui.needViolations.keys
        for (key in keys) {
            val d = VioKey.dayOf(key) ?: continue
            if (d !in 0 until dayCount) continue
            val classes = (ui.needFamilies[key] ?: listOfNotNull(ui.needViolations[key]))
                .filter { vioVisible(it, vioEnabled) }
            if (classes.isEmpty()) continue
            // 下線の段階は最重クラスで決める（重い族が実線を要求すれば実線）。
            if (classes.any { isHardCellViolation(it) }) hard[d]++ else soft[d]++
            for (cls in classes) when (familyOfVioClass(cls)) {
                "covU" -> short[d] = true
                "covO" -> over[d] = true
            }
        }
        for (i in 0 until staffCount) for (j in 0 until dayCount) {
            val v = cellVio[i][j] ?: continue
            if (isHardCellViolation(v)) hard[j]++ else soft[j]++
        }
        return (0 until dayCount).map { d ->
            val risk = ui.v6?.dayRisks?.getOrNull(d)
            DayStaffing(
                day = d,
                shortage = risk?.shortage ?: 0,
                surplus = risk?.surplus ?: 0,
                hasShortage = short[d],
                hasSurplus = over[d],
                hardVioCount = hard[d],
                softVioCount = soft[d],
            )
        }
    }
}

/** 週送りの現在週。左端の日を含む週、右端まで来ていれば最終週（最終週が 7 日未満の月は左端の日だけでは届かない）。 */
internal fun currentWeekIndex(weeks: List<List<Int>>, leftDay: Int, atEnd: Boolean): Int {
    if (weeks.isEmpty()) return 0
    if (atEnd) return weeks.size - 1
    return weeks.indexOfFirst { leftDay <= it.last() }.let { if (it < 0) weeks.size - 1 else it }
}

/** [思考誘導S3] 残っている必須違反に関わる希望 1 件（職員・日・理由）。 */
internal data class InvolvedWish(val staff: Int, val day: Int, val name: String, val reason: String)

/**
 * 必須違反に関わる希望を、名前・日付・理由つきで列挙する（職員順→日順）。関わる＝そのセルに希望違反(pref)か
 * 希望の前日の禁止(c3w)がある、または禁止の並び(c3n)が本人の希望のセルに掛かっている。
 */
internal fun involvedWishes(ui: UiState): List<InvolvedWish> =
    ui.violationCellFamilies.flatMap { (key, fams) ->
        val parts = key.split(",")
        val i = parts.getOrNull(0)?.toIntOrNull() ?: return@flatMap emptyList()
        val j = parts.getOrNull(1)?.toIntOrNull() ?: return@flatMap emptyList()
        val name = ui.staffNames.getOrNull(i) ?: "職員${i + 1}"
        buildList {
            if ("vio-pref" in fams) add(InvolvedWish(i, j, name, "希望の勤務になっていません"))
            // c3w の印は前日側のセルに付く＝ぶつかっている希望はその翌日。
            if ("vio-c3w" in fams) add(InvolvedWish(i, j + 1, name, "前日（${DayText.short(ui.startDate, j)}）に置けない勤務が入っています"))
            if ("vio-c3n" in fams && ui.wishes.containsKey(key)) add(InvolvedWish(i, j, name, "希望が禁止の並びに掛かっています"))
        }
    }.distinct().sortedWith(compareBy({ it.staff }, { it.day }))

/** [S5] 試算の候補 1 行。`locked=false`（担当できない勤務の希望）は試算ボタンを出さず [WISH_TRIAL_NOT_LOCKED] を出す。 */
internal data class WishTrialRow(val staff: Int, val day: Int, val name: String, val reason: String, val locked: Boolean, val pinned: Boolean = false)

/** [S5b] 人員不足の枠 1 つ（見出し「12日 日勤 1人不足」）と、その日に別の勤務で希望固定されている人の行（職員順）。 */
internal data class ShortfallWishGroup(val day: Int, val shift: Int, val header: String, val rows: List<WishTrialRow>)

internal data class WishTrialCandidates(val direct: List<WishTrialRow>, val shortfall: List<ShortfallWishGroup>) {
    val isEmpty: Boolean get() = direct.isEmpty() && shortfall.all { it.rows.isEmpty() }
}

internal const val WISH_TRIAL_NOT_LOCKED = "担当できない勤務の希望なので、取り消しても勤務表は変わりません。"
/** [#41] 手動固定のセルは試算の候補にしない（`lockedWishKeys` が外す）＝担当外と混ぜず、固定が理由だと言う。 */
internal const val WISH_TRIAL_PINNED = "このセルは手動固定のため、自動では変更しません。固定を外すと試算できます。"
/** 希望タブの注記（希望が必須違反の並びに掛かっているとき）。希望を変えても盤面のセルはそのまま＝黙って崩さない。 */
internal const val WISH_TAB_KEEP_NOTE = "希望を変えても勤務表のセルはそのままです（未反映になります）。もう一度つくると希望に合わせます。"
internal fun wishTabInvolvedLine(wishSymbol: String, families: List<String>): String? = when {
    "c3n" in families -> "${wishSymbol}はこの禁止の並びに関係しています。"
    "c3w" in families -> "${wishSymbol}はこの希望の前日の禁止に関係しています。"
    else -> null
}
/** [S5b] 1 枠に並べる行の上限（超えたら「ほか N人」）。 */
internal const val WISH_TRIAL_GROUP_LIMIT = 8

/**
 * [S5] 試算の候補（`docs/s5_wish_trial.md` §2.2・§2.3）。S5a＝必須違反に関わる希望を (職員, 日) で重複除去し、
 * 代表の理由を pref＞c3w＞c3n で選ぶ（他は「ほか: …」）。c3w は翌日の希望 X と、印の付く前日自身が wishLocked の希望 Y の両方。
 * 満たされない希望が希望どうしの衝突（`UiState.wishSelfConflicts`）の組に入っていれば、組のほかの希望も S5a の行にする（§2.2）。
 * S5b＝人員不足の枠の `wishPinned`（日→シフト、職員順）。S5a と重なる (職員, 日) は S5a を代表にし「ほか: 人員不足の日」を足す。
 */
internal fun wishTrialCandidates(ui: UiState): WishTrialCandidates {
    // 優先度（小さいほど代表）と「ほか」に出す短い名前。
    class Hit(val staff: Int, val day: Int, val prio: Int, val reason: String)
    val short = listOf("希望の勤務になっていない", "前日の禁止", "禁止の並び", "希望どうし")
    val hits = ui.violationCellFamilies.flatMap { (key, fams) ->
        val parts = key.split(",")
        val i = parts.getOrNull(0)?.toIntOrNull() ?: return@flatMap emptyList()
        val j = parts.getOrNull(1)?.toIntOrNull() ?: return@flatMap emptyList()
        buildList {
            if ("vio-pref" in fams) add(Hit(i, j, 0, "希望の勤務になっていません"))
            if ("vio-c3w" in fams) {
                add(Hit(i, j + 1, 1, "前日（${DayText.short(ui.startDate, j)}）に置けない勤務が入っています"))
                if (key in ui.lockedWishKeys) add(Hit(i, j, 1, "翌日（${DayText.short(ui.startDate, j + 1)}）の希望の勤務の前日に置けない勤務の希望です"))
            }
            if ("vio-c3n" in fams && ui.wishes.containsKey(key)) add(Hit(i, j, 2, "希望が禁止の並びに掛かっています"))
        }
    }
    val hitKeys = hits.map { it.staff to it.day }.toSet()
    val prefKeys = ui.violationCellFamilies.filterValues { "vio-pref" in it }.keys
    val siblings = ui.wishSelfConflicts.filter { g -> g.wishKeys.any { it in prefKeys } }.flatMap { g ->
        val pat = g.shifts.joinToString("→") { ui.shiftSymbols.getOrNull(it) ?: "?" }
        val reason = if (g.family == "c3w") "希望どうしが前日の禁止「$pat」に当たっています" else "希望どうしが禁止の並び「$pat」を作っています"
        g.days.filter { (g.staff to it) !in hitKeys }.map { Hit(g.staff, it, 3, reason) }
    }.distinctBy { it.staff to it.day }
    val pinned = ui.coverageDiag?.shortfalls.orEmpty().filter { it.wishPinned.isNotEmpty() }
        .sortedWith(compareBy({ it.dayIndex }, { it.shiftIndex }))
    val pinnedKeys = pinned.flatMap { s -> s.wishPinned.map { it to s.dayIndex } }.toSet()
    fun name(i: Int) = ui.staffNames.getOrNull(i) ?: "職員${i + 1}"
    val direct = (hits + siblings).groupBy { it.staff to it.day }.map { (sd, hs) ->
        val rep = hs.minBy { it.prio }
        val others = hs.map { it.prio }.distinct().filter { it != rep.prio }.sorted().map { short[it] } +
            (if (sd in pinnedKeys) listOf("人員不足の日") else emptyList())
        val reason = if (others.isEmpty()) rep.reason else "${rep.reason}（ほか: ${others.joinToString("・")}）"
        WishTrialRow(sd.first, sd.second, name(sd.first), reason, "${sd.first},${sd.second}" in ui.lockedWishKeys, VioKey.cell(sd.first, sd.second) in ui.manualPins)
    }.sortedWith(compareBy({ it.staff }, { it.day }))
    val directKeys = direct.map { it.staff to it.day }.toSet()
    val shortfall = pinned.map { s ->
        val rows = s.wishPinned.sorted().filter { (it to s.dayIndex) !in directKeys }.map { i ->
            val sym = ui.wishes["$i,${s.dayIndex}"]?.let { ui.shiftSymbols.getOrNull(it) } ?: "別の勤務"
            WishTrialRow(i, s.dayIndex, name(i), "${sym}の希望", "$i,${s.dayIndex}" in ui.lockedWishKeys, VioKey.cell(i, s.dayIndex) in ui.manualPins)
        }
        ShortfallWishGroup(s.dayIndex, s.shiftIndex, "${s.dayLabel} ${s.shiftSymbol} ${s.miss}人不足", rows)
    }.filter { it.rows.isNotEmpty() }
    return WishTrialCandidates(direct, shortfall)
}

/** [S5] 試算結果 1 行の文（§5 の表）。止めた試算は null（数字を出さない）。 */
internal fun wishTrialText(o: WishTrial.Outcome): String? = when (o) {
    is WishTrial.Result -> when {
        o.rk >= o.h0 && o.att <= 0 -> "この希望を取り消しても、必須は減らない見込みです（必須 ${o.h0}件 → ${o.pCancel}件）。これは全探索で解けない証明ではありません。"
        o.rk >= o.h0 && o.aPrime > 0 && o.b > 0 -> "取り消すと必須違反が確実に${o.aPrime}件 減り、もう一度つくるとさらに${o.b}件 減る見込みです。"
        o.rk >= o.h0 && o.aPrime > 0 -> "取り消すと必須違反が確実に${o.aPrime}件 減ります。"
        o.rk >= o.h0 -> "取り消してもう一度つくると、必須違反が${o.b}件 減る見込みです。"
        o.att > 0 -> "もう一度つくるだけの場合より、さらに${o.att}件 減る見込みです。"
        else -> "取り消さなくても、もう一度つくるだけで同じだけ減る見込みです。"
    }
    is WishTrial.Unavailable -> "試算できませんでした（${o.reason}）。"
    else -> null
}

/** [S5] 取り消しても必須が減らない見込みの行（S6 の組があれば「希望を残したまま、設定を緩めて試す」を添える）。 */
internal fun wishTrialNoGain(o: WishTrial.Outcome): Boolean = o is WishTrial.Result && o.rk >= o.h0 && o.att <= 0

internal const val WISH_KEEP_FOOTER = "希望は、あなたが選ぶまで取り消しません。"
internal const val WISH_TO_RELAX_LABEL = "希望を残したまま、設定を緩めて試す"

/** [S5] Rk < H0 の盤面でダイアログの先頭に出す文（§5）。対照だけで減らないなら null。 */
internal fun wishTrialKeepOnlyText(control: WishTrial.Control): String? =
    if (control.rk < control.h0) "希望を残したまま、もう一度つくるだけで必須違反が${control.h0 - control.rk}件 減る見込みです。" else null

/** [S6] 起点の違反の呼び方（名前・日の範囲・族名）。ダイアログの題とホームの見出しが共有する。 */
internal data class RelaxTarget(val name: String, val span: String, val what: String)

internal fun relaxTarget(r: RelaxTrial.Result, ui: UiState): RelaxTarget {
    val name = ui.staffNames.getOrNull(r.staff) ?: "職員${r.staff + 1}"
    val fams = ui.violationCellFamilies[VioKey.cell(r.staff, r.day)].orEmpty()
    val fam = listOf("c3n", "c3w", "pref", "groupViol").firstOrNull { "vio-$it" in fams } ?: "groupViol"
    val hardDays = r.window.filter { j -> ui.violationCellFamilies[VioKey.cell(r.staff, j)].orEmpty().any { isHardCellViolation(it) } }
    val span = if (hardDays.size > 1) DayText.range(ui.startDate, hardDays.first(), hardDays.last()) else DayText.full(ui.startDate, r.day)
    return RelaxTarget(name, span, breakdownLabels[fam] ?: fam)
}

/** [S6] 試算ダイアログの文（`docs/s6_relax_trial.md` §5）。盤面は持たない＝手順は言葉だけ。 */
internal data class RelaxTrialText(
    val title: String,
    val dialogTitle: String,
    val hardLine: String,
    val scaleLine: String,
    val prerequisiteRows: List<String>,
    val lead: String,
    val rows: List<String>,
    val solveNote: String,
    val moveLines: List<String>,
    val otherMoveLines: List<String>,
    val otherMoves: Int,
    val keepNote: String?,
)

internal const val RELAX_WISH_LINE = "希望: 変更しません"
internal const val RELAX_PREREQ_HEAD = "前提として上限を上げる設定"
internal const val RELAX_PREREQ_WHY = "今の勤務表の勤務に合わせます（もう一度つくったときにその勤務が外れないため）。"
internal const val RELAX_SET_HEAD = "解消に使う設定"

/**
 * [S6] 結果を文にする。上限の行は 0→1（上げ幅は `mayPlace` を外す最小）。当てた後の回数が 2 回以上になる行は
 * 要調整（上限超過）に数えることを添える。組は探索が見つけた十分条件＝「この組で」と言い、最小とは言わない。
 * 手順は起点の窓の日をそのまま、窓の外は畳んで [RelaxTrialText.otherMoveLines] に全件（確定の前に全部読める）。
 */
internal fun relaxTrialText(r: RelaxTrial.Result, ui: UiState): RelaxTrialText {
    fun name(i: Int) = ui.staffNames.getOrNull(i) ?: "職員${i + 1}"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "?"
    val board = ui.schedule.map { it.toIntArray() }.toTypedArray()
    val after = RelaxTrial.applyMoves(board, r.moves) { i, j -> VioKey.cell(i, j) in ui.manualPins } ?: board
    val target = relaxTarget(r, ui)
    fun row(x: RelaxTrial.Relax): String {
        val n = after.getOrNull(x.staff)?.count { it == x.shift } ?: 0
        val note = if (n > x.newHi) "（この月は ${n}回になります。要調整に数えます）" else ""
        return "${name(x.staff)} ${sym(x.shift)} 上限 0回→${x.newHi}回まで$note"
    }
    val preRows = r.prerequisite.map { x ->
        val days = board.getOrNull(x.staff)?.indices?.filter { board[x.staff][it] == x.shift }.orEmpty()
        "${row(x)}（${days.joinToString("・") { DayText.short(ui.startDate, it) }} に置いてあります）"
    }
    fun dayLines(ms: List<RelaxTrial.Move>) = ms.groupBy { it.day }.toSortedMap().map { (d, xs) ->
        "${DayText.short(ui.startDate, d)}　" + xs.joinToString("、") { "${name(it.staff)} ${sym(it.from)}→${sym(it.to)}" }
    }
    val (inWin, outWin) = r.moves.partition { it.day in r.window }
    val lead = if (r.prerequisite.isEmpty()) "この組を例外として緩めると、必須違反が ${r.att}件 減る見込みです。"
        else "今の勤務表に合わせて上限を上げます。そのうえでこの組を例外として緩めると、必須違反が ${r.att}件 減る見込みです。"
    val keep = if (r.rk > r.h0) "設定をそのままにもう一度つくると、上限0の勤務が外されて必須違反が ${r.rk}件 に増えます（元の勤務表が残ります）。" else null
    val people = ((r.prerequisite + r.relaxes).map { it.staff } + r.moves.map { it.staff }).distinct().size
    val title = "${target.name} ${target.span}　${target.what}"
    return RelaxTrialText(
        title = title,
        dialogTitle = "例外として上限を緩める候補：${target.name} ${target.span} ${target.what}",
        hardLine = "必須違反: ${r.h0}件 → ${r.rr}件",
        scaleLine = "変わるもの: 設定${r.prerequisite.size + r.relaxes.size}つ、${people}人の勤務、${r.moves.size}か所",
        prerequisiteRows = preRows,
        lead = lead,
        rows = r.relaxes.map(::row),
        solveNote = "この${target.what}を解消できます。" + (if (r.rr > 0) "他の必須違反 ${r.rr}件 は残ります。" else "必須違反はなくなる見込みです。"),
        moveLines = dayLines(inWin),
        otherMoveLines = dayLines(outWin),
        otherMoves = outWin.size,
        keepNote = keep,
    )
}

/** [S6] ホームの次にやることカードの文（見出し・本文・残る件数の注記）。起点の違反と件数を名指しする。 */
internal data class RelaxCardText(val headline: String, val body: String, val note: String?)

internal const val RELAX_SEARCHING_TEXT = "希望を変えずに、個人の上限0を例外で緩める方法を調べています…"
internal const val RELAX_NO_WALL_TEXT = "緩めても解ける組はありませんでした"
internal const val RELAX_STOPPED_TEXT = "試算を止めました"
internal const val RELAX_FAILED_TEXT = "試算に失敗しました"
internal const val RELAX_RETRY_LABEL = "もう一度試す"
internal fun relaxPeekLabel(sym: String) = "例外として緩める候補: $sym"

internal fun relaxCardText(r: RelaxTrial.Result, ui: UiState): RelaxCardText {
    val t = relaxTarget(r, ui)
    return RelaxCardText(
        headline = "${t.name} ${t.span}の${t.what}（必須 ${r.h0}件中 ${r.h0 - r.rr}件）は、個人の上限0を例外で緩めると解消できる見込みです",
        body = "希望を残したまま、設定と勤務表を手順で変えられます",
        note = if (r.rr > 0) "残りの必須違反 ${r.rr}件はそのまま残ります" else null,
    )
}

/** [S6 §9] 確定の後、次にやることカードに出す 1 行。 */
internal fun relaxDoneLine(h0: Int, after: Int): String =
    "設定を緩めて手順を当てました: 必須違反 $h0 → $after。元に戻すで設定と勤務表をまとめて戻せます。"

/** 表示色だけの undo 段（[MagiViewModel.applyDisplayOnly] と元に戻す/やり直す）の判定の単一ソース。 */
internal object DisplayOnlyUndo {
    /** 変えた色の対象（記号・予約キー）。同じ対象への続けての変更だけを 1 段にまとめる目印。 */
    fun colorKey(old: Map<String, String>, new: Map<String, String>): String =
        (old.keys + new.keys).filter { old[it] != new[it] }.sorted().joinToString(",")

    /** 2 つの (設定, 盤面) の差が表示色だけか＝戻しても結果・他の案・直し方はそのまま使える。 */
    fun differsOnlyInColors(a: MagiState, aSched: Array<IntArray>, b: MagiState, bSched: Array<IntArray>): Boolean =
        aSched.contentDeepEquals(bSched) && a.copy(shiftColors = b.shiftColors) == b
}

// ===== 表示専用の印 =====
// チェッカーの場所マップ（violations/countViolations/needViolations）は探索の手掛かり（V6SwapSuggester・GLS・
//   C1WindowPolish 等）も読むので変えない。画面だけが要る印はここで報告から別に組み立てる。

/** c1 の表示専用の印（セルキー）。不足窓の中で、いま そのシフトでなく そのシフトに変えられる日だけ。 */
internal fun c1DisplayMarks(ui: UiState): Set<String> =
    ui.c1Shortages.flatMap { sh -> sh.marks.map { VioKey.cell(sh.staff, it) } }.toSet()

/** 上限0のセルの表示クラス。族は high（回数チップに従う）、枠は破線（どのセルが超過か一意なので）。 */
internal const val ZERO_CAP_CLASS = "vio-high0"

/** 個人の上限0（休を除く）のシフトが入っているセル。上限0なら入っている日はどれも超過なのでセルに印を付けられる
 *  （上限1以上の超過はどの日が余分か決まらないので名前の横の ▲ だけ）。 */
internal fun zeroCapCells(p: Problem, s: Array<IntArray>): Set<String> {
    val out = HashSet<String>()
    for (i in 0 until minOf(p.S, s.size)) for (j in s[i].indices) {
        val k = s[i][j]
        if (k in 0 until p.K && k != p.restIdx && p.rangeHi[i][k] == 0) out += VioKey.cell(i, j)
    }
    return out
}

/** 許容0の超過のセルの表示クラス（族→クラス）。人員の上限0・グループの上限0・適切回数0は、入っているセルが
 *  どれも超過なので一意に印を付けられる（上限1以上の超過はどのセルが余分か決まらないので日付/名前の印だけ）。 */
internal val ZERO_ALLOW_CLASS = mapOf("covO" to "vio-covO0", "c41" to "vio-c410", "c41s" to "vio-c41s0", "apt" to "vio-apt0")

/** 許容0の超過のセル → 表示クラス。covO は人員の上限（covOCell の基準）が0の日、c41/c41s は上限0の日、
 *  apt は実効目標0（個人設定のある組は -1 で対象外）。 */
internal fun zeroAllowCells(p: Problem, s: Array<IntArray>): Map<String, String> {
    val out = HashMap<String, String>()
    val nS = minOf(p.S, s.size)
    fun at(i: Int, j: Int) = s[i].getOrElse(j) { -1 }
    for (j in 0 until p.T) for (k in 0 until p.K) {
        val on = (0 until nS).filter { at(it, j) == k }
        if (on.isNotEmpty() && p.covOCell(k, j, on.size) == on.size) for (i in on) out.putIfAbsent(VioKey.cell(i, j), ZERO_ALLOW_CLASS.getValue("covO"))
    }
    fun groupDay(rows: List<com.magi.app.v6.C41>, grp: IntArray, fam: String) {
        for (c in rows) if (c.u == 0) for (j in 0 until p.T) for (i in 0 until nS)
            if (grp[i] == c.groupIdx && at(i, j) == c.shiftIdx) out.putIfAbsent(VioKey.cell(i, j), ZERO_ALLOW_CLASS.getValue(fam))
    }
    groupDay(p.cons41, p.sgrp, "c41")
    groupDay(p.cons41s, p.ssk, "c41s")
    for (i in 0 until nS) for (j in s[i].indices) {
        val k = s[i][j]
        if (k in 0 until p.K && p.apt[i][k] == 0) out.putIfAbsent(VioKey.cell(i, j), ZERO_ALLOW_CLASS.getValue("apt"))
    }
    return out
}

/** 画面に出すセルの違反クラス（重み降順）。チェッカーの c1（ランの先頭）は描かず、表示専用の印に置き換える。
 *  上限0のセルには表示専用の [ZERO_CAP_CLASS]、許容0の超過のセルには [zeroAllowCells] のクラスを足す。 */
internal fun displayCellClasses(ui: UiState, key: String, c1Marks: Set<String>): List<String> {
    val base = cellVioClasses(ui, key).filter { it != "vio-c1" }
    val extra = listOfNotNull("vio-c1".takeIf { key in c1Marks }, ZERO_CAP_CLASS.takeIf { key in ui.zeroCapCells }, ui.zeroAllowCells[key])
    if (extra.isEmpty()) return base
    return (base + extra).sortedByDescending { MirrorKeys.weightOf(familyOfVioClass(it)) }
}

/** 回数キーのクラスが不足側(▼)か。c2 は職員別合計の下限なので不足側。 */
internal fun isUnderCountClass(cls: String): Boolean = cls in setOf("vio-low", "vio-aptLow", "vio-c2")

/** 職員名の横の小さな印。under=▼（不足）・over=▲（超過）。 */
internal data class CountBadge(val under: Boolean, val over: Boolean) {
    val glyph: String get() = (if (under) "▼" else "") + (if (over) "▲" else "")
}

/** 回数の族（low/high/apt/c2）が 1 つでもある職員 → 印。「回数」チップが OFF なら空。 */
internal fun countBadges(ui: UiState, enabled: Set<String>): Map<Int, CountBadge> {
    val under = HashSet<Int>(); val over = HashSet<Int>()
    val keys = ui.countFamilies.keys + ui.countViolations.keys
    for (key in keys) {
        val i = VioKey.first(key) ?: continue
        for (cls in ui.countFamilies[key] ?: listOfNotNull(ui.countViolations[key])) {
            if (!vioVisible(cls, enabled)) continue
            if (isUnderCountClass(cls)) under += i else over += i
        }
    }
    return (under + over).associateWith { CountBadge(it in under, it in over) }
}

/** 日ヘッダの人員の印 1 つ＝シフトと向き（under=▼ 人員不足 / ▲ 人員過剰）。 */
internal data class CoverageMark(val shift: Int, val under: Boolean)

/** 日ごとの人員の印（不足を先、シフト順）。「人員」チップが OFF なら全日空。 */
internal fun coverageHeaderMarks(ui: UiState, enabled: Set<String>, dayCount: Int): List<List<CoverageMark>> {
    val out = List(dayCount) { ArrayList<CoverageMark>() }
    val keys = if (ui.needFamilies.isNotEmpty()) ui.needFamilies.keys else ui.needViolations.keys
    for (key in keys) {
        val k = VioKey.first(key) ?: continue
        val d = VioKey.dayOf(key) ?: continue
        if (d !in 0 until dayCount) continue
        for (cls in ui.needFamilies[key] ?: listOfNotNull(ui.needViolations[key])) {
            if (!vioVisible(cls, enabled)) continue
            when (familyOfVioClass(cls)) {
                "covU" -> out[d] += CoverageMark(k, true)
                "covO" -> out[d] += CoverageMark(k, false)
            }
        }
    }
    return out.map { m -> m.distinct().sortedWith(compareBy({ !it.under }, { it.shift })) }
}

/** 日ヘッダに出す短い字面（例「休▲」、2 つ目以降は「+N」）。列幅 36dp に収めるため 1 シフトだけ名指す。 */
internal fun coverageHeaderLabel(ui: UiState, marks: List<CoverageMark>): String {
    val m = marks.firstOrNull() ?: return ""
    val sym = ui.shiftSymbols.getOrNull(m.shift) ?: "?"
    return sym + (if (m.under) "▼" else "▲") + (if (marks.size > 1) "+${marks.size - 1}" else "")
}

/**
 * 回数の過不足の 1 枚（例「Dﾃ +2回 (6/4)」）。ref は目標（apt）か下限・上限、refLabel は下限・上限のときだけ付ける。
 * under＝不足側の色（下限割れ・適切回数の不足・個人の合計）。
 */
internal data class CountChip(val shift: String, val count: Int, val ref: Int?, val refLabel: String, val under: Boolean) {
    val delta: Int? get() = ref?.let { count - it }
    val text: String get() = "$shift  " + (delta?.let { (if (it > 0) "+" else "") + "${it}回 " } ?: "") +
        "(${count}/" + (ref?.let { refLabel + it } ?: "—") + ")"
}

/** 行末の印のシートの中身。weekly は 1 シフト 1 行、fair は「差 N回 : シフト, …」を差の大きい順。 */
internal data class StaffCountSheet(val chips: List<CountChip>, val weekly: List<String>, val fair: List<String>, val c1: List<String> = emptyList()) {
    val isEmpty: Boolean get() = c1.isEmpty() && chips.isEmpty() && weekly.isEmpty() && fair.isEmpty()
}

private val WEEKDAY_JP = listOf("日", "月", "火", "水", "木", "金", "土")

/** startDate の曜日（0=日）。`Problem.dow0` と同じ式、読めなければ 0。 */
internal fun dow0Of(startDate: String): Int =
    runCatching { java.time.LocalDate.parse(startDate).dayOfWeek.value % 7 }.getOrDefault(0)

/**
 * 曜日別の回数 wd（0=日）の偏りを 1 句で言う。e=7×回数−合計（`weeklyDevOfBucket` の各項）。
 * 平均より半回以上多い（e≥4）曜日を「集中」、半回以上少ない（e≤−4）曜日を「少ない」として名指し、両側あれば「／」で並べる。
 * どちらも無ければ最も外れた 1 日。各側の数値は round(その側の |e| の和 ÷7)。Σe=0 なので両側の和は等しく、
 * 片側は採点 Σ|e|÷7 のおよそ半分になる。
 */
internal fun weeklySkewPhrase(wd: IntArray): String? {
    val c = wd.sum()
    val e = IntArray(7) { 7 * wd[it] - c }
    if (e.all { it == 0 }) return null
    val amount = Math.round(e.filter { it > 0 }.sum() / 7.0).toInt()
    var over = (0 until 7).filter { e[it] >= 4 }
    var under = (0 until 7).filter { e[it] <= -4 }
    if (over.isEmpty() && under.isEmpty()) {
        if (e.max() >= -e.min()) over = listOf((0 until 7).maxBy { e[it] }) else under = listOf((0 until 7).minBy { e[it] })
    }
    fun names(ds: List<Int>) = ds.joinToString("・") { WEEKDAY_JP[it] }
    return listOfNotNull(
        over.takeIf { it.isNotEmpty() }?.let { "${names(it)}に集中 (+$amount)" },
        under.takeIf { it.isNotEmpty() }?.let { "${names(it)}が少ない (-$amount)" },
    ).joinToString("／")
}

/**
 * 職員 i の回数・偏りのシート（行末の印・セルシートの「この職員の回数・偏り」で共有）。
 * 回数の族は報告の回数キー、公平化・曜日は `distLocations`（セルに印を出さない族）から。
 * limits は (職員,シフト) → (下限, 上限, 目標)。null なら数値の注記を省く。
 */
internal fun staffCountSheet(ui: UiState, i: Int, limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)? = null): StaffCountSheet {
    val c1 = ui.c1Shortages.filter { it.staff == i && it.stuck }.distinctBy { it.shift }.map { sh ->
        "・${ui.shiftSymbols.getOrNull(sh.shift) ?: "${sh.shift}"}: ${breakdownLabels["c1"]}（${sh.day1}日に${sh.day2}日）— $C1_STUCK_TEXT"
    }
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val row = ui.schedule.getOrNull(i).orEmpty()
    val chips = ArrayList<CountChip>()
    val keys = (ui.countFamilies.keys + ui.countViolations.keys).filter { VioKey.first(it) == i }
        .sortedBy { VioKey.second(it) ?: 0 }
    for (key in keys) {
        val k = VioKey.second(key) ?: continue
        val count = row.count { it == k }
        val (lo, hi, apt) = limits?.invoke(i, k) ?: Triple(null, null, null)
        for (cls in ui.countFamilies[key] ?: listOfNotNull(ui.countViolations[key])) {
            chips += when (cls) {
                "vio-low" -> CountChip(sym(k), count, lo, "下限", true)
                "vio-high" -> CountChip(sym(k), count, hi, "上限", false)
                "vio-aptLow", "vio-aptHigh" -> CountChip(sym(k), count, apt, "", cls == "vio-aptLow")
                else -> CountChip(sym(k), count, null, "", isUnderCountClass(cls))
            }
        }
    }
    val dow0 = dow0Of(ui.startDate)
    val weekly = ui.distLocations["weekly"].orEmpty().filter { it.getOrNull(0) == i && it.size >= 3 }
        .sortedBy { it[1] }.mapNotNull { e ->
            val wd = IntArray(7)
            row.forEachIndexed { j, k -> if (k == e[1]) wd[(dow0 + j) % 7]++ }
            weeklySkewPhrase(wd)?.let { "${sym(e[1])} : $it" }
        }
    val fair = ui.distLocations["fair"].orEmpty().filter { it.getOrNull(0) == i && it.size >= 3 }
        .groupBy({ it[2] }, { it[1] }).toSortedMap(reverseOrder())
        .map { (d, ks) -> "差 ${d}回 : " + ks.distinct().sorted().joinToString(", ") { sym(it) } }
    return StaffCountSheet(chips, weekly, fair, c1)
}

/** セルシートの「この職員の回数・偏り」用の平文（シートと同じ中身を 1 行ずつ）。 */
internal fun staffCountLines(ui: UiState, i: Int, limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)? = null): List<String> {
    val sh = staffCountSheet(ui, i, limits)
    return sh.c1 + sh.chips.map { (if (it.under) "▼ " else "▲ ") + it.text } +
        sh.weekly.map { "${breakdownLabels["weekly"]} ${it}" } +
        sh.fair.map { "${breakdownLabels["fair"]} ${it}" }
}

/** 日 j の人員の一覧（日ヘッダの印のシート）。limits は (シフト,日) → (必要, 適正)。 */
internal fun dayCoverageLines(ui: UiState, j: Int, marks: List<CoverageMark>, limits: ((Int, Int) -> Pair<Int, Int>?)? = null): List<String> =
    marks.map { m ->
        val sym = ui.shiftSymbols.getOrNull(m.shift) ?: "${m.shift}"
        val now = ui.schedule.count { it.getOrNull(j) == m.shift }
        val lim = limits?.invoke(m.shift, j)
        if (m.under) "▼ $sym: ${breakdownLabels["covU"]}（現在 ${now}人" + (lim?.let { "・必要 ${it.first}人" } ?: "") + "）"
        else "▲ $sym: ${breakdownLabels["covO"]}（現在 ${now}人" + (lim?.let { "・適正 ${it.second}人" } ?: "") + "）"
    }

/** 凡例の「枠の形 → 族」の 1 行。セルに印を持つ族だけを名指す（回数・人員は行末と日ヘッダの印）。 */
internal fun legendShapeFamilies(): String {
    val solid = listOf("c3n", "c3w", "pref", "groupViol").map { breakdownLabels[it] ?: it }
    return "実線: ${solid.joinToString("・")}／破線: ${breakdownLabels["c1"]}（この日を○○にすると近づく）・${breakdownLabels["c3mn"]}"
}

// ===== その場の直し方探し（印・セルのシートの中で探して、見つからなければ理由と次の一歩） =====

/** 探す対象。staff/shift は `FixSuggester` の絞り込み、day はセル・日の理由の読み取りだけに使う。 */
internal data class FixFocus(val staff: Int?, val shift: Int?, val day: Int? = null, val exceptStaff: Int? = null) {
    /** 結果がどの依頼のものかを見分ける鍵（`UiState.fixDoneKey` と照合）。 */
    val key: String get() = "${staff ?: "-"},${shift ?: "-"},${day ?: "-"}" + (exceptStaff?.let { ",x$it" } ?: "")
}

/** 設定への行き先の名（節ごと。「設定を見直す」の一語で済ませない＝利用者決定 2026-09-27）。 */
internal fun settingsLabelFor(section: String): String = when (section) {
    "yr_headcount" -> "必要人数の設定を開く"
    "yr_cons" -> "並び・期間の制約の設定を開く"
    else -> "回数の設定を開く"
}

/** 手が見つからなかったときの説明。lines は確かめた事実だけ、wishRelated なら「希望を見る」を出す。 */
internal data class NoFixExplain(val lines: List<String>, val wishRelated: Boolean, val settingsSection: String)

internal const val NO_FIX_SCOPE = "1 セルの変更・2 人の入れ替え・玉突きの範囲では、必須を増やさずに違反を減らす手が見つかりませんでした。"

/**
 * 直せる手が 0 件のときの理由を盤面と設定から読む（推測は書かない）。
 * limits は (職員,シフト) → (下限, 上限, 目標)、needLimits は (シフト,日) → (必要, 適正)。
 */
internal fun noFixReasons(
    ui: UiState, f: FixFocus,
    limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)? = null,
    needLimits: ((Int, Int) -> Pair<Int, Int>?)? = null,
): NoFixExplain {
    val out = ArrayList<String>()
    var wish = false
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    fun cellAt(i: Int, j: Int) = ui.schedule.getOrNull(i)?.getOrNull(j)
    fun headcount(k: Int, j: Int) = ui.schedule.count { it.getOrNull(j) == k }
    val i = f.staff; val k = f.shift; val d = f.day
    if (i != null && d != null) {
        val cur = cellAt(i, d)
        if (cur != null && ui.wishes["$i,$d"] == cur) { out += "このセル（${DayText.short(ui.startDate, d)} の「${sym(cur)}」）は本人の希望で固定されています。"; wish = true }
        // 禁止の並びの相手が本人の希望のセル＝このセルを動かしても並びは希望側に残る。
        if ("vio-c3n" in ui.violationCellFamilies[VioKey.cell(i, d)].orEmpty()) {
            val near = listOf(d - 1, d + 1).filter { n -> ui.wishes["$i,$n"]?.let { it == cellAt(i, n) } == true && "vio-c3n" in ui.violationCellFamilies[VioKey.cell(i, n)].orEmpty() }
            if (near.isNotEmpty()) { out += "この並びには本人の希望（" + near.joinToString("・") { "${DayText.short(ui.startDate, it)} の「${sym(cellAt(i, it)!!)}」" } + "）が入っています。"; wish = true }
        }
    }
    if (i != null && k != null) {
        val days = ui.schedule.getOrNull(i)?.indices?.filter { cellAt(i, it) == k }.orEmpty()
        val pinned = days.filter { ui.wishes["$i,$it"] == k }
        if (days.isNotEmpty() && pinned.size == days.size) { out += "「${sym(k)}」の ${days.size} 回はどれも本人の希望で固定されています。"; wish = true }
        else if (pinned.isNotEmpty()) { out += "「${sym(k)}」の ${days.size} 回のうち ${pinned.size} 回は本人の希望で固定されています。"; wish = true }
        val (_, hi, _) = limits?.invoke(i, k) ?: Triple(null, null, null)
        if (hi == 0 && days.isNotEmpty()) out += "「${sym(k)}」は個人の上限が 0 回（入れない指定）です。"
        val tight = days.filter { j -> needLimits?.invoke(k, j)?.let { headcount(k, j) <= it.first } == true }
        if (tight.isNotEmpty()) out += tight.joinToString("・") { DayText.short(ui.startDate, it) } + " は「${sym(k)}」がその日の必要人数ぎりぎりで、抜けると人員不足になります。"
        val fixedOthers = (0 until ui.shifts.coerceAtLeast(ui.shiftSymbols.size)).filter { k2 ->
            if (k2 == k) return@filter false
            val (lo2, hi2, _) = limits?.invoke(i, k2) ?: return@filter false
            lo2 != null && lo2 == hi2 && (ui.schedule.getOrNull(i)?.count { it == k2 } ?: -1) == lo2
        }
        if (fixedOthers.isNotEmpty()) out += "ほかの勤務は下限＝上限で固定です（" +
            fixedOthers.joinToString("・") { k2 -> "${sym(k2)} ${limits!!.invoke(i, k2).first}回" } + "）。"
    }
    if (f.exceptStaff != null && d != null) {
        val w = ui.wishes["${f.exceptStaff},$d"]
        if (w != null && w == cellAt(f.exceptStaff, d)) { out += "本人の希望（${DayText.short(ui.startDate, d)} の「${sym(w)}」）は守ったままです。"; wish = true }
    }
    if (i == null && k != null && d != null) {
        val here = ui.schedule.indices.filter { cellAt(it, d) == k }
        val pinned = here.filter { ui.wishes["$it,$d"] == k }
        if (pinned.isNotEmpty()) {
            out += "${DayText.short(ui.startDate, d)} の「${sym(k)}」のうち " + pinned.joinToString("・") { ui.staffNames.getOrNull(it) ?: "#$it" } + " は本人の希望で固定されています。"
            wish = true
        }
    }
    out += NO_FIX_SCOPE
    val section = if (i == null && k != null) "yr_headcount" else "yr_count"
    return NoFixExplain(out, wish, section)
}

/** シフト集計「計（期間）」の人員の印：そのシフトに人員不足・過剰がある日。 */
internal data class ShiftCoverageTotal(val underDays: List<Int>, val overDays: List<Int>) {
    /** 例「▼2」「▲1」「▼1▲2」（数字は日数）。 */
    val glyph: String get() = (if (underDays.isNotEmpty()) "▼${underDays.size}" else "") + (if (overDays.isNotEmpty()) "▲${overDays.size}" else "")
    val days: List<Int> get() = (underDays + overDays).distinct().sorted()
}

/** シフト → 期間中の人員の印（必要数の無いシフトは被覆キーが無いので載らない）。 */
internal fun shiftCoverageTotals(marks: List<List<CoverageMark>>): Map<Int, ShiftCoverageTotal> {
    val under = HashMap<Int, MutableList<Int>>(); val over = HashMap<Int, MutableList<Int>>()
    marks.forEachIndexed { d, ms -> for (m in ms) (if (m.under) under else over).getOrPut(m.shift) { ArrayList() }.add(d) }
    return (under.keys + over.keys).associateWith { ShiftCoverageTotal(under[it].orEmpty(), over[it].orEmpty()) }
}

// ===== つくる前の確認（`PreRunCheck` の結果を行にする） =====

/** シートの 1 行。staff/day があれば押すとそのセルへ移る（希望の行は希望のシートで開く）。 */
internal data class PreRunRow(val text: String, val staff: Int? = null, val day: Int? = null, val wish: Boolean = false)

internal data class PreRunSheetText(
    val floorHeader: String?,
    val floorRows: List<PreRunRow>,
    val rerunHeader: String?,
    val rerunRows: List<PreRunRow>,
    val wallLine: String?,
    val hasWishRows: Boolean,
    val overCapNote: String? = null,
    val overCapRows: List<PreRunRow> = emptyList(),
    val zeroCapNote: String? = null,
)

internal const val PRE_RUN_FLOOR_NOTE = "本人の希望は固定・必要人数は設定どおりなので、何度つくっても必須違反として残ります。"
internal const val PRE_RUN_OVERCAP_HEAD = "設定上入れないシフトと希望（要調整）"
internal const val PRE_RUN_OVERCAP_ZERO = "上限0のシフトに希望が載っています。上限0は意図した制限です。残るのは要調整です。希望を変えるか、例外として後から「設定を緩めたら」で試せます。"
internal const val PRE_RUN_OVERCAP_OTHER = "個人の上限より多い希望が載っています。残るのは要調整です。希望を変えるか、例外として上限を緩めてください。"
internal const val PRE_RUN_ZERO_CAP_TAG = "（入れないシフトの指定が関係）"
internal const val PRE_RUN_ZERO_CAP_NOTE = "「入れないシフトの指定が関係」の行は、個人の上限0（入れない指定）が原因で残ります。希望のせいではありません。例外として緩めると解ける場合があります。つくったあとに「設定を緩めたら」で試せます。"
internal const val PRE_RUN_RERUN_NOTE = "今の勤務表に個人の上限（0回）のシフトが入っています。つくると外されます。"

internal fun preRunSheetText(s: com.magi.app.v6.PreRunCheck.Summary, ui: UiState): PreRunSheetText {
    fun name(i: Int) = ui.staffNames.getOrNull(i) ?: "職員${i + 1}"
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    fun day(j: Int) = DayText.short(ui.startDate, j)
    val floor = buildList {
        for (g in s.wishConflicts) {
            val pat = g.shifts.joinToString("→") { sym(it) }
            val text = if (g.family == "c3w")
                "${name(g.staff)} ${day(g.days[0])}「${sym(g.shifts[0])}」→ ${day(g.days[1])}「${sym(g.shifts[1])}」 本人の希望どうしが希望の前日の禁止に当たっています"
            else "${name(g.staff)} ${g.days.joinToString("・") { day(it) }} 本人の希望「$pat」が禁止の並びに当たっています"
            add(PreRunRow(text, g.staff, g.days.last(), wish = true))
        }
        for (w in s.impossibleWishes) {
            val cell = w.staffIndex >= 0 && w.dayIndex >= 0
            add(PreRunRow("${w.staffName} ${if (w.dayIndex >= 0) day(w.dayIndex) else "?"} 本人の希望「${w.shiftSymbol}」は反映できません（${w.reason}）",
                w.staffIndex.takeIf { cell }, w.dayIndex.takeIf { cell }, wish = cell))
        }
        fun pin(core: List<com.magi.app.v6.ConstraintMus.Item>) = core.firstNotNullOfOrNull { it as? com.magi.app.v6.ConstraintMus.WishPin }
        for (d in s.dayProofs) pin(d.core).let { w ->
            val z = if (d.day in s.zeroCapProofDays) PRE_RUN_ZERO_CAP_TAG else ""
            add(PreRunRow("${DayText.full(ui.startDate, d.day)} 必要人数と本人の希望の衝突（${d.core.size}件は同時に成立しません）$z", w?.staff, w?.day, w != null))
        }
        for (c in s.staffProofs) pin(c.core).let { w ->
            val z = if (s.zeroCapStaffProof(c)) PRE_RUN_ZERO_CAP_TAG else ""
            add(PreRunRow("${name(c.staff)} 本人の希望と条件の組合せ（${c.core.size}件は同時に成立しません）$z", w?.staff, w?.day, w != null))
        }
        for (f in s.forcedShortfalls) add(PreRunRow("「${f.shiftSymbol}」 ${f.cells}日で担当できる人より必要人数が多く、人員不足が合計${f.amount}人残ります" +
            (if (f.shiftIndex in s.zeroCapShortShifts) PRE_RUN_ZERO_CAP_TAG else "")))
    }
    val rerun = s.rerunClears.map { PreRunRow("${name(it.staff)} ${day(it.day)} ${sym(it.shift)}", it.staff, it.day) }
    val wall = s.wallHint?.let { "個人の上限0：${it.pairs}組（${it.staffCount}人）。入れないシフトの指定です。つくったあとに、例外として緩める試算もできます。" }
    return PreRunSheetText(
        floorHeader = if (floor.isEmpty()) null else "何度つくっても残る（${floor.size}件）",
        floorRows = floor,
        rerunHeader = if (rerun.isEmpty()) null else "もう一度つくると外れる（${rerun.size}件）",
        rerunRows = rerun,
        wallLine = wall,
        hasWishRows = floor.any { it.wish },
        overCapNote = s.wishOverCaps.firstOrNull()?.let { f ->
            val who = "${name(f.staff)}「${sym(f.shift)}」${if (s.wishOverCaps.size > 1) "など" else ""}"
            if (s.wishOverCaps.all { it.hi == 0 }) "$who：$PRE_RUN_OVERCAP_ZERO" else "$who：$PRE_RUN_OVERCAP_OTHER"
        },
        zeroCapNote = PRE_RUN_ZERO_CAP_NOTE.takeIf { floor.any { it.text.endsWith(PRE_RUN_ZERO_CAP_TAG) } },
        overCapRows = s.wishOverCaps.map { PreRunRow("${name(it.staff)}「${sym(it.shift)}」 本人の希望${it.wished}件（個人の上限${it.hi}回）") },
    )
}
