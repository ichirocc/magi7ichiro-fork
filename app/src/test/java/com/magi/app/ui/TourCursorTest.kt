package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 巡回の位置が一覧の作り直し（違反の解消・発生）に追従する（[tourCursor]）。 */
class TourCursorTest {
    private fun item(staff: Int, day: Int, fam: String = "pref") = TourItem(fam, staff, listOf(day), "$fam $staff/$day")
    private val a = item(0, 1); private val b = item(1, 2); private val c = item(2, 3); private val d = item(3, 4)

    @Test fun unchangedListKeepsTheIndexAndWraps() {
        val l = listOf(a, b, c)
        assertEquals(TourCursor(1, 2, 0), tourCursor(l, 1, l))
        assertEquals(TourCursor(2, 0, 1), tourCursor(l, 2, l))
        assertEquals("1 件だけなら前・次は無い", TourCursor(0, null, null), tourCursor(listOf(a), 0, listOf(a)))
    }

    @Test fun fixingTheCurrentItemPointsNextAtItsSuccessorNotOnePastIt() {
        // b を直した→一覧は a, c, d。据え置きの添字 1 のままだと「次」は d（c を飛ばす）。
        val now = listOf(a, c, d)
        val t = tourCursor(listOf(a, b, c, d), 1, now)
        assertEquals(TourCursor(null, 1, 0), t)
        assertEquals(c, now[t.next!!]); assertEquals(a, now[t.prev!!])
    }

    @Test fun anEarlierItemVanishingFollowsTheCurrentItem() {
        // 今 c（添字 2）。前の a が消えた→c は添字 1。据え置きの 2 だと前・次とも c 自身を指して無効になっていた。
        assertEquals(TourCursor(1, 0, 0), tourCursor(listOf(a, b, c), 2, listOf(b, c)))
        // 後ろが入れ替わっても今の項目が残れば位置はそのまま。
        assertEquals(TourCursor(1, 2, 0), tourCursor(listOf(a, b, c), 1, listOf(a, b, d)))
    }

    @Test fun vanishedItemWithNewArrivalsAndEdges() {
        // 先頭が消えて前に生き残りが無い→次は残った直後の項目。
        assertEquals(TourCursor(null, 0, 1), tourCursor(listOf(a, b, c), 0, listOf(b, c)))
        // 末尾が消えた→次は先頭へ回り、前は直前。
        assertEquals(TourCursor(null, 0, 1), tourCursor(listOf(a, b, c), 2, listOf(a, b)))
        // 消えた b の位置に新しい違反 d が入った（並びは a, d, c）→次は d。
        assertEquals(TourCursor(null, 1, 0), tourCursor(listOf(a, b, c), 1, listOf(a, d, c)))
        // 残りが 1 件なら前・次ともその 1 件（今のセルは消えた項目なので押せる）。
        assertEquals(TourCursor(null, 0, 0), tourCursor(listOf(a, b), 1, listOf(a)))
        assertEquals("全部直った", TourCursor(null, null, null), tourCursor(listOf(a), 0, emptyList()))
    }

    @Test fun aCellInTwoViolationsStillAdvancesByPosition() {
        // 福澤 10/1 が禁止の並びと c3w の両方に属する: 次の項目は同じセル。セルの一致で判定すると［次 ▶］が押せなかった。
        val run = TourItem("c3n", 0, listOf(0, 1, 2), "禁止の並び"); val wish = TourItem("c3w", 0, listOf(0, 1), "希望の前日禁止")
        val l = listOf(run, wish, b)
        val t = tourCursor(l, 0, l)
        assertEquals(TourCursor(0, 1, 2), t)
        assertEquals(run.cell, l[t.next!!].cell)
        assertEquals("必須違反 2 / 3 ・ 希望の前日禁止", tourHeading(l, tourCursor(l, t.next!!, l).at!!))
    }

    @Test fun tourButtonsMoveByItemNotByCell() {
        val src = listOf("app/src/main/java/com/magi/app/ui", "src/main/java/com/magi/app/ui").map(::File).first { it.isDirectory }
        val sheet = File(src, "CellEditSheet.kt").readText()
        assertEquals("巡回の前・次はセルでなく項目へ移る操作を受け取る", 2, Regex("""tour(Next|Prev): \(\(\) -> Unit\)\? = null""").findAll(sheet).count())
        assertEquals("セルが同じというだけで巡回の前・次を無効にしない", false, Regex("""tour(Next|Prev) != cell""").containsMatchIn(sheet))
        val app = File(src, "MagiApp.kt").readText()
        assertEquals(true, app.contains("tourCursor(tourBase, tourAt, tourItems)"))
    }

    @Test fun matchesByFamilyStaffAndDaysNotByHeading() {
        assertEquals(TourCursor(0, 1, 1), tourCursor(listOf(a, b), 0, listOf(a.copy(heading = "見出しだけ変わった"), b)))
        // 同じセルでも族が違えば別の違反。
        assertEquals(TourCursor(null, 1, 0), tourCursor(listOf(a, b), 0, listOf(item(0, 1, "c3w"), b)))
    }
}
