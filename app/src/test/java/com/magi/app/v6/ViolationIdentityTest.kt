package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.663.0] 違反の同一性（種類＋職員＋日付、被覆は種類＋シフト＋日付）。件数が減っても別の場所に生まれた必須違反は見分ける。 */
class ViolationIdentityTest {
    private fun rep(cells: Map<String, List<String>> = emptyMap(), need: Map<String, List<String>> = emptyMap(),
                    count: Map<String, List<String>> = emptyMap(), b: Map<String, Int>): ViolationReport {
        val hard = b.filterKeys { it in MirrorKeys.hard }.values.sum()
        return ViolationReport(emptyMap(), emptyMap(), emptyMap(), cellFamilies = cells, countFamilies = count, needFamilies = need,
            breakdown = b, total = b.values.sum(), hard = hard, soft = b.values.sum() - hard, weightedScore = 0.0)
    }

    @Test fun keysSeparateFamilySpaceAndPlace() {
        val r = rep(cells = mapOf("0,3" to listOf("vio-c3n", "vio-c1")), need = mapOf("2,5" to listOf("vio-covU")),
            count = mapOf("1,0" to listOf("vio-aptLow")), b = mapOf("c3n" to 1, "c1" to 1, "covU" to 1, "apt" to 1))
        assertEquals(setOf(ViolationKey("c3n", ViolationKey.Space.CELL, 0, 3), ViolationKey("covU", ViolationKey.Space.NEED, 2, 5)),
            ViolationIdentity.keys(r, hardOnly = true))
        assertTrue(ViolationKey("apt", ViolationKey.Space.COUNT, 1, 0) in ViolationIdentity.keys(r))
    }

    /** A さん（0 行）の禁止の並び 2 件を解消し、B さん（1 行）に 1 件を作る＝件数は減るが新たな必須違反。 */
    @Test fun sameFamilyMovedToAnotherStaffIsANewHardViolation() {
        val before = rep(cells = mapOf("0,3" to listOf("vio-c3n"), "0,9" to listOf("vio-c3n")), b = mapOf("c3n" to 2))
        val after = rep(cells = mapOf("1,4" to listOf("vio-c3n")), b = mapOf("c3n" to 1))
        assertEquals(null, newHardFamilyViolation(before, after))   // 族の件数だけでは通ってしまう
        assertEquals(setOf(ViolationKey("c3n", ViolationKey.Space.CELL, 1, 4)), ViolationIdentity.newHard(before, after))
        assertTrue(introducesHardViolation(before, after))
    }

    @Test fun resolvingWithoutNewPlacesPasses() {
        val before = rep(cells = mapOf("0,3" to listOf("vio-c3n")), need = mapOf("2,5" to listOf("vio-covU")), b = mapOf("c3n" to 1, "covU" to 1))
        val after = rep(need = mapOf("2,5" to listOf("vio-covU")), cells = mapOf("1,1" to listOf("vio-c1")), b = mapOf("covU" to 1, "c1" to 1))
        assertFalse(introducesHardViolation(before, after))
        val d = ViolationIdentity.diff(before, after)
        assertEquals(setOf(ViolationKey("c3n", ViolationKey.Space.CELL, 0, 3)), d.resolved)
        assertEquals(setOf(ViolationKey("c1", ViolationKey.Space.CELL, 1, 1)), d.appeared)
    }
}
