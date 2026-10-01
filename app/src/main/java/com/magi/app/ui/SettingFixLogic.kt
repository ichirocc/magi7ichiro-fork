package com.magi.app.ui

import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.SettingIssue
import com.magi.app.v6.c3SeqKey

/** 設定の見直しカードのワンタップ修正の純粋部分（状態 → 新しい状態 / 何も変えないなら null）。ViewModel は適用とログだけ持つ。 */
object SettingFixLogic {
    fun apply(s: MagiState, issue: SettingIssue): MagiState? {
        return when (issue.action) {
            SettingFixAction.REMOVE_WISH -> {
                val key = issue.wishKey ?: return null
                if (!s.wishes.containsKey(key)) return null
                s.copy(wishes = s.wishes - key)
            }
            SettingFixAction.DELETE_DUP_SEQ -> {
                val fam = issue.seqFamily ?: return null
                val key = issue.seqKey ?: return null
                // 診断と同じ鍵（最初の空白まで・詰めない）。一致する最初の1行だけ消し、無ければ何も変えない（null）。
                fun delOne(rows: List<C3Row>): List<C3Row>? {
                    val i = rows.indexOfFirst { c3SeqKey(it.pattern) == key }
                    return if (i < 0) null else rows.filterIndexed { idx, _ -> idx != i }
                }
                when (fam) {
                    "c3" -> delOne(s.cons3)?.let { s.copy(cons3 = it) }
                    "c3n" -> delOne(s.cons3n)?.let { s.copy(cons3n = it) }
                    "c3m" -> delOne(s.cons3m)?.let { s.copy(cons3m = it) }
                    "c3mn" -> delOne(s.cons3mn)?.let { s.copy(cons3mn = it) }
                    else -> return null
                }
            }
            SettingFixAction.ZERO_RANGE_LO, SettingFixAction.CLAMP_RANGE_LO -> {
                val key = issue.rangeKey ?: return null
                val cur = s.staffRange[key] ?: Range("", "")
                s.copy(staffRange = s.staffRange + (key to Range(issue.newLo ?: cur.lo, cur.hi)))
            }
            SettingFixAction.CLAMP_GROUP_RANGE_LO -> {
                // 行は List なので index でなく**内容一致**で指す（DELETE_DUP_SEQ と同じ理由＝診断から
                //   タップまでに並びが変わっても別の行を壊さない）。同じ内容が複数あるときは先頭1件だけ直す。
                val row = issue.groupRangeRow ?: return null
                val lo = issue.newLo ?: return null
                fun clampOne(rows: List<C41Row>): List<C41Row> {
                    val i = rows.indexOf(row)
                    if (i < 0) return rows
                    return rows.toMutableList().also { it[i] = row.copy(l = lo) }
                }
                when (issue.groupRangeFamily) {
                    "c41" -> s.copy(cons41 = clampOne(s.cons41))
                    "c41s" -> s.copy(cons41s = clampOne(s.cons41s))
                    else -> return null
                }
            }
            SettingFixAction.CAP_DEMAND -> {
                val k = issue.demandShiftIdx ?: return null
                val cap = issue.demandCap ?: return null
                val sh = s.shifts.getOrNull(k) ?: return null
                val j = issue.demandDayIdx
                if (j != null) {
                    // 日付つきの不足はその日の例外だけを書く（標準の必要数を下げると例外の無い他の日まで動く）。
                    //   P2 は実際に使っていて（use2Patterns）その日の実効値が上限を超えるときだけ。
                    val key = "$k,$j"
                    fun eff(map: Map<String, String>, std: String) = map[key]?.trim()?.toIntOrNull() ?: std.trim().toIntOrNull()
                    val e1 = eff(s.needDay1, sh.need1)
                    val e2 = eff(s.needDay2, sh.need2)
                    val w1 = e1 != null && e1 > cap
                    val w2 = s.use2Patterns && e2 != null && e2 > cap
                    if (!w1 && !w2) return null
                    s.copy(
                        needDay1 = if (w1) s.needDay1 + (key to cap.toString()) else s.needDay1,
                        needDay2 = if (w2) s.needDay2 + (key to cap.toString()) else s.needDay2,
                    )
                } else {
                    val n1 = sh.need1.trim().toIntOrNull()
                    val n2 = sh.need2.trim().toIntOrNull()
                    val newN1 = if (n1 != null && n1 > cap) cap.toString() else sh.need1
                    val newN2 = if (n2 != null && n2 > cap) cap.toString() else sh.need2
                    if (newN1 == sh.need1 && newN2 == sh.need2) return null
                    val list = s.shifts.toMutableList()
                    list[k] = sh.copy(need1 = newN1, need2 = newN2)
                    s.copy(shifts = list)
                }
            }
            SettingFixAction.NONE -> null
        }
    }
}
