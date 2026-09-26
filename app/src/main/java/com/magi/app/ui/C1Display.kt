package com.magi.app.ui

import com.magi.app.v6.Problem
import com.magi.app.v6.canDo
import com.magi.app.v6.mayPlace
import com.magi.app.v6.wishLocked

// ===== 期間の制約（c1）の表示専用の印 =====
// チェッカーの c1 の印（違反窓ランの先頭）は探索が読むので残し、画面には描かない。画面は「どの日を変えれば届くか」を出す。

/**
 * 職員 [staff] × 規則（[shift] を [day1] 日のなかに [day2] 日）の不足の 1 区間。[from]〜[to] は続けて不足した窓の和。
 * [marks] は不足窓の中で、いま [shift] でなく [shift] に変えられる日（変えられる日が 1 つも無い窓の日は含めない）。
 * [stuck] は変えられる日が 1 つも無い不足窓があること。
 */
data class C1Shortage(
    val staff: Int, val shift: Int, val day1: Int, val day2: Int,
    val from: Int, val to: Int, val windows: Int, val marks: List<Int>, val stuck: Boolean,
) {
    /** 行の下に帯を引くか（窓が重なって続く＝1 窓より長い不足）。 */
    val band: Boolean get() = windows > 1
}

/** セル (i,d) を [k] に変えられるか。最適化器と同じ基準（希望固定なら希望どおりだけ、それ以外は mayPlace）。 */
internal fun c1Changeable(p: Problem, i: Int, d: Int, k: Int): Boolean =
    if (p.wishLocked(i, d)) p.wish[i][d] == k else p.mayPlace(i, k)

/** 盤面 [s] の期間の制約の不足区間。窓の数え方はチェッカー（担当不可の職員は対象外）と同じ。 */
internal fun c1Shortages(p: Problem, s: Array<IntArray>): List<C1Shortage> {
    val out = ArrayList<C1Shortage>()
    for (c in p.cons1) {
        if (c.day1 <= 0 || c.day1 > p.T) continue
        for (i in 0 until p.S) {
            if (!p.canDo(i, c.shiftIdx)) continue
            val row = s[i]
            val cand = BooleanArray(p.T) { d -> row[d] != c.shiftIdx && c1Changeable(p, i, d, c.shiftIdx) }
            var runStart = -1; var n = 0; var stuck = false
            val marks = sortedSetOf<Int>()
            fun close() {
                if (runStart >= 0) out += C1Shortage(i, c.shiftIdx, c.day1, c.day2, runStart, runStart + n - 1 + c.day1 - 1, n, marks.toList(), stuck)
                runStart = -1; n = 0; stuck = false; marks.clear()
            }
            for (j in 0..p.T - c.day1) {
                val z = (j until j + c.day1).count { row[it] == c.shiftIdx }
                if (z >= c.day2) { close(); continue }
                if (runStart < 0) runStart = j
                n++
                val inWin = (j until j + c.day1).filter { cand[it] }
                if (inWin.isEmpty()) stuck = true else marks.addAll(inWin)
            }
            close()
        }
    }
    return out
}

/** 勤務表だけでは届かないときの 1 文（セルシート・職員の内訳で共有）。 */
internal const val C1_STUCK_TEXT = "希望や担当の都合で、勤務表だけでは期間の約束を満たせません。"

/** セル (i,j) に掛かる不足区間の説明文（無ければ null）。[sym] はシフト記号、[day] は日の表記。 */
internal fun c1CellText(shortages: List<C1Shortage>, s: Array<IntArray>, i: Int, j: Int, sym: (Int) -> String, day: (Int) -> String): String? {
    val sh = shortages.firstOrNull { it.staff == i && j in it.from..it.to } ?: return null
    val k = sym(sh.shift)
    val head = "期間の約束: ${sh.day1}日のなかに「$k」が${sh.day2}日必要です。"
    val body = if (sh.marks.isEmpty()) C1_STUCK_TEXT
    else "いま足りない期間（${day(sh.from)}〜${day(sh.to)}）があり、印の日を${k}にすると届く見込みです。" +
        (if (sh.stuck) C1_STUCK_TEXT else "")
    val held = if (s.getOrNull(i)?.getOrNull(j) == sh.shift) "（この日の${k}はすでに数に入っています）" else ""
    return head + body + held
}
