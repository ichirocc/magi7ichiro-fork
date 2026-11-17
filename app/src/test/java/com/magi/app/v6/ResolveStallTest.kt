package com.magi.app.v6

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解き直しへ移るのは、最短実行を過ぎてから探索が 10 秒改善しないときだけ。 */
class ResolveStallTest {
    @Test fun waitsForMinRun() = assertFalse(resolveStallReached(now = 20_000, startMs = 0, minRunMs = 45_000, lastImproveMs = 0))

    @Test fun firesAfterMinRunAndStall() = assertTrue(resolveStallReached(now = 50_000, startMs = 0, minRunMs = 45_000, lastImproveMs = 40_000))

    @Test fun recentImprovementKeepsSearching() = assertFalse(resolveStallReached(now = 50_000, startMs = 0, minRunMs = 45_000, lastImproveMs = 45_000))
}
