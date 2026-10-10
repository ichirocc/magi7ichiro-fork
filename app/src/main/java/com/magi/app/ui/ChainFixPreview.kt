package com.magi.app.ui

import com.magi.app.v6.FixSuggestion

/** [3.644.0/UX-03] 複数人の入替（玉突き）を当てる前の一覧。だれの・どの日の・何を何に変えるかと、必須違反の増減（`fixImpactLines`）。 */
data class ChainFixPreview(
    val suggestion: FixSuggestion,
    val title: String,
    val changes: List<String>,
    val hardLine: String,
    val caution: String?,
    /** 探した枠（日付・シフト記号・見出し）。相談に積んだあと、今の勤務表で案を探し直すために持つ。 */
    val target: ChainTarget? = null,
)

internal fun chainFixPreview(s: FixSuggestion, snapshot: Array<IntArray>, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): ChainFixPreview {
    fun name(i: Int) = staffNames.getOrNull(i) ?: "職員${i + 1}"
    fun sym(k: Int) = shiftSymbols.getOrNull(k) ?: "$k"
    val changes = s.ops.map { op ->
        val before = snapshot.getOrNull(op.staff)?.getOrNull(op.day)
        "${name(op.staff)} ${DayText.short(startDate, op.day)} ${before?.let { sym(it) } ?: "?"} → ${sym(op.toShift)}"
    }
    val (hard, caution) = fixImpactLines(s)
    // 人数・マス数は本文 1 行目（提案のラベル）が持つ。題に入れると「（6 / 人・11 セル）」のように途中で折り返した。
    return ChainFixPreview(s, "複数人の入れ替え", changes, hard, caution)
}
