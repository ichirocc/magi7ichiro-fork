package com.magi.app.ui

import com.magi.app.v6.Problem
import com.magi.app.v6.canDo
import com.magi.app.v6.mayPlace
import com.magi.app.v6.lockTo
import com.magi.app.v6.wishLocked

// ===== 期間の制約（c1）の表示専用の印 =====
// チェッカーの c1 の印（違反窓ランの先頭）は探索が読むので残し、画面には描かない。画面は「どの日を変えれば届くか」を出す。

/**
 * 職員 [staff] × 規則（[shift] を [day1] 日のなかに [day2] 日）の不足の 1 区間。[from]〜[to] は続けて不足した窓の和。
 * [marks] は不足窓の中で、いま [shift] でなく [shift] に変えられる日（変えられる日が 1 つも無い窓の日は含めない）。
 * [stuck] は変えられる日が足りない日数より少ない不足窓があること（印をすべて変えても届かない）。
 * [minChanges] は届かせるのに変える日数の最小（右端の日を優先する貪欲法＝同じ長さの窓では最小）。stuck のときは 0（届かない）。
 */
data class C1Shortage(
    val staff: Int, val shift: Int, val day1: Int, val day2: Int,
    val from: Int, val to: Int, val windows: Int, val marks: List<Int>, val stuck: Boolean,
    val minChanges: Int = 0,
) {
    /** 行の下に帯を引くか（窓が重なって続く＝1 窓より長い不足）。 */
    val band: Boolean get() = windows > 1
}

/** セル (i,d) を [k] に変えられるか。最適化器と同じ基準（希望固定なら希望どおりだけ、それ以外は mayPlace）。 */
internal fun c1Changeable(p: Problem, i: Int, d: Int, k: Int): Boolean =
    if (p.wishLocked(i, d)) p.lockTo(i, d) == k else p.mayPlace(i, k)

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
                if (runStart >= 0) {
                    var minChanges = 0
                    if (!stuck) {
                        val chosen = BooleanArray(p.T)
                        for (w in runStart until runStart + n) {
                            var need = c.day2 - (w until w + c.day1).count { row[it] == c.shiftIdx || chosen[it] }
                            for (d in w + c.day1 - 1 downTo w) {
                                if (need <= 0) break
                                if (cand[d] && !chosen[d]) { chosen[d] = true; need--; minChanges++ }
                            }
                        }
                    }
                    out += C1Shortage(i, c.shiftIdx, c.day1, c.day2, runStart, runStart + n - 1 + c.day1 - 1, n, marks.toList(), stuck, minChanges)
                }
                runStart = -1; n = 0; stuck = false; marks.clear()
            }
            for (j in 0..p.T - c.day1) {
                val z = (j until j + c.day1).count { row[it] == c.shiftIdx }
                if (z >= c.day2) { close(); continue }
                if (runStart < 0) runStart = j
                n++
                val inWin = (j until j + c.day1).filter { cand[it] }
                if (inWin.size < c.day2 - z) stuck = true
                marks.addAll(inWin)
            }
            close()
        }
    }
    return out
}

/** 勤務表だけでは届かないときの 1 文（セルシート・職員の内訳で共有）。 */
internal const val C1_STUCK_TEXT = "希望・手動固定・個人の上限0（入れない指定）の都合で、勤務表だけでは期間の制約を満たせません。"

/** セル (i,j) に掛かる不足区間の説明文（無ければ null）。[sym] はシフト記号、[day] は日の表記。 */
internal fun c1CellText(shortages: List<C1Shortage>, s: Array<IntArray>, i: Int, j: Int, sym: (Int) -> String, day: (Int) -> String): String? {
    val sh = shortages.firstOrNull { it.staff == i && j in it.from..it.to } ?: return null
    val k = sym(sh.shift)
    val head = "期間の制約: ${sh.day1}日のなかに「$k」が${sh.day2}日必要です。"
    val body = if (sh.marks.isEmpty()) C1_STUCK_TEXT
    else if (sh.stuck) "いま足りない期間（${day(sh.from)}〜${day(sh.to)}）があり、印の日を${k}にすると不足は減ります。$C1_STUCK_TEXT"
    else {
        val how = if (sh.minChanges >= sh.marks.size) "印の日をすべて${k}に変えると" else "印の日をうまく選べば、いちばん少なくて${sh.minChanges}日を${k}にすると"
        "いま足りない期間（${day(sh.from)}〜${day(sh.to)}）があり、${how}この制約の日数に届きます（ほかの制約への影響は見ていません）。"
    }
    val held = if (s.getOrNull(i)?.getOrNull(j) == sh.shift) "（この日の${k}はすでに数に入っています）" else ""
    return head + body + held
}
