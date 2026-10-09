package com.magi.app.ui

import com.magi.app.v6.ConstraintMus
import com.magi.app.v6.CoverageShortfall
import com.magi.app.v6.CoverageVerdict

/**
 * [UX監査 中4] 診断の原因に対応する設定の着地先（値で持つ）。null＝原因が分からない＝編集タブの先頭（従来どおり）。
 * 3.644.0: 「つくる前の確認」のセルを持たない行からも使う＝必要人数のシフト（月次条件）と回数のマス（③ 職員×シフト）と
 * ボタンの文言の上書きを足した（UX-02: 推奨に出した対象を遷移先へ引き継ぐ）。
 */
internal data class EditLanding(
    val scope: Int, val section: String?, val wishStaff: Int? = null,
    val needShift: Int? = null, val countCell: Pair<Int, Int>? = null, val label: String? = null,
)

internal fun landingFor(s: CoverageShortfall): EditLanding? = when {
    s.wishPinned.isNotEmpty() -> EditLanding(scope = 0, section = null, wishStaff = s.wishPinned.first())   // 希望で固定された本人（月次条件）
    s.verdict == CoverageVerdict.INFEASIBLE -> EditLanding(scope = 2, section = "yr_ws1")                  // 担当・必要人数（①）
    s.blockedNow && s.forbidCount > 0 -> EditLanding(scope = 2, section = "yr_cons")                       // 移すと禁止の並び（⑤）
    else -> null
}

internal fun landingButtonLabel(l: EditLanding?): String = when {
    l?.label != null -> l.label
    l?.wishStaff != null -> "希望を見直す"
    l?.needShift != null -> "必要人数を見直す"
    l?.section == "yr_count" -> "回数の下限・上限を見直す"
    l?.section == "yr_cons" -> "禁止の並びを見直す"
    l?.section == "yr_ws1" -> "担当を見直す"
    else -> "データを見直す"
}

internal const val LANDING_ZERO_CAP = "入れない指定を見直す"
internal const val LANDING_WINDOW = "期間の制約を見直す"

/**
 * 証明つきの矛盾（コア）のうち希望を含まないものの着地先。上限0が原因とされた行は上限0のマスへ、
 * そうでなければ必要人数（月次条件）→ 回数のマス → 期間の制約の順。どれも無ければ null（行は文だけ）。
 */
internal fun landingForProofCore(core: List<ConstraintMus.Item>, zeroCap: Boolean): EditLanding? {
    val zero = core.firstNotNullOfOrNull { (it as? ConstraintMus.RangeCap)?.takeIf { c -> c.hi == 0 } }
    if (zeroCap && zero != null) return EditLanding(2, "yr_count", countCell = zero.staff to zero.shift, label = LANDING_ZERO_CAP)
    core.firstNotNullOfOrNull { it as? ConstraintMus.DayNeed }?.let { return EditLanding(0, null, needShift = it.shift) }
    core.firstNotNullOfOrNull { it as? ConstraintMus.RangeCap }?.let { return EditLanding(2, "yr_count", countCell = it.staff to it.shift) }
    core.firstNotNullOfOrNull { it as? ConstraintMus.RangeFloor }?.let { return EditLanding(2, "yr_count", countCell = it.staff to it.shift) }
    if (core.any { it is ConstraintMus.WindowRule }) return EditLanding(2, "yr_cons", label = LANDING_WINDOW)
    return null
}
