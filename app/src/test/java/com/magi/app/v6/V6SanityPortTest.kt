package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6SanityPortTest {
    @Test fun detectsImpossibleWishAndInvalidAssignment() {
        val st = MagiState(
            startDate = "2026-06-01",
            endDate = "2026-06-02",
            shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 0)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1, 0)),
            wishes = mapOf("0,0" to 1),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val rep = V6SanityPort.build(st)
        assertEquals(1, rep.impossibleWishes.size)
        assertTrue(rep.warns.any { it.contains("実現不能") })
        assertTrue(rep.warns.any { it.contains("担当不可") })
    }

    /** ベース: 2職員×6日、A は 1日1スロット。cons1 A(窓3日で2回以上) を切替えて壁/ダイヤルを検証。 */
    private fun windowState(need1A: String, cons1: List<com.magi.app.model.C1Row>) = MagiState(
        startDate = "2026-06-01", endDate = "2026-06-06",
        shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", need1A, "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = List(2) { listOf(1, 1, 0, 1, 1, 0) },
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = cons1, cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun classifiesStructuralWindowWallButNotDial() {
        val cons = listOf(com.magi.app.model.C1Row("3", "A", "2"))   // A を3日窓で2回以上
        // 壁: A 供給=6(1/日×6) < 需要下界=2人×2回×floor(6/3=2)=8 → 構造的不能。
        val wall = V6SanityPort.buildGuidance(windowState("1", cons))
        assertTrue("A の窓が壁として案内される",
            wall.any { it.where.contains("A") && it.problem.contains("構造的に残ります") })
        // ダイヤル: A 供給=12(2/日×6) ≥ 需要8 → 案内しない。
        val dial = V6SanityPort.buildGuidance(windowState("2", cons))
        assertTrue("供給充足なら窓の壁案内は出さない",
            dial.none { it.where.contains("窓ルール") && it.problem.contains("構造的に残ります") })
    }

    /** [3.228.0/個人内壁検知] 1職員×31日、cons1(day1日窓でXが≥day2回)、Xの個人上限をhiで指定。 */
    private fun personalWallState(day1: String, day2: String, hi: String) = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-31",
        shifts = listOf(Shift("休", "休", "0", ""), Shift("X", "X", "0", "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(List(31) { 0 }),
        wishes = emptyMap(),
        staffRange = mapOf("0,1" to com.magi.app.model.Range("0", hi)),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = listOf(com.magi.app.model.C1Row(day1, "X", day2)),
        cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    // [3.228.0/ドッグフーディングで発見・修正] 壁/ダイヤル分類器(2b-2)は全体供給(集計)しか見ないため、
    // 「集計では担当者が大勢いても、この1人だけは自分の個人上限のせいで自分の窓ルールを満たせない」
    // 局面（例: 桒澤美幸のAｱ上限2×「14日窓Aｱ≥1」）を検知できていなかった。実データ相当(T=31,14日窓,
    // 下界=1×floor(31/14)=2)で、個人上限1(<下界2)は構造的不能として案内されることを固定する。
    @Test fun personalC1WallDetectsWhenRangeHiBelowConservativeMinimum() {
        val impossible = V6SanityPort.buildGuidance(personalWallState("14", "1", "1"))
        assertTrue("個人上限1<下界2は個人内で構造的不能として案内されること",
            impossible.any { it.where.contains("s0") && it.where.contains("個人上限と窓ルールの衝突") })
    }

    // [重要=当初の仮説を訂正] 美幸の実際の設定(個人上限2, 下界も2)は「上限==下界」で理論上ぎりぎり
    // 満たせる（false wallと誤検知してはいけない）。この保守的下界チェックでは壁と判定されないことを
    // 固定し、彼女の実際の停滞原因が「データの構造的矛盾」でなく「探索が最適配置を見つけていないこと」
    // であるという訂正済みの理解を裏付ける。
    @Test fun personalC1WallDoesNotFalselyFlagBorderlineSatisfiableCase() {
        val borderline = V6SanityPort.buildGuidance(personalWallState("14", "1", "2"))
        assertTrue("個人上限2==下界2は壁として誤検知しないこと",
            borderline.none { it.where.contains("個人上限と窓ルールの衝突") })
    }

    @Test fun personalC1WallIgnoresStaffWithoutPersonalCap() {
        // 個人上限が未設定(無制限)なら誰でも壁にはならない。
        val uncapped = V6SanityPort.buildGuidance(personalWallState("14", "1", ""))
        assertTrue(uncapped.none { it.where.contains("個人上限と窓ルールの衝突") })
    }

    // [3.227.0/c1内訳] 「違反詳細 c1(N件)」はDETAIL_CAP=8で打ち切られ職員別の内訳が読めないため、
    // 職員×窓ルール別の全件集計を別行で出すようにした。s0のみ「休(5日窓≥2)」ルールに1件違反する
    // 最小盤面で、正確な件数がその1行に出ることを固定する。
    @Test fun violationDebugReportsC1CountsPerStaffAndRule() {
        val st = MagiState(
            startDate = "2026-06-01", endDate = "2026-06-07",
            shifts = listOf(Shift("休", "休", "0", ""), Shift("A", "A", "0", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0), Staff("s1", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 1)),
            groupShiftApt = listOf(listOf("", "")),
            // s0: 最初の5日が全て A（休0回、5日窓で休>=2に違反）／s1: 休とAの交互（常に窓内2回以上で違反なし）
            schedule = listOf(
                listOf(1, 1, 1, 1, 1, 0, 0),
                listOf(0, 1, 0, 1, 0, 1, 0),
            ),
            wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
            cons1 = listOf(com.magi.app.model.C1Row("5", "休", "2")),
            cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val sched = st.schedule.toIntArray2D()
        val report = UnifiedViolationChecker.check(st, sched)
        assertTrue("前提: c1違反が発生していること", (report.breakdown["c1"] ?: 0) > 0)
        val lines = V6SanityPort.buildViolationDebug(st, sched, report)
        val summary = lines.firstOrNull { it.contains("c1内訳") }
        assertTrue("c1内訳サマリ行が出力されること", summary != null)
        assertTrue("s0が休(5日窓≥2)で1件と表示されること", summary!!.contains("s0 休(5日窓≥2)1件"))
        assertTrue("s1は違反なしのため内訳に出ないこと", !summary.contains("s1 "))
    }
}
