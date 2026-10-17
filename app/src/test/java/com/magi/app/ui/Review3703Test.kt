package com.magi.app.ui

import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.703.0] 人員不足の手伝い（H）と変更の確認（D）の文言。 */
class Review3703Test {
    private val snap = listOf(listOf(0, 0, 1), listOf(1, 0, 0), listOf(0, 2, 0))
    private val arr = Array(snap.size) { snap[it].toIntArray() }
    private val names = listOf("甲", "乙", "丙")
    private val syms = listOf("休", "日", "夜")
    private val sug = FixSuggestion(FixKind.CHAIN, listOf(FixCell(0, 2, 2), FixCell(2, 2, 1), FixCell(1, 0, 2)), "（玉突き）", deltaHard = -1, deltaTotal = 1,
        diff = listOf("covU" to -1, "covO" to 1))
    private val need = FixTarget(null, 2, 2, 1, true, "必要人数")

    @Test fun guidedFixTitlePerStage() {
        assertEquals("10/3の「夜」に入る人", guidedFixTitle(GuidedStage.CANDIDATES, "10/3", "夜"))
        assertEquals("10/3の「夜」を入れ替えで埋める", guidedFixTitle(GuidedStage.CHAIN, "10/3", "夜"))
        assertEquals("10/3の「夜」は埋められません", guidedFixTitle(GuidedStage.BLOCKED, "10/3", "夜"))
        assertEquals("残りの人員不足は埋められません", guidedFixTitle(GuidedStage.BLOCKED, null, null))
        assertEquals(SHORTAGE_DONE_TITLE, guidedFixTitle(GuidedStage.DONE, "10/3", "夜"))
    }

    @Test fun statusPointsAndCandidateLabel() {
        assertEquals(listOf("配置 1 / 必要 2", "動かせる人が3人います"), guidedStatusPoints(2, 1, 3))
        assertEquals(listOf("配置 1 / 必要 2"), guidedStatusPoints(2, 1, 0))
        assertEquals("山田さんをA4に入れる", guidedCandidateLabel("山田", "A4"))
        assertEquals("必須違反4か所を順に見る", guidedTourButton(4))
    }

    @Test fun summaryLineWithAndWithoutTarget() {
        val e = need.effect(snap, sug.ops)
        assertEquals("今回の不足 1人 → 0人（解消）・3か所を変更・新たな必須違反なし", previewSummaryLine(e, 3, -1, emptyList()))
        assertEquals("必須違反は減ります・1か所を変更・新たな必須違反は未評価（適用の直前に検査）", previewSummaryLine(null, 1, -1, null))
        assertEquals("2か所を変更・新たな必須違反 1件", previewSummaryLine(null, 2, 0, listOf("乙 10/1")))
        // 区切りごとに単位は 1 つ（INV-07）、未評価を「なし」と言わない（INV-10）。
        val units = listOf("人", "か所", "件", "回", "pt")
        listOf(previewSummaryLine(e, 3, -1, emptyList()), previewSummaryLine(null, 2, 1, listOf("x"))).forEach { line ->
            line.split("・").forEach { seg -> assertTrue(seg, units.count { u -> Regex("\\d+\\s*$u").containsMatchIn(seg) } <= 1) }
        }
        assertTrue("なし" !in previewSummaryLine(null, 1, 0, null))
    }

    @Test fun purposeAndEffectFilledOnChainAndCandidatePaths() {
        val base = chainFixPreview(sug, arr, names, syms, "2026-10-01").copy(fixTarget = need, newHard = emptyList())
        val p = fillPreview(base, snap, syms, "2026-10-01", names)
        assertEquals("10/3の夜の不足を減らす", p.purpose)
        assertEquals(listOf("10/3の夜：0人 → 1人", "今回の不足 1人 → 0人", "必要 1人を満たします"), p.effect)
        assertEquals("今回の不足 1人 → 0人（解消）・3か所を変更・新たな必須違反なし", p.summary)
    }

    @Test fun listAndContestPathsKeepCallerPurposeAndEffect() {
        val e = need.effect(snap, sug.ops)
        val eff = previewEffectLines("10/3の夜", e, need.basis)
        val base = chainFixPreview(sug, arr, names, syms, "2026-10-01").copy(purpose = "推奨A", effect = eff, fromList = true, fixTarget = need)
        val p = fillPreview(base, snap, syms, "2026-10-01", names)
        assertEquals("推奨A", p.purpose)
        assertEquals(eff, p.effect)
        assertTrue(p.summary!!.endsWith("新たな必須違反は未評価（適用の直前に検査）"))
    }

    @Test fun noTargetLeavesPurposeEmpty() {
        val p = fillPreview(chainFixPreview(sug, arr, names, syms, "2026-10-01"), snap, syms, "2026-10-01", names)
        assertNull(p.purpose)
        assertTrue(p.effect.isEmpty())
        assertTrue(p.summary!!.startsWith("必須違反は減ります"))
        assertEquals("この3か所の変更を適用する", applyCountButton(3))
    }
}
