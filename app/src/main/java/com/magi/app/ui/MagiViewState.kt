package com.magi.app.ui

import com.magi.app.model.MagiState
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

    val c1Anchors: Map<String, Int> = c1DisplayAnchors(ui)

    /** セルに出す違反クラスのうちフィルタを通るもの（重み降順・c1 の表示アンカー込み）。 */
    private val cellVisible: Array<Array<List<String>>> = Array(staffCount) { i ->
        Array(dayCount) { j -> displayCellClasses(ui, VioKey.cell(i, j), c1Anchors).filter { vioVisible(it, vioEnabled) } }
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

/** c1 の表示アンカー（セルキー → そのランの違反窓数）。ランの先頭に加えて窓幅おきに置き、ランが覆う日の中に
 *  収める＝どの違反窓にも少なくとも 1 つ入る（チェッカーはランの先頭 1 セルだけ）。 */
internal fun c1DisplayAnchors(ui: UiState): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (r in ui.c1Runs) {
        val i = r.getOrNull(0) ?: continue; val j0 = r.getOrNull(1) ?: continue
        val n = r.getOrNull(2) ?: continue; val w = r.getOrNull(3) ?: continue
        if (n <= 0 || w <= 0) continue
        val last = j0 + n - 1 + w - 1
        var a = j0
        while (a <= last) { val key = VioKey.cell(i, a); out[key] = maxOf(out[key] ?: 0, n); a += w }
    }
    return out
}

/** 画面に出すセルの違反クラス（重み降順）。チェッカーのクラスに c1 の表示アンカーを足したもの。 */
internal fun displayCellClasses(ui: UiState, key: String, c1Anchors: Map<String, Int>): List<String> {
    val base = cellVioClasses(ui, key)
    if (key !in c1Anchors || "vio-c1" in base) return base
    return (base + "vio-c1").sortedByDescending { MirrorKeys.weightOf(familyOfVioClass(it)) }
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

private fun countDetail(cls: String, count: Int, lo: Int?, hi: Int?, apt: Int?): String = when (cls) {
    "vio-low" -> lo?.let { "現在 ${count}回・下限 ${it}回" }
    "vio-high" -> hi?.let { "現在 ${count}回・上限 ${it}回" }
    "vio-aptLow", "vio-aptHigh" -> apt?.let { "現在 ${count}回・目標 ${it}回" }
    else -> "現在 ${count}回"
}?.let { "（$it）" } ?: ""

/**
 * 職員 i の回数・偏りの一覧（行末の印のシートとセルシートの「この職員の回数・偏り」で共有）。
 * 回数の族は報告の回数キー、公平化・曜日は `distLocations`（セルに印を出さない族）から。
 * limits は (職員,シフト) → (下限, 上限, 目標)。null なら数値の注記を省く。
 */
internal fun staffCountLines(ui: UiState, i: Int, limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)? = null): List<String> {
    val out = ArrayList<String>()
    fun sym(k: Int) = ui.shiftSymbols.getOrNull(k) ?: "$k"
    val keys = (ui.countFamilies.keys + ui.countViolations.keys).filter { VioKey.first(it) == i }
        .sortedBy { VioKey.second(it) ?: 0 }
    for (key in keys) {
        val k = VioKey.second(key) ?: continue
        val count = ui.schedule.getOrNull(i)?.count { it == k } ?: 0
        val (lo, hi, apt) = limits?.invoke(i, k) ?: Triple(null, null, null)
        for (cls in ui.countFamilies[key] ?: listOfNotNull(ui.countViolations[key])) {
            val fam = cls.removePrefix("vio-")
            val label = breakdownLabels[fam] ?: breakdownLabels[familyOfVioClass(cls)] ?: fam
            out += (if (isUnderCountClass(cls)) "▼ " else "▲ ") + "${sym(k)}: $label" + countDetail(cls, count, lo, hi, apt)
        }
    }
    for (e in ui.distLocations["fair"].orEmpty()) if (e.getOrNull(0) == i && e.size >= 3) {
        out += "・${sym(e[1])}: ${breakdownLabels["fair"]}（グループ内の差 ${e[2]}回）"
    }
    for (e in ui.distLocations["weekly"].orEmpty()) if (e.getOrNull(0) == i && e.size >= 3) {
        out += "・${sym(e[1])}: ${breakdownLabels["weekly"]}（偏り ${e[2]}）"
    }
    return out
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
    val dashed = listOf("c1", "c3mn").map { breakdownLabels[it] ?: it }
    return "実線: ${solid.joinToString("・")}／破線: ${dashed.joinToString("・")}"
}
