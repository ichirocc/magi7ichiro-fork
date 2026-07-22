package com.magi.app.v6

import com.magi.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6PortAnalyzerTest {
    @Test
    fun v6OverviewComputesAptAndRisk() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-03",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "1")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = true,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "2")),
            schedule = listOf(listOf(1, 1, 1), listOf(0, 0, 0)),
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val report = UnifiedViolationChecker.check(st)
        val v6 = V6PortAnalyzer.analyze(st, st.schedule.toIntArray2D(), report)
        assertEquals(3, v6.demand)
        assertEquals(100, v6.coveragePct)
        assertTrue(v6.aptPenalty > 0.0)
        assertTrue(v6.sanityNotes.any { it.contains("groupShiftApt") })
    }

    // [実バグ修正の回帰] diagnoseCoverage が need1 のみを見て miss=need1-got を計算していたため、
    // need1 未設定・need2 単独定義（Problem.covUCell の「片方定義=その値」対応セル）の covU 違反が
    // 診断から丸ごと消えていた。need1="" / need2="2" で1人しか配置しない盤面を使い、
    // covUCell（source of truth）どおり不足1として検出されることを固定する。
    @Test
    fun diagnoseCoverageCatchesNeed2OnlyShortfall() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-01",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "", "2")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = true,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1), listOf(0)),
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val diag = V6PortAnalyzer.diagnoseCoverage(st)
        assertEquals(1, diag.totalShortfall)
        assertEquals(1, diag.shortfalls.size)
        val sf = diag.shortfalls.single()
        assertEquals(1, sf.shiftIndex)
        assertEquals(1, sf.got)
        assertEquals(1, sf.miss)
    }

    // [3.263.0, 600秒改善ゼロの深い停滞調査で判明] 「玉突き」判定は1ホップ(直接移動が別のcovUを
    // 生むか)のみで、その先が実際に埋まる保証がなかった。実データ(findCovUChainを200 seed総当たり)
    // で「玉突き候補はいるが下流の唯一の候補が希望固定で誰も動けない」真の壁を確認したため、
    // findCovUChainで実在を検証してから案内を出し分けるよう修正。この2件は同一形状(X の covU、
    // Aが唯一の直接候補でYを空けるとcascade、CがYを埋める唯一の depth2 候補)で、Cの希望有無だけを
    // 変え、chainVerified の有無で案内文が変わることを固定する。
    private fun cascadeChainState(cWished: Boolean): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-01",
        shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "1", ""), Shift("Y", "Y", "1", "")),
        groups = listOf(Group("GA", "GA"), Group("GC", "GC")),
        staff = listOf(Staff("A", 0), Staff("C", 1)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1), listOf(1, 0, 1)),   // GA:休/X/Y可 / GC:休/Y可(Xは不可)
        groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
        schedule = listOf(listOf(2), listOf(0)),                 // A=Y, C=休
        wishes = if (cWished) mapOf("1,0" to 0) else emptyMap(), // Cが休に希望固定
        staffRange = emptyMap(),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test
    fun diagnoseCoverageConfirmsCascadeWhenChainActuallyResolves() {
        val diag = V6PortAnalyzer.diagnoseCoverage(cascadeChainState(cWished = false))
        val sf = diag.shortfalls.single { it.shiftIndex == 1 }
        assertTrue("玉突き候補が本当に解消できるときは従来どおりの案内",
            sf.reason.contains("玉突き=ブロック移動") && sf.reason.contains("必要"))
        assertTrue("「どう組んでも解消できません」は出さない", !sf.reason.contains("どう組んでも"))
    }

    @Test
    fun diagnoseCoverageWarnsWhenCascadeIsBlockedByDownstreamWish() {
        // Cが唯一のdepth2候補だが休へ希望固定＝findCovUChainの候補から除外され連鎖が完成しない。
        val diag = V6PortAnalyzer.diagnoseCoverage(cascadeChainState(cWished = true))
        val sf = diag.shortfalls.single { it.shiftIndex == 1 }
        assertTrue("連鎖が実在しないことを正直に案内する",
            sf.reason.contains("どう組んでも解消できません"))
    }

    // [人員過剰(covO)の「なぜ減らないか」診断] 在勤2人のうち誰も希望固定・禁止連続に阻まれない盤面では
    // 「動かせる」人数が過剰人数と一致し、解消可能ヒントが出ることを固定する。
    @Test
    fun diagnoseCoverageMarksFreelyRelievableSurplus() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-01",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1), listOf(1)),   // 両者ともA（必要1に対し現状2＝過剰1、休へ動かす余地あり）
            wishes = emptyMap(),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val diag = V6PortAnalyzer.diagnoseCoverage(st)
        assertEquals(1, diag.totalSurplus)
        assertEquals(1, diag.surpluses.size)
        val sp = diag.surpluses.single()
        assertEquals(1, sp.shiftIndex)
        assertEquals(2, sp.got)
        assertEquals(1, sp.excess)
        assertTrue(sp.reason.contains("動かせる2人"))
        assertTrue(sp.reason.contains("解消可能"))
    }

    // 両者とも希望固定（希望どおりに配置済み＝pref違反ゼロ）だと、動かすと希望未充足に化けるため
    // 「動かせる」人数は0になり、希望調整が必要という理由が出ることを固定する
    // （実機ログで「回数制限のない有が増えない」問い合わせの根本原因の再現）。
    @Test
    fun diagnoseCoverageMarksWishPinnedSurplusAsUnmovable() {
        val st = MagiState(
            startDate = "2025-12-01",
            endDate = "2025-12-01",
            shifts = listOf(Shift("休み", "休", "", ""), Shift("早番", "A", "1", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1), listOf(1)),
            wishes = mapOf("0,0" to 1, "1,0" to 1),   // 両者ともAを希望固定
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val diag = V6PortAnalyzer.diagnoseCoverage(st)
        assertEquals(1, diag.totalSurplus)
        val sp = diag.surpluses.single()
        assertEquals(1, sp.excess)
        assertTrue(sp.reason.contains("希望固定2人"))
        assertTrue(sp.reason.contains("希望"))
    }
}
