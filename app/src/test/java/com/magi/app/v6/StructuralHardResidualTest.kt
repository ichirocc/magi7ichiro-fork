package com.magi.app.v6

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralHardResidualTest {
    private fun rep(vararg kv: Pair<String, Int>): ViolationReport {
        val b = kv.toMap()
        val h = b.values.sum()
        return ViolationReport(emptyMap(), emptyMap(), emptyMap(), breakdown = b, total = h, hard = h, soft = 0, weightedScore = 0.0)
    }

    // 外部レビュー R5: c3n 壁でも covU が床を超えて残るなら、解ける HARD がある＝追加精製を省略しない。
    @Test
    fun c3nWallWithRepairableCovUIsNotStructural() {
        assertFalse(V6FinalPort.isStructuralHardResidual(rep("c3n" to 1, "covU" to 2), hardFloor = 0) { true })
    }

    @Test
    fun c3nWallWithCovUAtFloorIsStructural() {
        assertTrue(V6FinalPort.isStructuralHardResidual(rep("c3n" to 1, "covU" to 2), hardFloor = 2) { true })
        assertTrue(V6FinalPort.isStructuralHardResidual(rep("covU" to 2), hardFloor = 2) { false })
        assertFalse(V6FinalPort.isStructuralHardResidual(rep("c3n" to 1), hardFloor = 0) { false })
        assertFalse(V6FinalPort.isStructuralHardResidual(rep("pref" to 1), hardFloor = 5) { true })
    }

    // [E0] 希望衝突の床に届いた HARD は解けない残り。c3w は希望どうしの衝突で証明された件数までだけ c3n と同列。
    @Test
    fun wishConflictFloorAndProvenC3wAreStructural() {
        assertTrue(V6FinalPort.isStructuralHardResidual(rep("c3n" to 3, "c3w" to 2), hardFloor = 0, wishReached = true) { false })
        assertFalse(V6FinalPort.isStructuralHardResidual(rep("c3n" to 3, "c3w" to 2), hardFloor = 0) { false })
        assertTrue(V6FinalPort.isStructuralHardResidual(rep("c3n" to 3, "c3w" to 2), hardFloor = 0, c3wProven = 2) { true })
        assertFalse(V6FinalPort.isStructuralHardResidual(rep("c3n" to 3, "c3w" to 2), hardFloor = 0, c3wProven = 1) { true })
        assertFalse(V6FinalPort.isStructuralHardResidual(rep("c3n" to 3, "c3w" to 2), hardFloor = 0) { true })
    }
}
