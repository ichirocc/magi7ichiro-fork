package com.magi.app.ui

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.restShiftIndex

/** ws1（初期設定）の編集画面が描くのに要るもの。`MagiState` から 1 度だけ組み立てる。 */
internal data class Ws1View(
    val startDate: String, val endDate: String, val days: Int, val use2: Boolean,
    val shifts: List<Shift>, val groups: List<Group>, val staff: List<Staff>,
    val groupShift: List<List<Int>>,
    val groupShiftApt: List<List<String>>,
    val skillGroups: List<Group> = emptyList(),
    /** 削除確認で見せる影響件数。対象を参照する制約行数（0 件なら影響なし）。 */
    val shiftRefs: List<Int> = emptyList(),
    val groupRefs: List<Int> = emptyList(),
    val groupMembers: List<Int> = emptyList(),
    val skillGroupRefs: List<Int> = emptyList(),
    /** [3.578.0/P10] 「休」の記号解決は`restShiftIndex`の唯一の持ち場に委譲（生文字列比較を作らない）。 */
    val restIdx: Int = -1,
) {
    /** グループは 2 つ以上あれば削除できる（所属者がいても先頭グループへ移して削除）。 */
    fun canRemoveGroup(g: Int): Boolean = g in groups.indices && groups.size > 1

    fun shiftRefCount(k: Int): Int = shiftRefs.getOrElse(k) { 0 }
    fun groupRefCount(g: Int): Int = groupRefs.getOrElse(g) { 0 }
    fun groupMemberCount(g: Int): Int = groupMembers.getOrElse(g) { 0 }
    fun skillGroupRefCount(g: Int): Int = skillGroupRefs.getOrElse(g) { 0 }
}

/** [days] は盤面の実日数（無ければ `MagiState.dayCount`）。 */
internal fun ws1ViewOf(st: MagiState?, days: Int): Ws1View? {
    if (st == null) return null
    return Ws1View(
        startDate = st.startDate, endDate = st.endDate, days = days, use2 = st.use2Patterns,
        shifts = st.shifts, groups = st.groups, staff = st.staff,
        groupShift = st.groupShift, groupShiftApt = st.groupShiftApt,
        skillGroups = st.skillGroups,
        shiftRefs = st.shifts.map { Ws1Ops.shiftRefCount(st, it.kigou) },
        groupRefs = st.groups.map { Ws1Ops.groupRefCount(st, it.kigou) },
        groupMembers = st.groups.indices.map { g -> st.staff.count { it.groupIdx == g } },
        skillGroupRefs = st.skillGroups.map { Ws1Ops.skillGroupRefCount(st, it.kigou) },
        restIdx = restShiftIndex(st),
    )
}
