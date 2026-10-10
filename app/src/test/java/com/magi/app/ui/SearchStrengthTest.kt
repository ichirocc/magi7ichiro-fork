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
            assertEquals(false, UiState().c1MoveARepair)
            assertEquals(false, PolishGate.c1MoveARepair)
            assertEquals(false, UiState().aptFairToleranceUnguarded)
            assertEquals(true, com.magi.app.v6.AptFairPolish.heavySoftGuard)
            assertEquals(EjectionChainMode.PIPELINE, UiState().ejectionChain)
            assertEquals(false, PolishGate.c1EjectionChain)
            assertEquals(false, PolishGate.allFamilyEjectionChain)
            assertEquals(com.magi.app.v6.EjectionChainPipeline.Focus.BOTH, PolishGate.ejectionPipelineFocus)
            assertEquals(true, PolishGate.ejectionPipelineAfterRepair)
            assertEquals(true, UiState().ejectionChainSwap)
            assertEquals(true, PolishGate.ejectionChainSwapMoves)
            assertEquals(PolishGate.ejectionPipelineRounds, UiState().ejectionPipelineRounds)
            assertEquals(PolishGate.ejectionPipelineHardLeg, UiState().ejectionPipelineHardLeg)
        } finally {
            PolishGate.combineExhaustPairs = saved[0]; PolishGate.wideC3nBreakDays = saved[1]
            PolishGate.countChainPolish = saved[2]; PolishGate.aptFairSoftTolerance = saved[3]
        }
    }

    @Test fun ejectionChainModeRoutesToExactlyOneFlag() {
        val off = com.magi.app.v6.EjectionChainPipeline.Focus.OFF
        fun flags() = listOf(PolishGate.c1EjectionChain, PolishGate.allFamilyEjectionChain, PolishGate.ejectionPipelineFocus)
        try {
            EjectionChainMode.C1.apply()
            assertEquals(listOf(true, false, off), flags())
            EjectionChainMode.ALL.apply()
            assertEquals(listOf(false, true, off), flags())
            EjectionChainMode.OFF.apply()
            assertEquals(listOf(false, false, off), flags())
        } finally {
            EjectionChainMode.PIPELINE.apply()   // 既定へ戻す（同じ JVM の後続テストが既定で走るように）
        }
        assertEquals(listOf(false, false, com.magi.app.v6.EjectionChainPipeline.Focus.BOTH), flags())
    }
}
