package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.694.0] 操作ログの行の形を固定する（C# `OpLogTextTest` と同じ定数）。VM の配線（どの値を渡すか）は android-sdk.yml のコンパイルと
 * 次回の端末ログで確かめる＝ここは語・区切り「 ・ 」・桁を守る。
 */
class OpLogTextTest {
    @Test fun hex16IsSixteenLowercaseDigits() {
        assertEquals("0000000000000000", OpLogText.hex16(0L))
        assertEquals("0000000000000001", OpLogText.hex16(1L))
        assertEquals("ffffffffffffffff", OpLogText.hex16(-1L))
        assertEquals("8000000000000000", OpLogText.hex16(Long.MIN_VALUE))
    }

    @Test fun periodUsesIsoDatesWithYear() {
        assertEquals("2026-10-01〜2026-10-31（31日）", OpLogText.period("2026-10-01", "2026-10-31", 31))
    }

    @Test fun droppedCountIsSizeOverCap() {
        assertEquals(1000, OpLogText.OP_LOG_CAP)
        assertEquals(0, OpLogText.droppedBy(1000))
        assertEquals(1, OpLogText.droppedBy(1001))
        assertEquals(0, OpLogText.droppedBy(3))
    }

    @Test fun dropNoteNamesStartAndDroppedCountWithoutRunNumber() {
        assertEquals("※この記録は 2026/10/16 10:07:12 からの分です。上限1000件を超えて捨てた古い操作は 0 件です。", OpLogText.dropNote("2026/10/16 10:07:12", 0))
        val n = OpLogText.dropNote("2026/10/16 10:07:12", 234)
        assertEquals("※この記録は 2026/10/16 10:07:12 からの分です。上限1000件を超えて捨てた古い操作は 234 件です。", n)
        assertFalse(n.contains("実行#"))
    }

    @Test fun exportStateLineHasPeriodAndFingerprintsOrNone() {
        assertEquals("期間=2026-10-01〜2026-10-31（31日） ・ 状態指紋=1f0e3c9a7b2d4e60 ・ 盤面指紋=9c0d61aa3e55f218",
            OpLogText.exportStateLine("2026-10-01", "2026-10-31", 31, 0x1f0e3c9a7b2d4e60L, 0x9c0d61aa3e55f218uL.toLong()))
        assertEquals("期間=なし", OpLogText.exportStateLine(null, null, 0, null, null))
        // 盤面がまだ無いときは盤面指紋だけ落とす。
        assertEquals("期間=2026-10-01〜2026-10-31（31日） ・ 状態指紋=0000000000000001", OpLogText.exportStateLine("2026-10-01", "2026-10-31", 31, 1L, null))
    }

    @Test fun startLineAppendsPeriodStaffAndFingerprints() {
        assertEquals("最適化 開始 (予算60s, 並列8, 方式AUTO) ・ 期間=2026-10-01〜2026-10-31（31日） ・ 職員=11名 ・ 状態指紋=1f0e3c9a7b2d4e60 ・ 盤面指紋=9c0d61aa3e55f218",
            OpLogText.startLine("最適化 開始 (予算60s, 並列8, 方式AUTO)", "2026-10-01", "2026-10-31", 31, 11, 0x1f0e3c9a7b2d4e60L, 0x9c0d61aa3e55f218uL.toLong()))
        assertEquals("初期解生成 開始 ・ 期間=2026-10-01〜2026-10-31（31日） ・ 職員=11名 ・ 状態指紋=0000000000000001 ・ 盤面指紋=0000000000000002",
            OpLogText.startLine("初期解生成 開始", "2026-10-01", "2026-10-31", 31, 11, 1L, 2L))
    }

    @Test fun completionLinesKeepTheOldHeadAndAppendBoard() {
        assertEquals("最適化 完了 必須=0 合計=123 (optimize:RSI→ALNS) ・ 盤面指紋=8a2f0d5e61c4b7a9",
            OpLogText.withBoard("最適化 完了 必須=0 合計=123 (optimize:RSI→ALNS)", 0x8a2f0d5e61c4b7a9uL.toLong()))
        assertEquals("初期解生成 完了 必須=3 合計=120 ・ 盤面指紋=9e1d0c4b7a52f3e8",
            OpLogText.withBoard("初期解生成 完了 必須=3 合計=120", 0x9e1d0c4b7a52f3e8uL.toLong()))
    }

    @Test fun loadLineNamesSourceWithoutFileName() {
        assertEquals("読込 11名/31日/10シフト ・ 期間=2026-10-01〜2026-10-31（31日） ・ 状態指紋=1f0e3c9a7b2d4e60 ・ 盤面指紋=0000000000000002 ・ 取得元=起動時の復元",
            OpLogText.loadLine(11, 31, 10, "2026-10-01", "2026-10-31", 0x1f0e3c9a7b2d4e60L, 2L, fromRestore = true))
        assertTrue(OpLogText.loadLine(11, 31, 10, "2026-10-01", "2026-10-31", 1L, 2L, fromRestore = false).endsWith(" ・ 取得元=利用者の読込"))
    }

