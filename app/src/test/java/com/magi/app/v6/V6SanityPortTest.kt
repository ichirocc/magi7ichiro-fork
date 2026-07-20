package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /** [3.234.0→3.236.0/休の適切回数合計チェック誤検知修正→実質的上限へ差替え] 休は「1日に何人休んで
     *  よいか」という座席上限を持たないため need1(=seatsHi)との比較は無意味だが、「本当に過大」な設定は
     *  引き続き検出したい。休の実質的上限＝Σ_i(T − 他シフトの個人下限)と比較する新ロジックを検証する。
     *  T=days・staff数=2・他シフトへの個人下限はotherLoで指定（未指定なら無し）。 */
    private fun aptVsNeedState(days: Int, need1: String, aptTarget: String, otherLo: String = "") = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-${days.toString().padStart(2, '0')}",
        shifts = listOf(Shift("休", "休", need1, ""), Shift("X", "X", need1, "")),
        groups = listOf(Group("G", "G")),
        staff = listOf(Staff("s0", 0), Staff("s1", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf(aptTarget, aptTarget)),
        schedule = List(2) { List(days) { 0 } },
        wishes = emptyMap(),
        staffRange = if (otherLo.isBlank()) emptyMap() else mapOf("0,1" to com.magi.app.model.Range(otherLo, ""), "1,1" to com.magi.app.model.Range(otherLo, "")),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun aptSumCheckUsesRestCapacityInsteadOfNeedForRestShift() {
        // T=10・apt目標3(合計6)。休の実質上限=2人×10日=20 ≥ 6 → 誤検知しない。
        val rep = V6SanityPort.buildGuidance(aptVsNeedState(days = 10, need1 = "0", aptTarget = "3"))
        assertTrue("控えめな休の目標は誤検知しない(need=0との比較をやめた効果)",
            rep.none { it.where.contains("休") && it.where.contains("適切回数の合計") })
        assertTrue("同一設定の非休シフト(X)は従来どおり検出する",
            rep.any { it.where.contains("X") && it.where.contains("適切回数の合計") })
    }

    @Test fun aptSumCheckStillFlagsRestShiftWhenGenuinelyExcessive() {
        // T=2・apt目標5(合計10)。休の実質上限=2人×2日=4 < 10 → 本当に過大なので検出する。
        val rep = V6SanityPort.buildGuidance(aptVsNeedState(days = 2, need1 = "0", aptTarget = "5"))
        assertTrue("物理的に不可能な休の目標(T=2日に対し目標5)は検出すること",
            rep.any { it.where.contains("休") && it.where.contains("適切回数の合計") })
    }

    @Test fun aptSumCheckAccountsForOtherShiftLowerBoundsReducingRestCapacity() {
        // T=10・apt目標3(合計6)だが、他シフトXの個人下限が8(各自)設定済み＝休の実質上限=2人×(10-8)=4 < 6。
        val rep = V6SanityPort.buildGuidance(aptVsNeedState(days = 10, need1 = "0", aptTarget = "3", otherLo = "8"))
        assertTrue("他シフトの個人下限を差し引いた実質上限を下回るなら検出すること",
            rep.any { it.where.contains("休") && it.where.contains("適切回数の合計") })
    }

    // [3.242.0/6c=staffRange上限版・grilling確定=美幸・上條・大島の実例を踏まえ実装] 6bと同じ
    // 「担当レパートリーから強制される最低回数」ロジックを staffRange 上限(hi)にも適用する検査。
    // target(担当=休,X,Y): 休lo=hi=4固定・Yのhi=3・T=10日 → 休+Yの上限合計7では10日を埋めきれず、
    // 残り3日は必ずXに回る(強制下限3)。Xの個人上限は2なので、targetがXを担当し続ける限り上限超過は
    // 構造的に不可避。sub(G0=target側と同じグループ)はXを担当可能＝代用要員候補として提示されるはず。
    private fun rangeHiWallState(subCanDoX: Boolean): MagiState = MagiState(
        startDate = "2026-08-01", endDate = "2026-08-10",
        shifts = listOf(Shift("休", "休", "", ""), Shift("X", "X", "", ""), Shift("Y", "Y", "", "")),
        groups = listOf(Group("G0", "G0"), Group("G1", "G1")),
        staff = listOf(Staff("target", 0), Staff("sub", if (subCanDoX) 0 else 1)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1, 1), listOf(1, 0, 1)),   // G0=休,X,Y全部可 / G1=休,Yのみ可(X不可)
        groupShiftApt = listOf(listOf("", "", ""), listOf("", "", "")),
        schedule = listOf(List(10) { 0 }, List(10) { 0 }),
        wishes = emptyMap(),
        staffRange = mapOf(
            "0,0" to Range("4", "4"),   // target: 休 lo=hi=4固定
            "0,1" to Range("", "2"),    // target: X 上限2(対象)
            "0,2" to Range("", "3"),    // target: Y 上限3
        ),
        needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
    )

    @Test fun personalHighWallDetectsWhenForcedMinExceedsRangeHiAndListsSubstitute() {
        val rep = V6SanityPort.buildGuidance(rangeHiWallState(subCanDoX = true))
        val issue = rep.firstOrNull { it.where.contains("target") && it.where.contains("X") && it.where.contains("上限") }
        assertNotNull("担当構成上Xの強制下限(3)が上限(2)を超えるため検出されること", issue)
        assertTrue("代用要員候補(sub)が案内されること", issue!!.problem.contains("sub"))
    }

    @Test fun personalHighWallReportsNoSubstituteWhenNoneCanDo() {
        val rep = V6SanityPort.buildGuidance(rangeHiWallState(subCanDoX = false))
        val issue = rep.firstOrNull { it.where.contains("target") && it.where.contains("X") && it.where.contains("上限") }
        assertNotNull("subがXを担当できなくても壁自体は検出されること", issue)
        assertTrue("代用要員がいない旨が案内されること", issue!!.problem.contains("代用できる他の担当者がいません"))
    }

    @Test fun personalHighWallDoesNotFireWhenOtherShiftHasNoUpperBound() {
        // Yの上限を未設定(無制限)にすると、休+Y(無制限)だけで10日を埋めきれるため強制下限が0以下になり
        // 発火しない(6bと同じ保守的判定)。
        val st = rangeHiWallState(subCanDoX = true).let {
            it.copy(staffRange = it.staffRange - "0,2")
        }
        val rep = V6SanityPort.buildGuidance(st)
        assertTrue("他シフトに上限未設定が1つでもあれば誤検知しないこと",
            rep.none { it.where.contains("target") && it.where.contains("X") && it.where.contains("上限") })
    }
}
