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

/** 変更を当てるボタン（違反セルの半自動修正仕様 §5: 押すと検証してから全変更を反映する）。 */
internal const val APPLY_CHANGE_BUTTON = "この変更を適用"
/** 当てずに閉じるボタン（今の勤務表のまま）。 */
internal const val KEEP_CURRENT_BUTTON = "今は変更しない"
/** 誰が・いつ確認するか（仕様 §4）。担当者名や期限のデータは無い＝推測せず「未設定」と書く。 */
internal const val CHANGE_DUTY_NOTE = "対応する人: 勤務表の作成担当者（担当者名は未設定）／対応する時期: 変更を適用する前（期限は未設定）"

/** [shiftNames]＝登録された勤務名（記号と違えば「記号（勤務名）」で併記）、[wishes]＝希望 "i,j"→シフト（あれば行ごとに希望どおりか添える）。 */
internal fun chainFixPreview(s: FixSuggestion, snapshot: Array<IntArray>, staffNames: List<String>, shiftSymbols: List<String>, startDate: String,
                             shiftNames: List<String> = emptyList(), wishes: Map<String, Int> = emptyMap()): ChainFixPreview {
    fun name(i: Int) = staffNames.getOrNull(i) ?: "職員${i + 1}"
    fun sym(k: Int) = shiftSymbols.getOrNull(k) ?: "$k"
    fun shift(k: Int) = sym(k) + (shiftNames.getOrNull(k)?.takeIf { it.isNotBlank() && it != sym(k) }?.let { "（$it）" } ?: "")
    val changes = s.ops.map { op ->
        val before = snapshot.getOrNull(op.staff)?.getOrNull(op.day)
        // 「希望との不一致なし」は本人の了承ではない＝希望があるときだけ、希望どおりか違うかを事実として添える（仕様 §4）。
        val wish = wishes["${op.staff},${op.day}"]?.takeIf { it >= 0 }?.let { w -> if (w == op.toShift) "・希望どおり" else "・希望（${sym(w)}）と異なる勤務になります" } ?: ""
        "${name(op.staff)} ${DayText.short(startDate, op.day)} ${before?.let { shift(it) } ?: "?"} → ${shift(op.toShift)}$wish"
    }
    val (hard, caution) = fixImpactLines(s)
    // 人数・マス数は本文 1 行目（提案のラベル）が持つ。題に入れると「（6 / 人・11 セル）」のように途中で折り返した。
    return ChainFixPreview(s, "複数人の入れ替え", changes, hard, caution)
}