    @Test fun fixLinesKeepTheOldCountsAndAddPeriodAndFingerprints() {
        assertEquals("改善手を適用: 12/7 休↔Cｱ（必須 2→1・合計 40→38） ・ 期間=2026-12-01〜2026-12-31（31日） ・ 盤面指紋=a03f77c2e9d15b60→9e1d0c4b7a52f3e8 ・ 状態指紋=5c1e0a9f3b27d814",
            OpLogText.fixLine("12/7 休↔Cｱ", 2, 1, 40, 38, "2026-12-01", "2026-12-31", 31, 0xa03f77c2e9d15b60uL.toLong(), 0x9e1d0c4b7a52f3e8uL.toLong(), 0x5c1e0a9f3b27d814L))
        assertEquals("改善手を見送り: 12/7 休↔Cｱ（案を出したときと違う: 盤面指紋 案=5e21c0d8a7f3b916 今=7a0c4e91d2b58f63 ・ 状態指紋 案=0000000000000001 今=0000000000000002）",
            OpLogText.fixMismatchLine("12/7 休↔Cｱ", 0x5e21c0d8a7f3b916L, 0x7a0c4e91d2b58f63L, 1L, 2L))
        assertEquals("改善手を見送り: 12/7 休↔Cｱ（案の出どころが違う: $FIX_ORIGIN_UNKNOWN_MESSAGE）", OpLogText.fixRefusalLine("12/7 休↔Cｱ", FIX_ORIGIN_UNKNOWN_MESSAGE))
    }

    @Test fun periodChangeAppendsRequestedPeriod() {
        assertEquals("期間変更: 31日 → 30日 ・ 要求期間=2026-12-01〜2026-12-30（30日）", OpLogText.withRequestedPeriod("期間変更: 31日 → 30日", "2026-12-01", "2026-12-30", 30))
        assertEquals("期間変更: 2026年12月（希望を残して） ・ 要求期間=2026-12-01〜2026-12-31（31日）",
            OpLogText.withRequestedPeriod("期間変更: 2026年12月（希望を残して）", "2026-12-01", "2026-12-31", 31))
    }

    @Test fun strengthLineSpellsOutTheFourFlagsFromTheSameSourceAsApply() {
        assertEquals(SearchStrength.Flags(true, true, true, true), SearchStrength.THOROUGH.flags())
        assertEquals(SearchStrength.Flags(false, false, false, false), SearchStrength.NORMAL.flags())
        assertEquals("設定変更: 探索の強さ → じっくり（結合探索を粘り強く=ON・禁止連続の崩し範囲=ON・回数連鎖研磨=ON・公平化/適切回数の他ソフト許容(6%)=ON）",
            OpLogText.strengthLine("じっくり", SearchStrength.THOROUGH.flags()))
        assertEquals("設定変更: 探索の強さ → ふつう（結合探索を粘り強く=OFF・禁止連続の崩し範囲=OFF・回数連鎖研磨=OFF・公平化/適切回数の他ソフト許容(6%)=OFF）",
            OpLogText.strengthLine("ふつう", SearchStrength.NORMAL.flags()))
    }

    @Test fun settingsStartLineUsesTheSameWordsAsTuningSummary() {
        val words = com.magi.app.v6.TuningTelemetry.startSettings(true, true, false, true, false, false, true)
        assertEquals("設定の開始時: ネイティブ加速（設定）=ON / Kotlin照合（設定）=ON / 必須が増える候補の事前フィルタ=ON / 禁止連続の崩し範囲=OFF / 仕上げ最適化=ON" +
            " / 結合探索を粘り強く=OFF / 公平化/適切回数の他ソフト許容(6%)=OFF / 回数連鎖研磨=ON", OpLogText.settingsStartLine(words))
    }

    @Test fun fingerprintLinesCarryOnlyHexNotNames() {
        val line = OpLogText.exportStateLine("2026-10-01", "2026-10-31", 31, com.magi.app.v6.StateFingerprint.of(sampleState()), 7L)
        assertTrue(line, Regex("""^期間=\d{4}-\d{2}-\d{2}〜\d{4}-\d{2}-\d{2}（\d+日） ・ 状態指紋=[0-9a-f]{16} ・ 盤面指紋=[0-9a-f]{16}$""").matches(line))
        assertFalse(line.contains("吉江"))
    }

    private fun sampleState() = com.magi.app.model.MagiState(
        startDate = "2026-10-01", endDate = "2026-10-03",
        shifts = listOf(com.magi.app.model.Shift("休", "休", "0", "1", com.magi.app.model.ShiftRole.Rest), com.magi.app.model.Shift("A", "A", "1", "2")),
        groups = listOf(com.magi.app.model.Group("G", "G")),
        staff = listOf(com.magi.app.model.Staff("吉江雄貴", 0, 0)),
        use2Patterns = false, groupShift = listOf(listOf(1, 1)), groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(listOf(0, 1, 0)), wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(), cons3m = emptyList(), cons3mn = emptyList(),
        cons41 = emptyList(), cons42 = emptyList(),
    )
}
