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
}
