package com.magi.app.v6

/**
 * 必須違反の原因（CP-SAT の互いに素な最小の組、[HardCauses]）を診断ログの 1 行ずつへ（3.727.73、S5）。
 * 1 行＝「この中の 1 件を緩めないと、その必須違反は消えない」の証明。日またぎの原因（前日の必要人数と禁止の並び）も名指しする。
 */
object HardCauseText {
    /** [staff]/[shift] は名前・記号、[day] は日付の表示、[seq] は禁止の並びの行の記号列。 */
    fun lines(
        cores: List<HardCauses.Core>,
        staff: (Int) -> String, shift: (Int) -> String, day: (Int) -> String, seq: (Int) -> String,
    ): List<String> = cores.mapIndexed { n, c ->
        val parts = c.causes.map { x ->
            when (x.kind) {
                HardCauses.Kind.WISH -> "希望 ${staff(x.staff)} ${day(x.day)}=${shift(x.shift)}"
                HardCauses.Kind.PIN -> "手動固定 ${staff(x.staff)} ${day(x.day)}=${shift(x.shift)}"
                HardCauses.Kind.NEED -> "必要人数 ${day(x.day)} ${shift(x.shift)} ${x.rule}人"
                HardCauses.Kind.FORBIDDEN_SEQ -> "禁止の並び ${seq(x.rule)}"
                HardCauses.Kind.OUTSIDE -> "担当外・拡張希望の禁止 ${staff(x.staff)}"
            }
        }
        "#${n + 1}" + (if (c.minimal) "" else "（最小か未確認）") + ": " + parts.joinToString(" ・ ")
    }

    /** 画面の札の 1 組（3.727.78）。[title] は何が成り立たないか、[lines] は種類ごとにまとめた原因。 */
    data class Block(val title: String, val lines: List<String>, val minimal: Boolean)

    /**
     * 画面用。必要人数を含む組は、その件数がいちばん多い日（同数なら後の日）を見出しにし、ほかの日の必要人数は
     * 「別の日」として分ける＝日またぎの原因（前日の勤務と禁止の並び）が見出しの日と区別して読める。
     */
    fun blocks(
        cores: List<HardCauses.Core>,
        staff: (Int) -> String, shift: (Int) -> String, day: (Int) -> String, seq: (Int) -> String,
    ): List<Block> = cores.map { c ->
        fun of(kind: HardCauses.Kind) = c.causes.filter { it.kind == kind }
        val wishes = of(HardCauses.Kind.WISH); val pins = of(HardCauses.Kind.PIN); val needs = of(HardCauses.Kind.NEED)
        val seqs = of(HardCauses.Kind.FORBIDDEN_SEQ); val outside = of(HardCauses.Kind.OUTSIDE)
        val anchor = needs.groupingBy { it.day }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
        val fixedStaff = (wishes + pins).map { it.staff }.distinct()
        val title = when {
            anchor != null -> "${day(anchor)} の必要人数を満たせない"
            fixedStaff.size == 1 -> "${staff(fixedStaff[0])}さんの希望・固定が両立しない"
            else -> "希望・固定が両立しない"
        }
        fun cell(x: HardCauses.Cause) = "${staff(x.staff)} ${day(x.day)}=${shift(x.shift)}"
        val otherNeeds = needs.filter { it.day != anchor }
        val lines = ArrayList<String>()
        if (wishes.isNotEmpty()) lines += "希望: " + wishes.joinToString(" ・ ") { cell(it) }
        if (pins.isNotEmpty()) lines += "手動固定: " + pins.joinToString(" ・ ") { cell(it) }
        needs.filter { it.day == anchor }.takeIf { it.isNotEmpty() }?.let { l -> lines += "必要人数: " + l.joinToString(" ・ ") { "${shift(it.shift)} ${it.rule}人" } }
        if (otherNeeds.isNotEmpty()) lines += "別の日の必要人数: " + otherNeeds.joinToString(" ・ ") { "${day(it.day)} ${shift(it.shift)} ${it.rule}人" }
        if (seqs.isNotEmpty()) lines += "禁止の並び: " + seqs.joinToString(" ・ ") { seq(it.rule) }
        if (outside.isNotEmpty()) lines += "担当外・拡張希望の禁止: " + outside.joinToString(" ・ ") { staff(it.staff) }
        if (otherNeeds.isNotEmpty() && seqs.isNotEmpty()) lines += HARD_CAUSE_CROSS_DAY_NOTE
        Block(title, lines, c.minimal)
    }
}

internal const val HARD_CAUSE_CARD_TITLE = "必須違反の原因"
internal const val HARD_CAUSE_CARD_LEAD = "どの組も、この中の 1 件を緩めないと必須違反が 1 件残ります（証明済み）。"
internal const val HARD_CAUSE_NOT_MINIMAL = "この組は、もっと少ない原因に絞れるかを確かめきれていません。"
internal const val HARD_CAUSE_CROSS_DAY_NOTE = "別の日の勤務と禁止の並びのために、この日に入れる人が減っています。"
/** 札で最初に出す組の数。残りは「すべて表示」。 */
internal const val HARD_CAUSE_PREVIEW = 3
