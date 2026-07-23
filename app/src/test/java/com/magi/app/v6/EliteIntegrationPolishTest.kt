package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EliteIntegrationPolishTest {
    // [検証で判明] 2職員同一群・単一勤務シフトの当初案は fair(群内公平化)/weekly(曜日平準化) が
    // 副次的に絡み、covO(weight1.0)が安いため s1 単独追加(b)だけでも weightedScore が before を
    // 下回り「単独で正式改善」になってしまっていた（手計算では見落としていたが check() は全19族を
    // 常に評価するため fair/weekly も非ゼロで寄与する）。2群1名ずつ(fair対象外=m<2)・2勤務シフト
    // X/Yで構成し直し、a/b とも「相手のシフトへ移る」ことで必ず covU(HARD,重み8000)を作る対称設計に
    // 変更（休/勤務の別を変えない=全候補でweeklyが定数化し、それも交絡しない）。これで両半移動は
    // hard>0という揺るぎない優先順位だけで非改善と判定でき、両方同時適用の完全swapだけが
    // 被覆・個人下限を同時に満たしhard=0へ改善する。
    private fun state(): MagiState = MagiState(
        startDate = "2026-08-01",
        endDate = "2026-08-01",
        shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("X勤務", "X", "1", ""),
            Shift("Y勤務", "Y", "1", ""),
        ),
        groups = listOf(Group("G0", "G0"), Group("G1", "G1")),
        staff = listOf(Staff("s0", 0), Staff("s1", 1)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1), listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
        schedule = listOf(
            listOf(1),
            listOf(2),
        ),
        wishes = emptyMap(),
        staffRange = mapOf(
            "0,2" to Range(lo = "1", hi = ""),
            "1,1" to Range(lo = "1", hi = ""),
        ),
        needDay1 = emptyMap(),
        needDay2 = emptyMap(),
        cons1 = emptyList(),
        cons2 = emptyList(),
        cons3 = emptyList(),
        cons3n = emptyList(),
        cons3m = emptyList(),
        cons3mn = emptyList(),
        cons41 = emptyList(),
        cons42 = emptyList(),
    )


    private fun pinState(): MagiState = MagiState(
        startDate = "2026-08-01",
        endDate = "2026-08-01",
        shifts = listOf(
            Shift("休", "休", "", ""),
            Shift("固定勤務", "X", "", ""),
            Shift("不足勤務", "Y", "1", ""),
        ),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1)),
        groupShiftApt = listOf(listOf("", "", "")),
        schedule = listOf(listOf(1)),
        wishes = emptyMap(),
        staffRange = mapOf(
            "0,1" to Range(lo = "1", hi = "1"),
            "0,2" to Range(lo = "1", hi = ""),
        ),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    private fun elite(
        st: MagiState,
        schedule: Array<IntArray>,
        role: HypothesisEpochRole,
        bridge: Boolean,
    ) = AdaptiveElite(
        schedule = schedule,
        report = UnifiedViolationChecker.check(st, schedule),
        role = role,
        worker = 1,
        epoch = 1,
        bridge = bridge,
    )

    @Test
    fun disagreementFusionCombinesTwoIndividuallyNonImprovingMovesIntoAFeasibleSwap() {
        val st = state()
        val root = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, root)
        // root: s0=X, s1=Y (被覆は満点だが個人下限が逆＝s0はYの下限未達、s1はXの下限未達で low×2)。
        // A: s0 を X→Y へ動かす。s0のY下限は満たすが、Xが空席(covU)・Yが2人(covO)の新規HARD/SOFTを作る。
        val a = root.copy2D().also { it[0][0] = 2 }
        // B: s1 を Y→X へ動かす。同型で X が2人(covO)・Y が空席(covU)を作る。
        val b = root.copy2D().also { it[1][0] = 1 }
        val ar = UnifiedViolationChecker.check(st, a)
        val br = UnifiedViolationChecker.check(st, b)
        assertTrue("A単独は正式改善でない", !AdaptiveEliteArchive.better(ar, before))
        assertTrue("B単独は正式改善でない", !AdaptiveEliteArchive.better(br, before))

        val result = EliteIntegrationPolish.apply(
            st,
            root,
            elites = listOf(
                elite(st, a, HypothesisEpochRole.HARD_DEBT_RSI_PLUS, bridge = true),
                elite(st, b, HypothesisEpochRole.PERSONAL_RSI, bridge = false),
            ),
            shouldStop = { false },
            deadlineMs = System.currentTimeMillis() + 3_000L,
            config = EliteIntegrationPolish.Config(maxPairs = 0, maxFusionGroups = 4, maxFusionCells = 4),
        )
        val after = UnifiedViolationChecker.check(st, result.schedule)
        assertTrue(AdaptiveEliteArchive.better(after, before))
        // weekly(曜日平準化)は両職員とも全期間「勤務」のまま(休/勤務の別を変えない設計)のため
        // 全候補で定数2のまま残る＝完全な0ではなく before(low×2=4) からの改善(4→2)を確認する。
        assertEquals(2, after.total)
        assertEquals(2, result.schedule[0][0])
        assertEquals(1, result.schedule[1][0])
        assertTrue(result.fusionImprovements > 0)
        for (i in root.indices) assertTrue(root[i].contentEquals(st.schedule[i].toIntArray()))
    }

    @Test
    fun bridgeScheduleIsNeverReturnedDirectly() {
        val st = state()
        val root = st.schedule.toIntArray2D()
        val bridge = root.copy2D().also { it[0][0] = 0 }
        val result = EliteIntegrationPolish.apply(
            st, root,
            listOf(elite(st, bridge, HypothesisEpochRole.HARD_DEBT_RSI_PLUS, bridge = true)),
            shouldStop = { false },
            deadlineMs = System.currentTimeMillis() + 1_000L,
        )
        for (i in root.indices) assertTrue(root[i].contentEquals(result.schedule[i]))
    }

    @Test
    fun archivedReportIsNotTrustedWithoutOfficialRecheck() {
        val st = state()
        val root = st.schedule.toIntArray2D()
        val worse = root.copy2D().also { it[0][0] = 0 }
        val fakePerfect = UnifiedViolationChecker.check(st, root).copy(
            total = 0, hard = 0, soft = 0, weightedScore = 0.0,
        )
        val result = EliteIntegrationPolish.apply(
            st, root,
            listOf(AdaptiveElite(worse, fakePerfect, HypothesisEpochRole.DAY_BLOCK_ALNS, 1, 1, false)),
            shouldStop = { false },
            deadlineMs = System.currentTimeMillis() + 1_000L,
        )
        for (i in root.indices) assertTrue(root[i].contentEquals(result.schedule[i]))
    }


    @Test
    fun exactPinRegressionRejectsOtherwiseBetterElite() {
        val st = pinState()
        val root = st.schedule.toIntArray2D()
        val before = UnifiedViolationChecker.check(st, root)
        val candidate = arrayOf(intArrayOf(2))
        val candidateReport = UnifiedViolationChecker.check(st, candidate)
        assertTrue("全体目的だけなら改善する反例", AdaptiveEliteArchive.better(candidateReport, before))
        val result = EliteIntegrationPolish.apply(
            st, root,
            listOf(elite(st, candidate, HypothesisEpochRole.PERSONAL_RSI, bridge = false)),
            shouldStop = { false },
            deadlineMs = System.currentTimeMillis() + 1_000L,
        )
        for (i in root.indices) assertTrue(root[i].contentEquals(result.schedule[i]))
    }

    @Test
    fun expiredDeadlineIsNoOpAndDoesNotMutateInput() {
        val st = state()
        val root = st.schedule.toIntArray2D()
        val saved = root.copy2D()
        val result = EliteIntegrationPolish.apply(
            st, root, emptyList(), shouldStop = { false }, deadlineMs = 0L,
        )
        for (i in root.indices) {
            assertTrue(saved[i].contentEquals(root[i]))
            assertTrue(saved[i].contentEquals(result.schedule[i]))
        }
    }
}
