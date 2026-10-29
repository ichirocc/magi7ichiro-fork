package com.magi.app.ui

import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.ViolationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [3.705.0] 分析タブの状態の段（一文・3 点・主ボタン 1 つ）と、変更案で直せる必須違反の和集合。 */
class Review3705Test {
    private val made = UiState(loaded = true, hasResult = true, engineRan = true, checkRev = 1, bestHard = 2L, bestSoft = 5L,
        breakdown = mapOf("c3n" to 2, "c1" to 5))

    @Test fun sentenceFollowsBoardStatusDistinctions() {
        assertEquals("まだ作っていません。", analysisSummary(UiState(loaded = true), null).sentence)
        assertEquals("勤務表をつくっています。表示は途中の値です。", analysisSummary(made.copy(running = true), null).sentence)
        assertEquals("手で編集した勤務表です（自動作成はまだ）。", analysisSummary(made.copy(engineRan = false), null).sentence)
        assertEquals("必須2件 · 要調整5件", analysisSummary(made, null).sentence)
        assertEquals("必須0件 · 要調整5件", analysisSummary(made.copy(bestHard = 0L, breakdown = mapOf("c1" to 5)), null).sentence)
        assertEquals("必須違反も要調整もありません。", analysisSummary(made.copy(bestHard = 0L, bestSoft = 0L, breakdown = emptyMap()), null).sentence)
        // 自動作成のあと手で変えた盤面は、上部の札と同じく「自動作成済み」の側。
        assertEquals("必須2件 · 要調整5件", analysisSummary(made.copy(engineRan = false, ranBeforeEdit = true), null).sentence)
    }

    @Test fun analysisHasNoPrimaryOfItsOwnButTheFixPoint() {
        // [UI-S6] 分析は「次の問題へ」の主を持たない（ホームの主と重ねない）。直せる数は点として言う。
        assertEquals(listOf("変更案で直せる必須違反 2か所"), analysisSummary(made, 2).points)
        assertEquals(emptyList<String>(), analysisSummary(made, null).points)
    }

    @Test fun thirdPointOnlyWhenEvaluatedAndSearched() {
        assertEquals(0, analysisSummary(made, null).points.size)
        assertEquals("変更案で直せる必須違反 1か所", analysisSummary(made, 1).points[0])
        assertEquals(0, analysisSummary(made.copy(checkRev = 0), 1).points.size)
        assertEquals(0, analysisSummary(made.copy(running = true), 1).points.size)
        assertEquals(emptyList<String>(), analysisSummary(UiState(loaded = true), 1).points)
    }

    @Test fun resolvedUnionCountsEachPlaceOnceAndChecksFreshness() {
        val a = listOf(FixCell(0, 1, 2)); val b = listOf(FixCell(1, 1, 0))
        val k1 = ViolationKey("c3n", ViolationKey.Space.CELL, 0, 1); val k2 = ViolationKey("covU", ViolationKey.Space.NEED, 2, 1)
        val union = fixResolvedUnionOf(listOf(a, b)) { if (it == a) setOf(k1, k2) else setOf(k2) }
        assertEquals(setOf(k1, k2), union)

        val sch = listOf(listOf(1, 2), listOf(0, 1))
        val sugg = listOf(FixSuggestion(FixKind.CHANGE, a, "", -1, -1, emptyList()), FixSuggestion(FixKind.CHANGE, b, "", -1, -1, emptyList()),
            FixSuggestion(FixKind.CHANGE, listOf(FixCell(0, 0, 0)), "", 0, -1, emptyList()))
        val ui = made.copy(schedule = sch, fixSuggestions = sugg, fixSearched = true, fixResolvedUnion = FixResolvedUnion(listOf(a, b), sch, union))
        assertEquals(2, analysisFixResolved(ui))
        assertNull("未探索なら出さない", analysisFixResolved(ui.copy(fixSearched = false)))
        assertNull("盤面が変わったら出さない", analysisFixResolved(ui.copy(schedule = listOf(listOf(0, 2), listOf(0, 1)))))
        assertNull("案が変わったら出さない", analysisFixResolved(ui.copy(fixSuggestions = sugg.take(1))))
    }

    @Test fun issueSettingsLabelNamesTheSection() {
        assertEquals("希望の設定を開く", issueSettingsLabel(com.magi.app.v6.IssueKind.WISH))
        assertEquals("必要人数の設定を開く", issueSettingsLabel(com.magi.app.v6.IssueKind.DEMAND))
        assertEquals("回数の設定を開く", issueSettingsLabel(com.magi.app.v6.IssueKind.RANGE))
        assertEquals("並び・期間の制約の設定を開く", issueSettingsLabel(com.magi.app.v6.IssueKind.CONSTRAINT))
    }

    private fun shortfall(day: Int, shift: Int = 0) =
        com.magi.app.v6.CoverageShortfall(day, "", shift, "", 2, 1, 1, 3, com.magi.app.v6.CoverageVerdict.FIXABLE, "")
}
