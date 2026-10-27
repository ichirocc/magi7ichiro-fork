package com.magi.app.v6

import com.magi.app.model.C2Row
import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.ShiftRole
import com.magi.app.model.Staff
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOFT の桁あふれ（2026-10-24 の外部レビュー）: 個人の下限 1 億で SOFT が 10 億を超えると、差分評価が必須の数を取り違えていた
 * （hard=43 を返した）。実行前の検証が断り、差分評価もフル評価と同じく黙って誤った値を返さない。
 */
class SoftOverflowGuardTest {
    private fun state(lo: String, c2: String = "") = MagiState(
        startDate = "2026-01-01", endDate = "2026-01-31",
        shifts = listOf(Shift("休", "休", "", "", ShiftRole.Rest), Shift("A", "A", "1", "1")),
        groups = listOf(Group("G", "G")), staff = listOf(Staff("s0", 0), Staff("s1", 0)), use2Patterns = true,
        groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = List(2) { List(31) { 0 } }, wishes = emptyMap(), staffRange = mapOf("0,1" to Range(lo, "")),
        needDay1 = emptyMap(), needDay2 = emptyMap(), cons1 = emptyList(),
        cons2 = if (c2.isEmpty()) emptyList() else listOf(C2Row("A", c2)), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun runValidationRefusesCountSettingsThatOverflowTheSoftDigit() {
        assertNull(Ws1Ops.countSettingError(state("31")))
        assertNull(Ws1Ops.countSettingError(state("${Ws1Ops.COUNT_SETTING_MAX}")))
        assertNotNull(Ws1Ops.countSettingError(state("100000000")))
        assertNotNull(Ws1Ops.countSettingError(state("", c2 = "100000000")))
    }

    @Test fun deltaEvaluatorDoesNotSilentlyMisreportHard() {
        val st = state("100000000")
        val d = DeltaEvaluator(Problem(st))
        val r = runCatching { d.reset(st.schedule.toIntArray2D()); d.score() }
        assertTrue("差分評価が桁あふれの値を返した: ${r.getOrNull()}", r.isFailure)
    }

    @Test fun theLimitItselfStaysWithinTheDigitForTheLargestShop() {
        // 30 名×12 シフトが全部上限の下限で 0 回＝最大の下限割れでも桁に収まる（上限値の根拠）。
        val worst = 30L * 12 * Ws1Ops.COUNT_SETTING_MAX * MirrorKeys.weightOf("low").toLong()
        assertTrue("上限 ${Ws1Ops.COUNT_SETTING_MAX} の最悪の SOFT $worst が桁を超える", worst < SCORE_HARD_UNIT)
    }
}
