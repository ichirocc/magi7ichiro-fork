package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.Problem
import com.magi.app.v6.canDoShiftsForStaff
import com.magi.app.v6.V6SanityPort

/** ws2: 日別の必要人数（例外）1 行。 */
data class NeedDayView(val k: Int, val j: Int, val kigou: String, val p1: String, val p2: String)

/** グループ単位の回数の適用済み 1 行。全メンバーが同一の非空レンジを持つ (g,k) だけが並ぶ。 */
data class GroupRangeView(val g: Int, val k: Int, val groupName: String, val kigou: String, val lo: String, val hi: String, val members: Int, val shared: Int = members)

/** 回数の規則 1 行。aptEff=実効目標(-1=なし) / aptRaw=群目標の生値（異なればクランプされている）。 */
data class CountRuleView(
    val i: Int, val k: Int, val staffName: String, val kigou: String,
    val lo: String, val hi: String, val aptEff: Int, val aptRaw: Int, val hasRange: Boolean,
)

/** ws3: 希望 1 行。 */
data class WishView(val i: Int, val j: Int, val staffName: String, val day: Int, val kigou: String, val k: Int)

/** 入力ガイド（月次/年次の入力手順）用の各項目の件数。 */
internal data class SetupCounts(
    val days: Int, val staff: Int, val shifts: Int, val groups: Int,
    val wishes: Int, val needDay: Int, val constraints: Int, val ranges: Int, val use2: Boolean,
)

/**
 * 月次条件（必要人数・希望・回数の上下限・グループ単位の回数）の編集画面が描くのに要るもの。
 * 表は一度に作って配る＝画面ごとに `Problem` を引き直さない。
 */
internal data class ConditionsView(
    val shiftKigou: List<String> = emptyList(),
    /** 休のシフト index（記号の字面でなく `restShiftIndex` の唯一の持ち場から）。休が無い設定は -1（どのシフトにも一致しない番兵、[3.603.0]）。 */
    val restIdx: Int = -1,
    val groupLabels: List<String> = emptyList(),
    val groupMembers: List<Int> = emptyList(),
    /** 担当できるシフト（職員ごと）。上限 0 は最適化器だけが除外するのでここには出る。 */
    val allowedByStaff: List<Set<Int>> = emptyList(),
    /** グループの全メンバーが担当できるシフトの積集合（下限を全員が満たせる範囲に限る）。 */
    val allowedByGroup: List<Set<Int>> = emptyList(),
    val needDayOverrides: List<NeedDayView> = emptyList(),
    val wishOverrides: List<WishView> = emptyList(),
    val countRules: List<CountRuleView> = emptyList(),
    val groupRanges: List<GroupRangeView> = emptyList(),
    val aptBalances: List<V6SanityPort.AptBalance> = emptyList(),
    val setupCounts: SetupCounts = SetupCounts(0, 0, 0, 0, 0, 0, 0, 0, false),
    private val staffLimits: List<List<Triple<Int?, Int?, Int?>>> = emptyList(),
    private val needLimits: List<List<Pair<Int, Int>?>> = emptyList(),
    private val groupRangeMembers: List<List<Int>> = emptyList(),
) {
    /** 集計セル(職員別)のしきい値: 下限/上限(staffRange)・目標(apt実効)。未設定は null。 */
    fun staffCellLimits(i: Int, k: Int): Triple<Int?, Int?, Int?> =
        staffLimits.getOrNull(i)?.getOrNull(k) ?: Triple(null, null, null)

    /** 集計セル(日別)の必要数レンジ lo..hi（need1/need2 の OR）。どちらも未定義なら null。 */
    fun needCellLimits(k: Int, j: Int): Pair<Int, Int>? = needLimits.getOrNull(k)?.getOrNull(j)

    /** グループ g のメンバーのうち (i,k) に個人上下限（非空）を持つ人数。 */
    fun groupRangeMemberCount(g: Int, k: Int): Int = groupRangeMembers.getOrNull(g)?.getOrNull(k) ?: 0

    fun allowedShiftsFor(i: Int): Set<Int> = allowedByStaff.getOrNull(i) ?: emptySet()
}

/**
 * [p] は呼び出し側が持っている `Problem`（`MagiViewModel.cachedProblem` が state ごとに使い回す）。
 * ここで作り直すと再描画のたびに高コストな再構築が走る。
 */
internal fun conditionsViewOf(st: MagiState?, p: Problem?): ConditionsView {
    if (st == null || p == null) return ConditionsView()
    val allowedByStaff: List<Set<Int>> = (0 until p.S).map { p.canDoShiftsForStaff(it).toSet() }
    val groupMembers = st.groups.indices.map { g -> st.staff.count { it.groupIdx == g } }
    // しきい値は covUCell/covOCell の選択と厳密に一致させる: 両方定義なら lo=min・hi=max
    //   （小さい方で不足が立ち、大きい方を超えて初めて過剰が立つ）、片方だけなら双方その値。
    val needLimits = (0 until p.K).map { k ->
        (0 until p.T).map { j ->
            val n1 = p.need1[k][j]
            val n2 = if (p.use2) p.need2[k][j] else -1
            if (n1 < 0 && n2 < 0) null
            else Pair(if (n1 >= 0 && n2 >= 0) minOf(n1, n2) else maxOf(n1, n2), maxOf(n1, n2))
        }
    }
    // 明示した 0 は 0 として返す（未設定＝MIN_VALUE/MAX_VALUE のみ null）。
    val staffLimits = (0 until p.S).map { i ->
        (0 until p.K).map { k ->
            Triple(
                p.rangeLo[i][k].let { if (it == Int.MIN_VALUE) null else it },
                p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) null else it },
                p.apt[i][k].let { if (it < 0) null else it },
            )
        }
    }
    val groupRangeMembers = st.groups.indices.map { g ->
        (0 until p.K).map { k ->
            st.staff.indices.count {
                st.staff[it].groupIdx == g &&
                    st.staffRange["$it,$k"]?.let { r -> r.lo.isNotBlank() || r.hi.isNotBlank() } == true
            }
        }
    }
    val cons = st.cons1.size + st.cons2.size + st.cons3.size + st.cons3n.size +
        st.cons3m.size + st.cons3mn.size + st.cons41.size + st.cons42.size + st.cons3w.size
    return ConditionsView(
        shiftKigou = st.shifts.map { it.kigou },
        restIdx = com.magi.app.v6.restShiftIndex(st) ?: -1,  // [3.603.0] 休が無い設定はどのシフトにも一致しない番兵
        groupLabels = st.groups.map { if (it.kigou.isNotBlank() && it.kigou != it.name) "${it.name}·${it.kigou}" else it.name },
        groupMembers = groupMembers,
        allowedByStaff = allowedByStaff,
        allowedByGroup = st.groups.indices.map { g ->
            val members = st.staff.indices.filter { st.staff[it].groupIdx == g }
            if (members.isEmpty()) emptySet()
            else members.map { allowedByStaff.getOrElse(it) { emptySet<Int>() } }.reduce { a, b -> a intersect b }
        },
        aptBalances = runCatching { V6SanityPort.aptBalances(st) }.getOrDefault(emptyList()),
        setupCounts = SetupCounts(
            st.dayCount, st.staffCount, st.shiftCount, st.groupCount,
            st.wishes.size, st.needDay1.size + st.needDay2.size, cons, st.staffRange.size, st.use2Patterns,
        ),
        staffLimits = staffLimits,
        needLimits = needLimits,
        groupRangeMembers = groupRangeMembers,
    )
}
