package com.magi.app.ui

import com.magi.app.v6.FixApplyGate
import com.magi.app.v6.MirrorKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.690.0: 適用経路の強化（族名の網羅・指紋なしは断る・ホームの主操作）。 */
class Review3690Test {
    @Test fun everyHardFamilyHasItsOwnLabel() {
        for (fam in MirrorKeys.hard) {
            val l = FixApplyGate.famLabel(fam)
            assertNotEquals(fam, "必須条件", l)
            assertNotEquals(fam, fam, l)
        }
        assertEquals("必須条件", FixApplyGate.famLabel("unknownFamily"))
    }

    @Test fun previewApplyWithoutOriginIsRefused() {
        assertEquals(FIX_ORIGIN_UNKNOWN_MESSAGE, fixOriginRefusal(0L, fromPreview = true))
        assertNull(fixOriginRefusal(123L, fromPreview = true))
        assertNull(fixOriginRefusal(0L, fromPreview = false))
    }

    @Test fun nullTargetNeedsExplicitGlobalPolish() {
        assertEquals(FIX_TARGET_UNKNOWN_MESSAGE, fixTargetRefusal(null, globalPolish = false))
        assertNull(fixTargetRefusal(null, globalPolish = true))
        assertNull(fixTargetRefusal(FixTarget(null, 1, 0, 2, true, "必要人数"), globalPolish = false))
    }

    @Test fun homePlanHasExactlyOnePrimaryAction() {
        for (running in listOf(false, true)) for (hasResult in listOf(false, true)) for (hard0 in listOf(false, true))
            for (saveFailed in listOf(false, true)) for (consults in listOf(0, 2)) {
                val p = homePlan(running, hasResult, hard0, saveFailed = saveFailed, consults = consults)
                val expected = when {
                    running -> HomePlanKind.RUNNING
                    !hasResult -> HomePlanKind.MAKE
                    hard0 -> HomePlanKind.PRE_DIST
                    else -> HomePlanKind.LIST
                }
                val case = "running=$running hasResult=$hasResult hard0=$hard0 saveFailed=$saveFailed consults=$consults"
                assertEquals(case, expected, p.kind)
                if (running) { assertEquals(case, HomeAction.NONE, p.primary); assertFalse(case, p.primaryEnabled) }
                else { assertNotEquals(case, HomeAction.NONE, p.primary); assertTrue(case, p.primaryEnabled) }
                assertNotEquals(case, p.primary, p.helper)
            }
    }

    @Test fun homePlanOrderOfUnfinishedStages() {
        assertEquals(HomePlanKind.WISH_INFEASIBLE, homePlan(false, true, false, infeasible = true, hasPinned = true).kind)
        assertEquals(HomePlanKind.DATA_INFEASIBLE, homePlan(false, true, false, infeasible = true).kind)
        assertEquals(HomePlanKind.GUIDED_FIX, homePlan(false, true, false, guided = true, hasMove = true).kind)
        assertEquals(HomePlanKind.APPLY_MOVE, homePlan(false, true, false, hasMove = true).kind)
        assertEquals(HomePlanKind.SEARCHING, homePlan(false, true, false, hasMove = true, focusBlank = false, fixSearching = true).kind)
        assertEquals(HomePlanKind.RELAX, homePlan(false, true, false, fixSearched = true, relax = true, wishes = true).kind)
        assertEquals(HomePlanKind.WISH_FLOOR, homePlan(false, true, false, fixSearched = true, stalled = true, wishes = true).kind)
        assertEquals(HomePlanKind.WISHES, homePlan(false, true, false, wishes = true).kind)
    }
}
