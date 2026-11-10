package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

/** 原始型の状態表にした [C1TemporalDp.solve] が、HashMap 版（[C1TemporalDpReference]）と同じ答えを返すこと。 */
class C1TemporalDpTableTest {
    @Test fun sameAnswerAsHashMapVersion() {
        val rng = kotlin.random.Random(20261110)
        var nonNull = 0
        repeat(3000) { n ->
            val t = 7 + rng.nextInt(25)
            val k = rng.nextInt(4)
            val row = IntArray(t) { rng.nextInt(4) }
            val rules = List(1 + rng.nextInt(3)) { C1TemporalDp.Rule(2 + rng.nextInt(minOf(t, 14) - 1), 1 + rng.nextInt(3)) }
            val locked = BooleanArray(t) { rng.nextInt(6) == 0 }
            val reloc = 1 + rng.nextInt(6)
            val seed = rng.nextLong()
            val a = C1TemporalDp.solve(row, k, rules, locked, reloc, seed)
            val b = C1TemporalDpReference.solve(row, k, rules.map { C1TemporalDpReference.Rule(it.days, it.minimum) }, locked, reloc, seed)
            if (a != null) nonNull++
            assertEquals("case $n", b?.let { listOf(it.targetDays, it.fires, it.changedCells, it.relocations) },
                a?.let { listOf(it.targetDays, it.fires, it.changedCells, it.relocations) })
        }
        assert(nonNull > 300) { "答えのある場面が少なすぎる: $nonNull" }
    }
}
