package com.magi.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 実行 A を B で置き換えたあと、A がどの出口（遅れた進捗・途中最良の退避・結果の保存・完了／停止／失敗の片付け）を通っても
 * B の入力・印・結果を変えない（外部レビュー P0「Worker の実行所有権を最後まで一貫させる」の合格条件）。
 */
class WorkerReplaceRaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = 1001L
    private val b = 2002L

    /** A が走り出したところで B が投入される（UI の runInBackground と同じ順＝印を立ててから旧途中状態を掃除し、入力を書く）。 */
    private fun replaced(): RunFiles {
        val f = RunFiles(tmp.root)
        assertTrue(f.beginRun(a))
        assertTrue(f.writeAtomically(f.input, "A-input") { f.owns(a) })
        assertTrue(f.writeAtomically(f.snapshot, "A-best") { f.owns(a) })
        assertTrue(f.beginRun(b))
        f.clear(keepRunId = true)
        assertTrue(f.writeAtomically(f.input, "B-input") { f.owns(b) })
        return f
    }

    private fun assertBIntact(f: RunFiles) {
        assertEquals(b, f.activeRunId())
        assertEquals("B-input", f.input.readText())
    }

    @Test fun lateProgressOfTheReplacedRunIsDroppedForever() {
        val f = RunFiles(tmp.root)
        assertTrue(f.beginRun(a))
        val gate = ProgressGate(200L) { f.owns(a) }
        assertEquals(ProgressGate.Step.PUBLISH, gate.next(0L))
        assertEquals(ProgressGate.Step.QUIET, gate.next(100L))   // 窓の中は出さない（所有権も見ない）
        assertTrue(f.beginRun(b))
        assertEquals(ProgressGate.Step.QUIET, gate.next(150L))
        assertEquals(ProgressGate.Step.DROP, gate.next(250L))    // 窓が開いた最初の回で喪失に気づく
        assertTrue(f.beginRun(a))                                 // 印が戻っても（あり得ないが）喪失は単調
        assertEquals(ProgressGate.Step.DROP, gate.next(1_000L))
        assertTrue(gate.lost)
        assertEquals(2, gate.dropped)
    }

    @Test fun replacedRunCannotWriteSnapshotOrResult() {
        val f = replaced()
        assertFalse(f.writeAtomically(f.snapshot, "A-best-late") { f.owns(a) })
        assertFalse(f.writeAtomically(f.result, "A-result") { f.owns(a) })
        assertFalse(f.snapshot.exists())
        assertFalse(f.result.exists())
        assertBIntact(f)
    }

    @Test fun replacedRunsCompletionCleanupTouchesNothing() {
        val f = replaced()
        assertNull(f.releaseIfOwner(a, deleteInputs = true))
        assertBIntact(f)
    }

    @Test fun replacedRunsStopOrFailureCleanupTouchesNothing() {
        val f = replaced()
        assertNull(f.clearIfOwner(a))
        assertBIntact(f)
    }

    @Test fun theOwnerStillCleansUpOnStopOrFailure() {
        val f = replaced()
        val stuck = f.clearIfOwner(b)
        assertNotNull(stuck)
        assertTrue(stuck!!.isEmpty())
        assertFalse(f.input.exists())
        assertEquals(0L, f.activeRunId())
    }

    @Test fun theNewRunCompletesNormallyAfterTheOldOneGaveUp() {
        val f = replaced()
        assertNull(f.clearIfOwner(a))
        assertTrue(f.writeAtomically(f.result, "B-result") { f.owns(b) })
        assertEquals(emptyList<String>(), f.releaseIfOwner(b, deleteInputs = true))
        assertEquals("B-result", f.result.readText())
        assertFalse(f.input.exists())
        assertEquals(0L, f.activeRunId())
    }
}
