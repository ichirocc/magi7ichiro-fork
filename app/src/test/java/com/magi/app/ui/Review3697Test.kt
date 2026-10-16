package com.magi.app.ui

import com.magi.app.ui.OpLogText.UiOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.697.0] 画面の操作の行と「操作の要約（迷いの指標）」の形を固定する（C# `OpLogText` と同じ文字列）。
 * VM の配線（どこで呼ぶか）は android-sdk.yml のコンパイルと次回の端末ログで確かめる。
 */
class Review3697Test {
    @Test fun uiLinesUseTheFixedWords() {
        assertEquals("画面: ホーム", OpLogText.uiLine(UiOp.Tab(0)))
        assertEquals("画面: 勤務表", OpLogText.uiLine(UiOp.Tab(1)))
        assertEquals("画面: 編集", OpLogText.uiLine(UiOp.Tab(2)))
        assertEquals("画面: 分析", OpLogText.uiLine(UiOp.Tab(3)))
        assertEquals("画面: 設定", OpLogText.uiLine(UiOp.Tab(4)))
        assertEquals("巡回 開始: 必須違反 5か所", OpLogText.uiLine(UiOp.TourStart(5)))
        assertEquals("巡回 終了: 3/5", OpLogText.uiLine(UiOp.TourEnd(2), tourTotal = 5))
        assertEquals("作成前の確認: 開く", OpLogText.uiLine(UiOp.PreRunOpen))
        assertEquals("作成前の確認: 条件を確認する", OpLogText.uiLine(UiOp.PreRunCheckConditions))
        assertEquals("作成前の確認: 閉じる", OpLogText.uiLine(UiOp.PreRunClose))
        assertEquals("配布前の確認: 開く", OpLogText.uiLine(UiOp.PreDistOpen))
        assertEquals("配布前の確認: 書き出す", OpLogText.uiLine(UiOp.PreDistExport))
        assertEquals("配布前の確認: 戻る", OpLogText.uiLine(UiOp.PreDistBack))
        // 不足枠の日は CoverageShortfall.dayLabel「10/12(月)」から M/D だけ。
        assertEquals("人員不足の手伝い: 開く 10/12 Aｱ 候補 3人", OpLogText.uiLine(UiOp.GuidedOpen("10/12(月)", "Aｱ", 3)))
        assertEquals("人員不足の手伝い: 閉じる（適用なし）", OpLogText.uiLine(UiOp.GuidedClose))
    }

    @Test fun manualChangeLines() {
        assertEquals("手で変更: 甲 10/12 休→夜", OpLogText.cellChangeLine("甲", "10/12", "休", "夜"))
        assertEquals("手で変更: 甲 10/12 空→夜", OpLogText.cellChangeLine("甲", "10/12", OpLogText.EMPTY_CELL, "夜"))
        assertEquals("手で変更: 7セル → 休（一括）", OpLogText.cellsChangeLine(7, "休"))
    }

    @Test fun draftAndSearchAndPreviewLines() {
        assertEquals("下書きで置き換え（確認済み）: 必須 0件→3件", OpLogText.draftReplaceLine(0L, 3))
        assertEquals("案を探す: 11/3のAｱ", OpLogText.fixSearchStartLine("11/3のAｱ"))
        assertEquals("案を探す: 全体", OpLogText.fixSearchStartLine(OpLogText.FIX_ORIGIN_ALL))
        assertEquals("案: 8件（3秒）", OpLogText.fixSearchEndLine(8, 3400))
        assertEquals("案: なし（8秒）", OpLogText.fixSearchEndLine(0, 8020))
        assertEquals("変更の確認: 開く 12/7 休↔Cｱ", OpLogText.previewOpenLine("12/7 休↔Cｱ"))
        assertEquals("変更の確認: 今は変更しない 12/7 休↔Cｱ", OpLogText.previewDismissLine("12/7 休↔Cｱ"))
        assertEquals("相談に残す: 12/7 休↔Cｱ", OpLogText.consultKeepLine("12/7 休↔Cｱ"))
        assertEquals("改善手を適用: 12/7 休↔Cｱ（必須 2→1・合計 40→38） ・ 案を見てから 12秒", OpLogText.withSeenSeconds("改善手を適用: 12/7 休↔Cｱ（必須 2→1・合計 40→38）", 12_900))
    }

    private val base = 1_760_000_000_000L   // 2025-10-09 の昼前（UTC）。間隔だけに意味がある

