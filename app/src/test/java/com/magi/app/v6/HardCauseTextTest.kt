package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

class HardCauseTextTest {
    @Test fun namesEveryKindAcrossDays() {
        val cores = listOf(
            HardCauses.Core(listOf(
                HardCauses.Cause(HardCauses.Kind.WISH, 0, 9, 1, -1),
                HardCauses.Cause(HardCauses.Kind.NEED, -1, 8, 2, 1),
                HardCauses.Cause(HardCauses.Kind.FORBIDDEN_SEQ, -1, -1, -1, 0),
            ), minimal = true),
            HardCauses.Core(listOf(HardCauses.Cause(HardCauses.Kind.PIN, 1, 0, 0, -1), HardCauses.Cause(HardCauses.Kind.OUTSIDE, 1, -1, -1, -1)), minimal = false),
        )
        val out = HardCauseText.lines(cores, staff = { listOf("福澤", "金沢")[it] }, shift = { listOf("休", "有", "Dﾃ")[it] },
            day = { "10/${it + 1}" }, seq = { "Dﾃ→Aｱ" })
        assertEquals(listOf(
            "#1: 希望 福澤 10/10=有 ・ 必要人数 10/9 Dﾃ 1人 ・ 禁止の並び Dﾃ→Aｱ",
            "#2（最小か未確認）: 手動固定 金沢 10/1=休 ・ 担当外・拡張希望の禁止 金沢",
        ), out)
    }

    @Test fun blocksSplitTheHeadlineDayFromOtherDays() {
        val cores = listOf(
            HardCauses.Core(listOf(
                HardCauses.Cause(HardCauses.Kind.WISH, 0, 9, 1, -1),
                HardCauses.Cause(HardCauses.Kind.FORBIDDEN_SEQ, -1, -1, -1, 0),
                HardCauses.Cause(HardCauses.Kind.NEED, -1, 8, 2, 1),
                HardCauses.Cause(HardCauses.Kind.NEED, -1, 9, 0, 1),
                HardCauses.Cause(HardCauses.Kind.NEED, -1, 9, 2, 2),
            ), minimal = true),
            HardCauses.Core(listOf(HardCauses.Cause(HardCauses.Kind.WISH, 0, 0, 2, -1), HardCauses.Cause(HardCauses.Kind.WISH, 0, 1, 0, -1)), minimal = false),
            HardCauses.Core(listOf(HardCauses.Cause(HardCauses.Kind.WISH, 0, 0, 2, -1), HardCauses.Cause(HardCauses.Kind.PIN, 1, 0, 0, -1),
                HardCauses.Cause(HardCauses.Kind.OUTSIDE, 1, -1, -1, -1)), minimal = true),
        )
        val out = HardCauseText.blocks(cores, staff = { listOf("福澤", "金沢")[it] }, shift = { listOf("休", "有", "Dﾃ")[it] },
            day = { "10/${it + 1}" }, seq = { "Dﾃ→休" })
        assertEquals(listOf(
            HardCauseText.Block("10/10 の必要人数を満たせない", listOf(
                "希望: 福澤 10/10=有", "必要人数: 休 1人 ・ Dﾃ 2人", "別の日の必要人数: 10/9 Dﾃ 1人", "禁止の並び: Dﾃ→休", HARD_CAUSE_CROSS_DAY_NOTE), true),
            HardCauseText.Block("福澤さんの希望・固定が両立しない", listOf("希望: 福澤 10/1=Dﾃ ・ 福澤 10/2=休"), false),
            HardCauseText.Block("希望・固定が両立しない", listOf("希望: 福澤 10/1=Dﾃ", "手動固定: 金沢 10/1=休", "担当外・拡張希望の禁止: 金沢"), true),
        ), out)
    }

    @Test fun headlineDayTieGoesToTheLaterDay() {
        val core = HardCauses.Core(listOf(HardCauses.Cause(HardCauses.Kind.NEED, -1, 3, 0, 1), HardCauses.Cause(HardCauses.Kind.NEED, -1, 4, 0, 1)), minimal = true)
        val b = HardCauseText.blocks(listOf(core), staff = { "$it" }, shift = { "休" }, day = { "10/${it + 1}" }, seq = { "" }).single()
        assertEquals("10/5 の必要人数を満たせない", b.title)
        assertEquals(listOf("必要人数: 休 1人", "別の日の必要人数: 10/4 休 1人"), b.lines)
    }
}
