package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-11: 族の表の 21 件が MirrorKeys（族・必須・重み）と一致し、位置の空間がチェッカーの記録先と一致する。 */
class ConstraintCatalogTest {
    @Test fun catalogMatchesMirrorKeys() {
        assertEquals(21, ConstraintCatalog.all.size)
        assertEquals(MirrorKeys.all, ConstraintCatalog.all.map { it.key })
        assertEquals(MirrorKeys.hard.toSet(), ConstraintCatalog.all.filter { it.hard }.map { it.key }.toSet())
        assertEquals(MirrorKeys.weights.keys, ConstraintCatalog.all.map { it.key }.toSet())
        ConstraintCatalog.all.forEach { assertEquals(MirrorKeys.weights.getValue(it.key), it.weight, 0.0) }
        // P5b: 消費者が持っていた必須の族の組と同じ（C1EjectionChainPolish・セルのシート・確認一覧）。
        assertEquals(setOf("c3n", "covU", "c3w", "pref", "groupViol", "extWish"), ConstraintCatalog.hardKeys)
        assertEquals(setOf("c3n", "c3w", "pref", "groupViol", "extWish"), ConstraintCatalog.hardCellKeys)
    }

    /** チェッカーが記録した位置の族が、表の空間と同じ表（セル・回数・人数）に入っている（6 fixture）。 */
    @Test fun spacesMatchWhereTheCheckerRecords() {
        val fam = { cls: String -> cls.removePrefix("vio-").let { if (it == "aptLow" || it == "aptHigh") "apt" else it } }
        for (name in CheckerSnapshotTest.FIXTURES) {
            val json = javaClass.getResourceAsStream("/$name.json")?.bufferedReader()?.readText()
            assertNotNull(json)
            val st = StateParser.parse(json!!)!!
            val r = UnifiedViolationChecker.check(st, st.schedule.toIntArray2D())
            r.cellFamilies.values.flatten().map(fam).forEach { assertEquals("$name セル: $it", KeySpace.CELL, ConstraintCatalog.space(it)) }
            r.countFamilies.values.flatten().map(fam).forEach { assertEquals("$name 回数: $it", KeySpace.COUNT, ConstraintCatalog.space(it)) }
            r.needFamilies.values.flatten().map(fam).forEach { assertEquals("$name 人数: $it", KeySpace.NEED, ConstraintCatalog.space(it)) }
            assertTrue(r.breakdown.keys.all { ConstraintCatalog.spec(it) != null })
        }
    }
}
