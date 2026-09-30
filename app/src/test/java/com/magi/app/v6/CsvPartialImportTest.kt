package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 引用符が閉じていない勤務表CSVは、読めたところまでを取り込むか確認してから適用する（利用者決定 B）。 */
class CsvPartialImportTest {

    private val json = """
        {
          "startDate": "2026-07-01", "endDate": "2026-07-03",
          "shifts": [{"name":"日勤","kigou":"日","need1":"0","need2":""},{"name":"夜勤","kigou":"夜","need1":"0","need2":""},{"name":"休み","kigou":"休","need1":"0","need2":""}],
          "groups": [{"name":"A","kigou":"A"}],
          "staff": [{"name":"山田","groupIdx":0,"skillIdx":0},{"name":"鈴木","groupIdx":0,"skillIdx":0},{"name":"佐藤","groupIdx":0,"skillIdx":0}],
          "schedule": [[2,2,2],[2,2,2],[2,2,2]]
        }
    """.trimIndent()

    private fun base() = arrayOf(intArrayOf(2, 2, 2), intArrayOf(2, 2, 2), intArrayOf(2, 2, 2))

    private fun breaks(s: String) = Regex("\r\n|\r|\n").findAll(s).count()

    @Test fun wellFormedCsvIsNotFlaggedAndReadsEveryRecord() {
        val r = CsvPartialImport.readable("スタッフ \\ 日付,1,2,3\n山田,日,夜,休\n鈴木,休,休,日\n")
        assertFalse(r.unclosedQuote)
        assertEquals(3, r.records)
        assertEquals(3, r.endLine)
    }

    @Test fun lastRecordWithoutTrailingNewlineCountsItsOwnLine() {
        val r = CsvPartialImport.readable("a,b\nc,d")
        assertFalse(r.unclosedQuote)
        assertEquals(2, r.records)
        assertEquals(2, r.endLine)
    }

    @Test fun endLineIsTheLastLineOfTheLastTerminatedRecord() {
        val text = "スタッフ \\ 日付,1,2,3\n山田,日,夜,休\n鈴木,\"日,休,休\n佐藤,休,休,日\n"
        val r = CsvPartialImport.readable(text)
        assertTrue(r.unclosedQuote)
        assertEquals(2, r.records)
        assertEquals(2, r.endLine)
        assertEquals("スタッフ \\ 日付,1,2,3\n山田,日,夜,休\n", r.prefixText)
    }

    @Test fun multiLineQuotedFieldInTheReadablePartCountsEveryPhysicalLine() {
        // 2 件目の氏名セルが改行を含む（2〜3 行目）。そのあと 3 件目（4 行目）で引用符が開いたまま終わる。
        val text = "スタッフ \\ 日付,1,2,3\n\"山田\n（日勤帯）\",日,夜,休\n鈴木,\"日,休\n佐藤,休,休,日\n"
        val r = CsvPartialImport.readable(text)
        assertTrue(r.unclosedQuote)
        assertEquals(2, r.records)
        assertEquals("複数行のセルは最後の行で数える", 3, r.endLine)
        assertEquals(text.substring(0, text.indexOf("鈴木")), r.prefixText)
    }

    @Test fun newlinesInsideTheSwallowedRecordDoNotMoveTheEnd() {
        val text = "山田,日,夜,休\n鈴木,\"日\n\n\n休\n休\n"
        val r = CsvPartialImport.readable(text)
        assertTrue(r.unclosedQuote)
        assertEquals(1, r.records)
        assertEquals(1, r.endLine)
    }

    @Test fun crlfAndLoneCrEachCountAsOneLineBreakOutsideAndInsideQuotes() {
        val crlf = "a,b\r\n\"x\r\ny\",z\r\nq,\"r"
        val r1 = CsvPartialImport.readable(crlf)
        assertTrue(r1.unclosedQuote)
        assertEquals(2, r1.records)
        assertEquals(3, r1.endLine)
        val cr = "a,b\r\"x\ry\",z\rq,\"r"
        val r2 = CsvPartialImport.readable(cr)
        assertTrue(r2.unclosedQuote)
        assertEquals(2, r2.records)
        assertEquals(3, r2.endLine)
    }

