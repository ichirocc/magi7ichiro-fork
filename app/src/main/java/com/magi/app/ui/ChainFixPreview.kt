package com.magi.app.ui

import com.magi.app.v6.FixSuggestion

/** [3.644.0/UX-03] 複数人の入替（玉突き）を当てる前の一覧。だれの・どの日の・何を何に変えるかと、必須違反の増減（`fixImpactLines`）。 */
data class ChainFixPreview(
    val suggestion: FixSuggestion,
    val changes: List<String>,
    val hardLine: String,
    val caution: String?,
    /** 探した枠（日付・シフト記号・見出し）。相談に積んだあと、今の勤務表で案を探し直すために持つ。 */
    val target: ChainTarget? = null,
    /** 当てようとして断られた理由（確認の画面を閉じずに先頭へ出す）。null＝まだ当てていない。 */
    val applyError: String? = null,
    /** 全体の研磨の案（ホーム・分析の「この手を使う」）＝対象を持たずに当ててよいと呼び手が明示した。false で対象が無ければ断る。 */
    val globalPolish: Boolean = false,
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
    /** 前後の 1 行（[previewSummaryLine]、applyError の次に太字）。null＝まだ組み立てていない。 */
    val summary: String? = null,
)

/** 変わるセル 1 つ。[wish]＝希望があるときの注記（希望どおり／希望と異なる）。[staffKey]＝職員 ID（無ければ並び順）＝同名でも別の人として束ねる。
 *  [staff] は表示名（同名の職員がいればグループ記号を添える）。 */
data class ChangeRow(val staff: String, val date: String, val before: String, val after: String, val wish: String = "", val impact: String = "", val staffKey: Int = -1)

/** 変更を当てるボタン（違反セルの半自動修正仕様 §5: 押すと検証してから全変更を反映する）。 */
internal const val APPLY_CHANGE_BUTTON = "この変更を適用する"
/** [3.687.0/R09] 確認の画面の門の説明（[FixApplyGate] と同じ規則: 必須を増やさない・別の場所に必須を生まない・希望の固定を崩さない。要調整だけの改善は通る）。 */
internal const val PREVIEW_GATE_NOTE = "適用の直前に検査し直し、必須違反を増やす・生む変更や希望の固定を崩す変更は当てません。「元に戻す」で取り消せます。"
/** 当てずに閉じるボタン（今の勤務表のまま）。 */
internal const val KEEP_CURRENT_BUTTON = "今は変更しない"
/** 変更を当てる前の確認の見出し（1 手でも入替でも同じ）。 */
internal const val PREVIEW_TITLE = "変更の確認"

/** 変更の確認の 1 行と、変わるセルの 1 行（「山本  10/15  休 → 遅番」）。 */
internal fun previewCountLine(n: Int): String = "${n}か所変わります"
internal fun previewRowLine(staff: String, date: String, before: String, after: String): String = "$staff  $date  $before → $after"

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
    return ChainFixPreview(s, changes, hard, caution, rows = rows,
        countNotes = staffCountNotes(board, s.ops, staffNames, shiftSymbols))
}

/** 対象の数の変化の 3 行（案の一覧・争点カード・確認の画面で共有）。 */
internal fun previewEffectLines(label: String, e: CountEffect, basis: String): List<String> =
    listOf("$label：${e.countLine()}", "今回の${e.gapLine()}", e.afterNote(basis))

/**
 * 確認の画面の前後の 1 行（D-2）「今回の不足 1人 → 0人（解消）・2か所を変更・新たな必須違反なし」。区切りごとに単位は 1 つ（INV-07）。
 * [effect]＝見ている問題の変化（null＝対象なし＝必須違反の増減の向きだけ言う）、[newHard] null＝未評価（INV-10）。
 */
internal fun previewSummaryLine(effect: CountEffect?, cells: Int, hardDelta: Int, newHard: List<String>?): String = listOfNotNull(
    effect?.let { "今回の${it.gapLine()}（${appliedStatusWord(it)}）" }
        ?: when { hardDelta < 0 -> "必須違反は減ります"; hardDelta > 0 -> "必須違反は増えます"; else -> null },
    "${cells}か所を変更",
    when { newHard == null -> "新たな必須違反は未評価（適用の直前に検査）"; newHard.isEmpty() -> "新たな必須違反なし"; else -> "新たな必須違反 ${newHard.size}件" },
).joinToString("・")

/** 見ている問題の目的の 1 文（一覧から来ない経路で使う）「11/3のA4の不足を減らす」。 */
internal fun previewPurpose(t: FixTarget, label: String, staffName: String?): String =
    (staffName?.let { "${it}さんの" } ?: "") + "${label}の${t.gapWord}を減らす"

/**
 * 3 つの組み立て口（候補・玉突き・案の一覧）で共通の仕上げ: [ChainFixPreview.fixTarget] があれば目的と対象の数の変化を埋め
 * （呼び手が渡していればそのまま）、前後の 1 行を作る。
 */
internal fun fillPreview(p: ChainFixPreview, schedule: List<List<Int>>, shiftSymbols: List<String>, startDate: String, staffNames: List<String>): ChainFixPreview {
    val t = p.fixTarget
    val e = t?.effect(schedule, p.suggestion.ops)
    val label = t?.let { fixTargetLabel(it, shiftSymbols.getOrNull(it.shift) ?: "", startDate) }
    val hardDelta = p.suggestion.diff.filter { it.first in com.magi.app.v6.MirrorKeys.hard }.sumOf { it.second }
    return p.copy(
        purpose = p.purpose ?: if (t != null && label != null) previewPurpose(t, label, t.staff?.let { staffNames.getOrNull(it) }) else null,
        effect = p.effect.ifEmpty { if (t != null && e != null && label != null) previewEffectLines(label, e, t.basis) else emptyList() },
        summary = previewSummaryLine(e, p.suggestion.ops.size, hardDelta, p.newHard),
    )
}
