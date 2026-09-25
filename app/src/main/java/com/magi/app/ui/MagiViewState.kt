package com.magi.app.ui

import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.WishTrial

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
    v != null && familyOfVioClass(v) in heavySoftFamilies

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

    /** cellVio[i][j] = そのセルで表示する最重の違反クラス（フィルタ通過後。無ければ null）。 */
    val cellVio: Array<Array<String?>> = Array(staffCount) { i ->
        Array(dayCount) { j -> visibleCellVio(ui, VioKey.cell(i, j), vioEnabled) }
    }

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
 * 希望前日の禁止(c3w)がある、または禁止の並び(c3n)が希望で固定したセルに掛かっている。
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
            if ("vio-c3w" in fams) add(InvolvedWish(i, j + 1, name, "前日（${j + 1}日）に置けない勤務が入っています"))
            if ("vio-c3n" in fams && ui.wishes.containsKey(key)) add(InvolvedWish(i, j, name, "希望が禁止の並びに掛かっています"))
        }
    }.distinct().sortedWith(compareBy({ it.staff }, { it.day }))

/** [S5] 試算の候補 1 行。`locked=false`（担当できない勤務の希望）は試算ボタンを出さず [WISH_TRIAL_NOT_LOCKED] を出す。 */
internal data class WishTrialRow(val staff: Int, val day: Int, val name: String, val reason: String, val locked: Boolean)

/** [S5b] 人手不足の枠 1 つ（見出し「12日 日勤 1人不足」）と、その日に別の勤務で希望固定されている人の行（職員順）。 */
internal data class ShortfallWishGroup(val day: Int, val shift: Int, val header: String, val rows: List<WishTrialRow>)

internal data class WishTrialCandidates(val direct: List<WishTrialRow>, val shortfall: List<ShortfallWishGroup>) {
    val isEmpty: Boolean get() = direct.isEmpty() && shortfall.all { it.rows.isEmpty() }
}

internal const val WISH_TRIAL_NOT_LOCKED = "担当できない勤務の希望なので、取り消しても勤務表は変わりません。"
/** [S5b] 1 枠に並べる行の上限（超えたら「ほか N人」）。 */
internal const val WISH_TRIAL_GROUP_LIMIT = 8

/**
 * [S5] 試算の候補（`docs/s5_wish_trial.md` §2.2・§2.3）。S5a＝必須違反に関わる希望を (職員, 日) で重複除去し、
 * 代表の理由を pref＞c3w＞c3n で選ぶ（他は「ほか: …」）。c3w は翌日の希望 X と、印の付く前日自身が wishLocked の希望 Y の両方。
 * 満たされない希望が希望どうしの衝突（`UiState.wishSelfConflicts`）の組に入っていれば、組のほかの希望も S5a の行にする（§2.2）。
 * S5b＝人手不足の枠の `wishPinned`（日→シフト、職員順）。S5a と重なる (職員, 日) は S5a を代表にし「ほか: 人手不足の日」を足す。
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
                add(Hit(i, j + 1, 1, "前日（${j + 1}日）に置けない勤務が入っています"))
                if (key in ui.lockedWishKeys) add(Hit(i, j, 1, "翌日（${j + 2}日）の希望の勤務の前日に置けない勤務の希望です"))
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
            (if (sd in pinnedKeys) listOf("人手不足の日") else emptyList())
        val reason = if (others.isEmpty()) rep.reason else "${rep.reason}（ほか: ${others.joinToString("・")}）"
        WishTrialRow(sd.first, sd.second, name(sd.first), reason, "${sd.first},${sd.second}" in ui.lockedWishKeys)
    }.sortedWith(compareBy({ it.staff }, { it.day }))
    val directKeys = direct.map { it.staff to it.day }.toSet()
    val shortfall = pinned.map { s ->
        val rows = s.wishPinned.sorted().filter { (it to s.dayIndex) !in directKeys }.map { i ->
            val sym = ui.wishes["$i,${s.dayIndex}"]?.let { ui.shiftSymbols.getOrNull(it) } ?: "別の勤務"
            WishTrialRow(i, s.dayIndex, name(i), "${sym}の希望", "$i,${s.dayIndex}" in ui.lockedWishKeys)
        }
        ShortfallWishGroup(s.dayIndex, s.shiftIndex, "${s.dayLabel} ${s.shiftSymbol} ${s.miss}人不足", rows)
    }.filter { it.rows.isNotEmpty() }
    return WishTrialCandidates(direct, shortfall)
}

/** [S5] 試算結果 1 行の文（§5 の表）。止めた試算は null（数字を出さない）。 */
internal fun wishTrialText(o: WishTrial.Outcome): String? = when (o) {
    is WishTrial.Result -> when {
        o.rk >= o.h0 && o.att <= 0 -> "この試算では、減る見込みは見つかりませんでした（もう一度つくると減ることはあります）。"
        o.rk >= o.h0 && o.aPrime > 0 && o.b > 0 -> "取り消すと必須違反が確実に${o.aPrime}件 減り、もう一度つくるとさらに${o.b}件 減る見込みです。"
        o.rk >= o.h0 && o.aPrime > 0 -> "取り消すと必須違反が確実に${o.aPrime}件 減ります。"
        o.rk >= o.h0 -> "取り消してもう一度つくると、必須違反が${o.b}件 減る見込みです。"
        o.att > 0 -> "もう一度つくるだけの場合より、さらに${o.att}件 減る見込みです。"
        else -> "取り消さなくても、もう一度つくるだけで同じだけ減る見込みです。"
    }
    is WishTrial.Unavailable -> "試算できませんでした（${o.reason}）。"
    else -> null
}

/** [S5] Rk < H0 の盤面でダイアログの先頭に出す文（§5）。対照だけで減らないなら null。 */
internal fun wishTrialKeepOnlyText(control: WishTrial.Control): String? =
    if (control.rk < control.h0) "希望を残したまま、もう一度つくるだけで必須違反が${control.h0 - control.rk}件 減る見込みです。" else null
