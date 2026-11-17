package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 期間の制約に要る最少回数と、それを下回る・ちょうどの個人の上限の案内（2026-11-17 実機ログ: 休 10 回固定と「14日で休5回以上」）。 */
class C1RangeBalanceTest {
    private fun load(name: String): MagiState = StateParser.parse(javaClass.getResource("/$name")!!.readText())!!

    /** 小さい規模の総当たり（どの窓にも r 回以上）と一致する。 */
    @Test fun minCountMatchesBruteForce() {
        for (t in 1..14) for (w in 1..t) for (r in 0..w) {
            var best = Int.MAX_VALUE
            for (mask in 0 until (1 shl t)) {
                val ok = (0..t - w).all { s -> (s until s + w).count { mask shr it and 1 == 1 } >= r }
                if (ok) best = minOf(best, Integer.bitCount(mask))
            }
            assertEquals("t=$t w=$w r=$r", if (r == 0) 0 else best, V6SanityPort.c1MinCount(t, w, r))
        }
        assertEquals(10, V6SanityPort.c1MinCount(31, 14, 5))
        assertEquals(8, V6SanityPort.c1MinCount(31, 7, 2))
    }

    @Test fun guidanceFlagsCapsBelowAndAtTheMinimum() {
        val st0 = load("sample_state_v6.json")
        val p0 = Problem(st0)
        val rest = p0.restIdx!!
        val w = 14; val r = 5
        val need = V6SanityPort.c1MinCount(p0.T, w, r)
        val staff = (0 until p0.S).filter { p0.canDo(it, rest) }.take(2)
        val st = st0.copy(
            cons1 = listOf(com.magi.app.model.C1Row(w.toString(), st0.shifts[rest].kigou, r.toString())),
            staffRange = st0.staffRange + mapOf(
                "${staff[0]},$rest" to com.magi.app.model.Range("", (need - 1).toString()),
                "${staff[1]},$rest" to com.magi.app.model.Range("", need.toString())),
        )
        val issues = V6SanityPort.buildGuidance(st).filter { "期間の制約「${st0.shifts[rest].kigou} ${w}日で${r}回以上」" in it.where }
        assertTrue(issues.any { !it.neutral && "必ず足りません" in it.problem && st0.staff[staff[0]].name in it.where })
        assertTrue(issues.any { it.neutral && "等間隔" in it.problem && st0.staff[staff[1]].name in it.where })
    }

    /** 休の上限 0 の日を月末に集めると、窓の延べ人数が足りないと知らせる（下限つき）。上限 0 の日が無ければ出さない。 */
    @Test fun guidanceFlagsWindowsWithoutEnoughRoom() {
        val st0 = load("sample_state_v6.json")
        val p0 = Problem(st0)
        val rest = p0.restIdx!!
        val rule = listOf(com.magi.app.model.C1Row("14", st0.shifts[rest].kigou, "5"))
        val days = (p0.T - 8 until p0.T)
        val st = st0.copy(cons1 = rule,
            needDay1 = st0.needDay1 + days.associate { "$rest,$it" to "0" }, needDay2 = st0.needDay2 + days.associate { "$rest,$it" to "0" })
        val hit = V6SanityPort.buildGuidance(st).filter { "延べ人数" in it.problem }
        assertTrue(hit.isNotEmpty())
        assertTrue("上限 0 の日" in hit.first().problem && "少なくとも" in hit.first().problem)
        assertTrue(V6SanityPort.buildGuidance(st0.copy(cons1 = rule)).none { "延べ人数" in it.problem && "上限 0 の日" in it.problem })
    }
}