    @Test fun hesitationSummaryCountsFromPrefixes() {
        val log = listOf(
            0L to "読込 11名/31日/10シフト",
            10_000L to "画面: 勤務表",
            20_000L to "手で変更: 甲 10/12 休→夜",
            25_000L to "違反チェック 必須=3 合計=40",
            30_000L to "元に戻す: セル編集",
            31_000L to "やり直し: セル編集",
            40_000L to "最適化 開始 (予算60s, 並列8, 方式AUTO) ・ 期間=…",
            100_000L to "最適化 完了 必須=1 合計=30 ・ 盤面指紋=…",
            150_000L to "下書きで置き換え（確認済み）: 必須 1件→4件",   // 完了から 50 秒＝結果の直後
            200_000L to "案を探す: 全体",
            203_000L to "案: 8件（3秒）",
            210_000L to "変更の確認: 開く 12/7 休↔Cｱ",
            222_000L to "改善手を適用: 12/7 休↔Cｱ（必須 4→3・合計 30→28） ・ 案を見てから 12秒",
            230_000L to "案を探す: 11/3のAｱ",
            238_000L to "案: なし（8秒）",
            240_000L to "変更の確認: 開く 11/4 休↔Bｲ",
            245_000L to "変更の確認: 今は変更しない 11/4 休↔Bｲ",
            250_000L to "相談に残す: 11/4 休↔Bｲ",
            260_000L to "人員不足の手伝い: 開く 10/12 Aｱ 候補 3人",
            270_000L to "人員不足の手伝い: 閉じる（適用なし）",
            280_000L to "作成前の確認: 開く",
            290_000L to "作成前の確認: 閉じる",
            300_000L to "配布前の確認: 開く",
            310_000L to "配布前の確認: 戻る",
            2_110_000L to "最適化 開始 (予算60s, 並列8, 方式AUTO) ・ 期間=…",   // 30 分の空白のあと
            2_170_000L to "最適化 完了 必須=0 合計=20 ・ 盤面指紋=…",
            2_300_000L to "下書きで置き換え（確認済み）: 必須 0件→2件",   // 完了から 130 秒＝直後ではない
            2_310_000L to "変更の確認: 開く 12/8 休↔Cｱ",
            2_330_000L to "改善手を適用: 12/8 休↔Cｱ（必須 2→1・合計 20→18） ・ 案を見てから 20秒",
            2_340_000L to "改善手を適用: 12/9 休↔Cｱ（必須 1→0・合計 18→16）",   // 確認を開いていない＝中央値に入れない
        )
        // 操作ログのリングは新しい順＝逆順で渡しても同じ答え。
        val rev = log.reversed()
        val lines = OpLogText.hesitationSummary(rev.map { it.second }, rev.map { base + it.first })
        assertEquals(6, lines.size)
        assertEquals("手で変更 1回 ・ 元に戻す 1回 ・ やり直し 1回", lines[0])
        assertEquals("案を探した 2回（案あり 1・なし 1） ・ 適用 3回 ・ 今は変更しない 1回 ・ 相談に残す 1回", lines[1])
        assertEquals("自動作成 2回 ・ 下書き 2回（結果の直後 1回）", lines[2])
        assertEquals("確認の画面を閉じた（適用なし）3回", lines[3])
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN)
        assertEquals("最長の空白 30分（${fmt.format(java.util.Date(base + 310_000L))}〜${fmt.format(java.util.Date(base + 2_110_000L))}）", lines[4])
        assertEquals("案を見てから適用までの中央値 16秒", lines[5])
        assertEquals(lines, OpLogText.hesitationSummary(log.map { it.second }, log.map { base + it.first }))
    }

    @Test fun hesitationSummaryWithoutDataSaysDash() {
        val lines = OpLogText.hesitationSummary(emptyList(), emptyList())
        assertEquals("手で変更 0回 ・ 元に戻す 0回 ・ やり直し 0回", lines[0])
        assertEquals("案を探した 0回（案あり 0・なし 0） ・ 適用 0回 ・ 今は変更しない 0回 ・ 相談に残す 0回", lines[1])
        assertEquals("自動作成 0回 ・ 下書き 0回（結果の直後 0回）", lines[2])
        assertEquals("確認の画面を閉じた（適用なし）0回", lines[3])
        assertEquals("最長の空白 —", lines[4])
        assertEquals("案を見てから適用までの中央値 —", lines[5])
        // 1 行だけでは空白が無い。
        assertEquals("最長の空白 —", OpLogText.hesitationSummary(listOf("画面: 勤務表"), listOf(base))[4])
    }

    @Test fun rightAfterResultIsSixtySecondsInclusive() {
        fun n(gapMs: Long): Int {
            val h = OpLogText.hesitation(listOf("最適化 完了 必須=1 合計=30", "下書きで置き換え（確認済み）: 必須 1件→4件"), listOf(base, base + gapMs))
            return h.draftsRightAfterResult
        }
        assertEquals(1, n(0L))
        assertEquals(1, n(60_000L))
        assertEquals(0, n(60_001L))
        // 完了が無い下書きは「結果の直後」に数えない（下書きの回数には入る）。
        val h = OpLogText.hesitation(listOf("下書きで置き換え（確認済み）: 必須 0件→4件"), listOf(base))
        assertEquals(1, h.drafts); assertEquals(0, h.draftsRightAfterResult)
        assertEquals(60_000L, OpLogText.RIGHT_AFTER_MS)
    }

    @Test fun summaryHeadAndNoNamesInIt() {
        assertEquals("==== 操作の要約（迷いの指標）====", OpLogText.HESITATION_HEAD)
        val lines = OpLogText.hesitationSummary(listOf("手で変更: 吉江雄貴 10/12 休→夜"), listOf(base))
        assertTrue(lines.none { it.contains("吉江") })
        assertFalse(lines[0].startsWith("手で変更:"))
    }
}