    @Test fun doubledQuoteInsideQuotedFieldDoesNotCloseIt() {
        val ok = CsvPartialImport.readable("a,\"x\"\"y\"\nb,c\n")
        assertFalse(ok.unclosedQuote)
        val ng = CsvPartialImport.readable("a,b\nc,\"x\"\"y\nd,e\n")
        assertTrue(ng.unclosedQuote)
        assertEquals(1, ng.records)
        assertEquals(1, ng.endLine)
    }

    @Test fun quoteOpeningInTheFirstRecordReadsNothing() {
        val r = CsvPartialImport.readable("\"山田,日,夜,休\n鈴木,日,日,日\n")
        assertTrue(r.unclosedQuote)
        assertEquals(0, r.records)
        assertEquals(0, r.endLine)
        assertEquals("", r.prefixText)
    }

    @Test fun bomIsSkippedAndDoesNotShiftTheLineNumbers() {
        val r = CsvPartialImport.readable("\uFEFFa,b\nc,\"d\ne,f\n")
        assertTrue(r.unclosedQuote)
        assertEquals(1, r.records)
        assertEquals(1, r.endLine)
        assertEquals("\uFEFFa,b\n", r.prefixText)
    }

    @Test fun prefixTextParsesToExactlyTheReadableRecordsAndLineCountsAgree() {
        val rnd = Random(20260930)
        val alphabet = charArrayOf('a', 'b', ',', '"', '"', '\n', '\r', ' ')
        var unclosedSeen = 0
        repeat(4000) {
            val text = String(CharArray(rnd.nextInt(40)) { alphabet[rnd.nextInt(alphabet.size)] })
            val r = CsvPartialImport.readable(text)
            val full = parseCsvFull(text)
            assertEquals("「$text」の旗", full.unclosedQuote, r.unclosedQuote)
            if (r.unclosedQuote) {
                unclosedSeen++
                val again = parseCsvFull(r.prefixText)
                assertFalse("「$text」の読めた部分は閉じている", again.unclosedQuote)
                assertEquals("「$text」の読めた件数", r.records, again.rows.size)
                assertEquals("「$text」の読めた行", full.rows.take(r.records), again.rows)
                assertEquals("「$text」の行番号", breaks(r.prefixText), r.endLine)
                assertTrue("「$text」は前置きで始まる", text.startsWith(r.prefixText))
            } else {
                assertEquals("「$text」の件数", full.rows.size, r.records)
                assertEquals("閉じていれば全文", text, r.prefixText)
                if (r.records == 0) assertEquals(0, r.endLine)
                else if (text.endsWith("\n") || text.endsWith("\r")) assertEquals("「$text」の最終行", breaks(text), r.endLine)
                else {
                    // 改行より後ろが引用符だけなら、行になるかは従来の解析次第（範囲だけ見る）。
                    val tail = text.substring(maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r')) + 1)
                    if (tail.any { it != '"' }) assertEquals("「$text」の最終行", breaks(text) + 1, r.endLine)
                    else assertTrue("「$text」の最終行", r.endLine in breaks(text)..breaks(text) + 1)
                }
            }
        }
        assertTrue("未閉の例が十分に含まれる", unclosedSeen > 300)
    }

    @Test fun judgeLeavesWellFormedCsvToTheUsualPath() {
        val v = CsvPartialImport.judge("山田,日,夜,休\n鈴木,休,休,日\n", StateParser.parse(json), base())
        assertTrue(v is CsvPartialImport.Verdict.WellFormed)
    }

    @Test fun judgeAsksWithTheLineAndTheStaffCountOfTheReadablePart() {
        val text = "スタッフ \\ 日付,1,2,3\n山田,日,夜,休\n鈴木,休,休,日\n佐藤,\"日,日,日\n"
        val v = CsvPartialImport.judge(text, StateParser.parse(json), base())
        assertTrue(v is CsvPartialImport.Verdict.Ask)
        v as CsvPartialImport.Verdict.Ask
        assertEquals(3, v.endLine)
        assertEquals(2, v.matched)
        assertFalse("読めた部分は閉じている", v.result.unclosedQuote)
        assertEquals(
            "CSV の 3行目までは読めました（2 名分）。その先は引用符が閉じていないため読めません。この部分だけ取り込みますか？",
            v.prompt,
        )
    }

    @Test fun judgeNeverAppliesTheRecordThatSwallowsTheRest() {
        // 鈴木の行は 1 日目の「休」のあとで引用符が開く＝その行は「読めた」に入らない（手前のセルも反映しない）。
        val text = "山田,日,夜,休\n鈴木,日,\"夜,休\n佐藤,休,休,日\n"
        val v = CsvPartialImport.judge(text, StateParser.parse(json), base()) as CsvPartialImport.Verdict.Ask
        assertEquals(1, v.endLine)
        assertEquals(1, v.matched)
        assertArrayEquals(intArrayOf(0, 1, 2), v.result.schedule[0])
        assertArrayEquals("鈴木は元のまま", intArrayOf(2, 2, 2), v.result.schedule[1])
        assertArrayEquals("佐藤は元のまま", intArrayOf(2, 2, 2), v.result.schedule[2])
    }

    @Test fun judgeKeepsSkippingAmbiguousNamesInTheReadablePart() {
        val dup = StateParser.parse(json.replace("\"鈴木\"", "\"山田\""))
        val v = CsvPartialImport.judge("山田,日,日,日\n佐藤,夜,夜,夜\n鈴木,\"日\n", dup, base()) as CsvPartialImport.Verdict.Ask
        assertEquals(1, v.matched)
        assertEquals(listOf("山田"), v.result.ambiguousNames)
        assertArrayEquals(intArrayOf(1, 1, 1), v.result.schedule[2])
    }

    @Test fun judgeReportsNothingReadableWhenTheQuoteOpensBeforeAnyRow() {
        val v = CsvPartialImport.judge("\"山田,日,夜,休\n鈴木,日,日,日\n", StateParser.parse(json), base())
        assertTrue(v is CsvPartialImport.Verdict.NothingReadable)
    }

    @Test fun judgeReportsNothingReadableWhenNoReadableRowMatchesAStaffName() {
        val v = CsvPartialImport.judge("田中,日,夜,休\n高橋,休,休,日\n山田,\"日,日,日\n", StateParser.parse(json), base())
        assertTrue(v is CsvPartialImport.Verdict.NothingReadable)
    }

    @Test fun wordingMatchesTheDecidedTexts() {
        assertEquals("取り込める行がありませんでした（引用符が閉じていません）", CsvPartialImport.NOTHING_READABLE)
        assertEquals("盤面が変わったため取込をやめました。もう一度取り込んでください", CsvPartialImport.STALE)
        assertEquals("取込をやめました", CsvPartialImport.CANCELLED)
        assertEquals("この部分だけ取り込む", CsvPartialImport.CONFIRM_LABEL)
        assertEquals("やめる", CsvPartialImport.CANCEL_LABEL)
    }

    private fun ask(): CsvPartialImport.Verdict.Ask =
        CsvPartialImport.judge("山田,日,夜,休\n鈴木,\"日\n", StateParser.parse(json), base()) as CsvPartialImport.Verdict.Ask

    @Test fun resolveAppliesOnlyWhileStateAndBoardAreUnchanged() {
        val pending = CsvPartialImport.Pending(ask(), stateKey = 11L, boardKey = 22L)
        val same = CsvPartialImport.resolve(pending, 11L, 22L)
        assertTrue(same is CsvPartialImport.Resolution.Apply)
        assertEquals(pending, (same as CsvPartialImport.Resolution.Apply).pending)
        assertTrue(CsvPartialImport.resolve(pending, 12L, 22L) is CsvPartialImport.Resolution.Stale)
        assertTrue(CsvPartialImport.resolve(pending, 11L, 23L) is CsvPartialImport.Resolution.Stale)
    }

    @Test fun resolveWithoutPendingDoesNothing() {
        assertTrue(CsvPartialImport.resolve(null, 1L, 2L) is CsvPartialImport.Resolution.NoPending)
    }
}
