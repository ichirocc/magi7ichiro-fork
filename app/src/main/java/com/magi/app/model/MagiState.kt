package com.magi.app.model

/**
 * MAGI shift-scheduling problem state.
 *
 * Field names and semantics mirror the Web app's `state` object exactly, so JSON
 * exported from the Web version (and vice-versa) round-trips without conversion.
 *
 *  - shifts[k]      : a shift type. need1/need2 = default per-day required count
 *                     for pattern P1/P2 ("" / null = no requirement).
 *  - groups[g]      : a staff group (skill class). kigou = symbol used in constraints.
 *  - staff[i]       : a person; groupIdx points into groups.
 *  - groupShift[g]  : per-group 0/1 mask of which shifts the group may take.
 *  - groupShiftApt[g][k] : V6 “適切回数” target for group×shift (blank = unset).
 *  - use2Patterns   : whether the P2 coverage generation is active (MIN=OR with P1).
 *  - schedule[i][j] : initial assignment = shift index for staff i on day j.
 *  - wishes["i,j"]  : desired shift index for a cell (hard-ish preference).
 *  - manualPins     : cells the user pinned by hand (the optimizer never rewrites them).
 *  - staffRange["i,k"] = {lo,hi} : per-staff per-shift count range (LimMin/LimMax).
 *  - needDay1/needDay2["k,j"]    : per-day need override for shift k on day j.
 *  - cons1..cons42  : the constraint families (see resolveConstraints / Evaluator).
 */
/** [backlog#24] シフトの特別な役割。休みの識別を記号"休"の字面一致から切り離すために導入（3.603.0）。 */
enum class ShiftRole { None, Rest }
data class Shift(val name: String, val kigou: String, val need1: String, val need2: String, val role: ShiftRole = ShiftRole.None)
data class Group(val name: String, val kigou: String)
/** staff[i]: groupIdx -> ユニットグループ(既存・担当可否/covU)、skillIdx -> スキルグループ(新設・新C41s/C42s専用)。
 *  skillIdx の既定は -1＝未所属（backlog#38。0 は先頭のスキルグループ）。 */
data class Staff(val name: String, val groupIdx: Int, val skillIdx: Int = -1)
data class Range(val lo: String, val hi: String)

/** Raw constraint rows (as authored), resolved later into index form. */
data class C1Row(val day1: String, val shiftKigou: String, val day2: String)
data class C2Row(val shiftKigou: String, val count: String)
data class C3Row(val pattern: List<String>)
data class C41Row(val groupKigou: String, val shiftKigou: String, val l: String, val u: String)
data class C42Row(val g1Kigou: String, val g2Kigou: String, val s1Kigou: String, val s2Kigou: String)
/** [#41] 手動固定 1 件＝職員 [staff] の [day] 日目を [shift] に固定（最適化器は書き換えない。手の編集は可で、値はそれに追従する）。 */
data class ManualPin(val staff: Int, val day: Int, val shift: Int)
/** 希望(ws3)で固定した [wishKigou] の前日に [prevKigou] を置けない（3.542.0）。素の連続禁止は cons3n。 */
data class C3wRow(val wishKigou: String, val prevKigou: String)

data class MagiState(
    val startDate: String,
    val endDate: String,
    val shifts: List<Shift>,
    val groups: List<Group>,
    val staff: List<Staff>,
    val use2Patterns: Boolean,
    val groupShift: List<List<Int>>,
    val groupShiftApt: List<List<String>>,
    val schedule: List<List<Int>>,
    val wishes: Map<String, Int>,
    val staffRange: Map<String, Range>,
    val needDay1: Map<String, String>,
    val needDay2: Map<String, String>,
    val cons1: List<C1Row>,
    val cons2: List<C2Row>,
    val cons3: List<C3Row>,
    val cons3n: List<C3Row>,
    val cons3m: List<C3Row>,
    val cons3mn: List<C3Row>,
    val cons41: List<C41Row>,
    val cons42: List<C42Row>,
    /** [スキルグループ新設] ユニットとは別の第2分類。担当可否には使わず、下の新C41s/C42sだけが参照。 */
    val skillGroups: List<Group> = emptyList(),
    /** スキルグループの C41 相当: スキル群 X のシフト Y を1日に [l,u] 回（既存C41のスキル版）。 */
    val cons41s: List<C41Row> = emptyList(),
    /** スキルグループの C42 相当: スキル群 g1 の s1 と スキル群 g2 の s2 が同日に併存不可（既存C42のスキル版）。 */
    val cons42s: List<C42Row> = emptyList(),
    /** 希望の前日に禁止（HARD、c3n と同格）。希望で固定された wishKigou の前日セルが prevKigou なら違反。 */
    val cons3w: List<C3wRow> = emptyList(),
    /** [#41] 手動固定（1 セル 1 件）。採点・希望の意味は変えない。 */
    val manualPins: List<ManualPin> = emptyList(),
    /** Per-shift display colour overrides, keyed by shift kigou -> "#rrggbb". Display only (no engine effect). */
    val shiftColors: Map<String, String> = emptyMap(),
    /** Anything we do not model yet, kept verbatim so export round-trips losslessly. */
    val extras: Map<String, Any?> = emptyMap(),
) {
    val staffCount: Int get() = staff.size
    val dayCount: Int get() = if (schedule.isNotEmpty()) schedule[0].size else 0
    val shiftCount: Int get() = shifts.size
    val groupCount: Int get() = groups.size
    val skillGroupCount: Int get() = skillGroups.size
}

/** [#41] セル (i,j) の手動固定（無ければ null）。 */
fun MagiState.pinAt(i: Int, j: Int): ManualPin? = manualPins.firstOrNull { it.staff == i && it.day == j }

/** [#41] 手の編集で [cells] が [shift] になったとき、固定されたセルの値を追従させる（固定は残す）。固定に当たらなければ同じ state。 */
fun MagiState.withPinsFollowing(cells: Collection<Pair<Int, Int>>, shift: Int): MagiState {
    if (manualPins.isEmpty()) return this
    val set = cells.toHashSet()
    if (manualPins.none { (it.staff to it.day) in set && it.shift != shift }) return this
    return copy(manualPins = manualPins.map { if ((it.staff to it.day) in set) it.copy(shift = shift) else it })
}

/** [#41] セル (i,j) を [shift] で固定する／固定を外す（トグル）。 */
fun MagiState.togglePin(i: Int, j: Int, shift: Int): MagiState =
    if (pinAt(i, j) != null) copy(manualPins = manualPins.filterNot { it.staff == i && it.day == j })
    else copy(manualPins = manualPins + ManualPin(i, j, shift))
