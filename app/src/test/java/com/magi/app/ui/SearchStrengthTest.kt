package com.magi.app.ui

import com.magi.app.v6.PolishGate
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchStrengthTest {
    private fun flags() = listOf(PolishGate.combineExhaustPairs, PolishGate.wideC3nBreakDays,
        PolishGate.countChainPolish, PolishGate.aptFairSoftTolerance)

    @Test fun thoroughSetsFourFlagsAndNormalRestoresDefaults() {
        val saved = flags()
        try {
            SearchStrength.THOROUGH.apply()
            assertEquals(listOf(true, true, true, true), flags())
            SearchStrength.NORMAL.apply()
            assertEquals(listOf(false, false, false, false), flags())
            assertEquals(SearchStrength.NORMAL, UiState().searchStrength)
        } finally {
            PolishGate.combineExhaustPairs = saved[0]; PolishGate.wideC3nBreakDays = saved[1]
            PolishGate.countChainPolish = saved[2]; PolishGate.aptFairSoftTolerance = saved[3]
        }
    }
}
