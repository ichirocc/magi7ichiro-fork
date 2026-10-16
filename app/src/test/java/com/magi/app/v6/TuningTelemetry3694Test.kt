package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [3.694.0] 「設定の効き」と「設定の開始時」が同じ 9 語を同じ並びで使うこと、summary の文字列が従来のままであることを固定する。 */
class TuningTelemetry3694Test {
    @Test fun startSettingsListsNineTogglesInTheFixedOrder() {
        val s = TuningTelemetry.startSettings(nativeOn = true, parityOn = true, filterOn = false, wideOn = false, softPolishOn = true,
            combineOn = false, lnsOn = true, aptFairOn = false, countChainOn = true)
        assertEquals("ネイティブ加速（設定）=ON / Kotlin照合（設定）=ON / 必須が増える候補の事前フィルタ=OFF / 禁止連続の崩し範囲=OFF / 仕上げ最適化=ON" +
            " / 結合探索を粘り強く=OFF / 一括見直しの自動調整=ON / 公平化/適切回数の他ソフト許容(6%)=OFF / 回数連鎖研磨=ON", s)
        assertEquals(listOf("ネイティブ加速（設定）", "Kotlin照合（設定）", "必須が増える候補の事前フィルタ", "禁止連続の崩し範囲", "仕上げ最適化", "結合探索を粘り強く",
            "一括見直しの自動調整", "公平化/適切回数の他ソフト許容(6%)", "回数連鎖研磨"), s.split(" / ").map { it.substringBefore("=") })
    }

    @Test fun summaryStillRendersTheSameStringThroughToggleWords() {
        val wide = PolishGate.wideC3nBreakDays; val filter = PolishGate.filterC3nIncrease
        try {
            TuningTelemetry.reset(); PolishGate.wideC3nBreakDays = false; PolishGate.filterC3nIncrease = true
            TuningTelemetry.c3nFilterSkipped.set(12); TuningTelemetry.aptFairToleranceUsed.set(3)
            val s = TuningTelemetry.summary(nativeOn = true, parityOn = false, softPolishOn = true,
                combineExhaustPairs = true, lnsAdaptive = false, aptFairSoftTolerance = true, countChainPolish = false)
            assertEquals("ネイティブ加速=ON / Kotlin照合=OFF / 必須が増える候補の事前フィルタ=ON(12件の必ず却下される検査を省略) / 禁止連続の崩し範囲=OFF" +
                " / 仕上げ最適化=ON(この実行では観測なし) / 結合探索を粘り強く=ON / 一括見直しの自動調整=OFF" +
                " / 公平化/適切回数の他ソフト許容(6%)=ON(3回、却下されるはずの手を採用) / 回数連鎖研磨=OFF", s)
        } finally { PolishGate.wideC3nBreakDays = wide; PolishGate.filterC3nIncrease = filter; TuningTelemetry.reset() }
    }

    @Test fun toleratedLineFormatsDecimalsWithLocaleRoot() {
        val n = TuningTelemetry.ToleratedNote("apt", 4, 3, 120, 118, 0, 0, rawDelta = 5.0, forgiven = 1.8, effectiveDelta = -1.8)
        assertEquals("1/3: apt 件数 4→3 ・ 合計 120→118 ・ 必須 0→0 ・ 容認 1.8 ・ 素の差 +5.0 ・ 実効差 -1.8", TuningTelemetry.toleratedLine(1, 3, n))
        // 実効差 0.0（合計の減で採用）は符号なし＝素の差だけ %+.1f（C# `TuningTelemetry.ToleratedLine` と同じ文字列）。
        val zero = TuningTelemetry.ToleratedNote("fair", 4, 3, 120, 118, 0, 0, rawDelta = 1.8, forgiven = 1.8, effectiveDelta = 0.0)
        assertEquals("2/3: fair 件数 4→3 ・ 合計 120→118 ・ 必須 0→0 ・ 容認 1.8 ・ 素の差 +1.8 ・ 実効差 0.0", TuningTelemetry.toleratedLine(2, 3, zero))
        TuningTelemetry.reset()
        try {
            repeat(7) { TuningTelemetry.noteTolerated("apt", 4, 3, 120, 118, 0, 0, 5.0, 1.8, -1.8) }
            val lines = TuningTelemetry.toleratedLines(7)
            assertEquals(6, lines.size)
            assertTrue(lines[4], lines[4].startsWith("5/7: apt"))
            assertEquals("ほか 2 件（先頭 5 件のみ）", lines[5])
            assertEquals(5, TuningTelemetry.toleratedLines(5).size)
        } finally { TuningTelemetry.reset() }
    }
}
