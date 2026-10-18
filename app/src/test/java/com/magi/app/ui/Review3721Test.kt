package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.721.0: 再作成しても同じ結果になる間は、下部の「再作成」を外枠にして塗りの主を変更案へ譲る。 */
class Review3721Test {
    @Test fun demotedOnlyWhenFreshAndAllHardWithinFloor() {
        assertTrue(rerunDemoted(hard = 2, floor = 2, fresh = true))
        assertTrue(rerunDemoted(hard = 1, floor = 3, fresh = true))
        assertFalse(rerunDemoted(hard = 3, floor = 2, fresh = true))
        assertFalse(rerunDemoted(hard = 2, floor = 2, fresh = false))
        assertFalse(rerunDemoted(hard = 0, floor = 0, fresh = true))
    }

    @Test fun footerKeepsExactlyOneFilledPrimary() {
        assertEquals(RerunFooter.FILLED, rerunFooter(demoted = false, primaryElsewhere = false, noFixFound = false))
        assertEquals(RerunFooter.OUTLINED, rerunFooter(demoted = true, primaryElsewhere = true, noFixFound = false))
        assertEquals(RerunFooter.OUTLINED, rerunFooter(demoted = false, primaryElsewhere = true, noFixFound = true))
        assertEquals(RerunFooter.FIX_SEARCH, rerunFooter(demoted = true, primaryElsewhere = false, noFixFound = false))
        assertEquals(RerunFooter.PROBLEM_LIST, rerunFooter(demoted = true, primaryElsewhere = false, noFixFound = true))
    }
}
