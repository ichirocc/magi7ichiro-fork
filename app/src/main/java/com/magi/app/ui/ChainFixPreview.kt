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
    /** 今回の目的（案の一覧から来たとき。例「古泉さんのA4不足を減らす」）。null＝目的の行を出さない。 */
    val purpose: String? = null,
    /** 対象の数の変化（例「A4：7回 → 9回」「適用後も、下限まで6回不足」）。 */
    val effect: List<String> = emptyList(),
    /** 案の一覧から開いた＝閉じるボタンは「別案を見る」と「今は変更しない」。 */
    val fromList: Boolean = false,
    /** 変わるセル（職員ごとに日付・変更前・変更後の表にする）。 */
    val rows: List<ChangeRow> = emptyList(),
    /** 職員ごとの回数の変化（2 か所以上を変える理由の事実）。 */
    val countNotes: List<String> = emptyList(),
    /** 案の一覧のどの依頼から来たか（適用後に同じ依頼の画面へ結果を返す）。 */
    val focusKey: String? = null,
    /** 見ている問題（適用後の結果の行で、解消して印が消えたあとも前後を言う）。 */
    val fixTarget: FixTarget? = null,
    /** 別の場所に新しく生まれる必須違反（正式な検査の前後。null＝評価していない）。 */
    val newHard: List<String>? = null,
)

/** 変わるセル 1 つ。[wish]＝希望があるときの注記（希望どおり／希望と異なる）。[staffKey]＝職員 ID（無ければ並び順）＝同名でも別の人として束ねる。
 *  [staff] は表示名（同名の職員がいればグループ記号を添える）。 */
data class ChangeRow(val staff: String, val date: String, val before: String, val after: String, val wish: String = "", val impact: String = "", val staffKey: Int = -1)

/** 変更を当てるボタン（違反セルの半自動修正仕様 §5: 押すと検証してから全変更を反映する）。 */
internal const val APPLY_CHANGE_BUTTON = "この変更を適用"
/** [3.687.0/R09] 確認の画面の門の説明（[FixApplyGate] と同じ規則: 必須を増やさない・別の場所に必須を生まない・希望の固定を崩さない。要調整だけの改善は通る）。 */
internal const val PREVIEW_GATE_NOTE = "適用の直前に最新の勤務表でもう一度検査し、必須違反を増やす・別の職員や日に必須違反を生む・希望の固定を崩す変更は適用しません。適用したあとは「元に戻す」で取り消せます。"
/** 当てずに閉じるボタン（今の勤務表のまま）。 */
internal const val KEEP_CURRENT_BUTTON = "今は変更しない"
/** 変更を当てる前の確認の見出し（1 手でも入替でも同じ）。 */
internal const val PREVIEW_TITLE = "この変更を適用しますか？"

/** [shiftNames]＝登録された勤務名（記号と違えば「記号（勤務名）」で併記）、[wishes]＝希望 "i,j"→シフト（あれば行ごとに希望どおりか添える）。 */
internal fun chainFixPreview(s: FixSuggestion, snapshot: Array<IntArray>, staffNames: List<String>, shiftSymbols: List<String>, startDate: String,
                             shiftNames: List<String> = emptyList(), wishes: Map<String, Int> = emptyMap(),
                             staffIds: List<Int> = emptyList(), staffGroups: List<String> = emptyList()): ChainFixPreview {
    val dupNames = staffNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    fun name(i: Int): String {
        val n = staffNames.getOrNull(i) ?: return "職員${i + 1}"
        if (n !in dupNames) return n
        return n + (staffGroups.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "（${i + 1}番目）")
    }
    fun key(i: Int) = staffIds.getOrNull(i)?.takeIf { it > 0 } ?: i
    fun sym(k: Int) = shiftSymbols.getOrNull(k) ?: "$k"
    fun shift(k: Int) = sym(k) + (shiftNames.getOrNull(k)?.takeIf { it.isNotBlank() && it != sym(k) }?.let { "（$it）" } ?: "")
    val board = snapshot.map { it.toList() }
    val after = boardAfter(board, s.ops)
    val rows = s.ops.map { op ->
        val before = snapshot.getOrNull(op.staff)?.getOrNull(op.day)
        // 「希望との不一致なし」は本人の了承ではない＝希望があるときだけ、希望どおりか違うかを事実として添える（仕様 §4）。
        val wish = wishes["${op.staff},${op.day}"]?.takeIf { it >= 0 }?.let { w -> if (w == op.toShift) "希望どおり" else "希望（${sym(w)}）と異なる勤務になります" } ?: ""
        // その日の人数（変更前の勤務と変更後の勤務）。休みを移す案は両日の休みの人数が並ぶ。
        val impact = listOfNotNull(before, op.toShift).distinct().joinToString("、") { k ->
            "${sym(k)} ${snapshot.count { it.getOrNull(op.day) == k }}人 → ${after.count { it.getOrNull(op.day) == k }}人"
        }
        ChangeRow(name(op.staff), DayText.short(startDate, op.day), before?.let { shift(it) } ?: "?", shift(op.toShift), wish, impact, key(op.staff))
    }
    val changes = rows.map { r -> "${r.staff} ${r.date} ${r.before} → ${r.after}" + (if (r.wish.isNotEmpty()) "・${r.wish}" else "") }
    val (hard, caution) = fixImpactLines(s)
    // 人数・マス数は本文 1 行目（提案のラベル）が持つ。題に入れると「（6 / 人・11 セル）」のように途中で折り返した。
    return ChainFixPreview(s, "複数人の入れ替え", changes, hard, caution, rows = rows,
        countNotes = staffCountNotes(board, s.ops, staffNames, shiftSymbols))
}
