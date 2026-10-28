package com.magi.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分析タブが「作成中」と見なす段（作成前の診断を並べない）。閉じている間の作成も含め、短い違反チェックは含めない。 */
class EngineBuildingTest {
    @Test fun foregroundAndBackgroundRunsButNotAQuickCheck() {
        assertTrue(engineBuilding(UiState(running = true, keepScreenOn = true)))
        assertTrue(engineBuilding(UiState(running = true, bgRunning = true)))
        assertFalse(engineBuilding(UiState(running = true)))
        assertFalse(engineBuilding(UiState(running = false, bgRunning = true)))
    }
}
