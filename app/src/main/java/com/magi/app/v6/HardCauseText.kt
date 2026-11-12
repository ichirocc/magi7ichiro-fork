package com.magi.app.v6

/**
 * 必須違反の原因（CP-SAT の互いに素な最小の組、[HardCauses]）を診断ログの 1 行ずつへ（3.727.73、S5）。
 * 1 行＝「この中の 1 件を緩めないと、その必須違反は消えない」の証明。日またぎの原因（前日の必要人数と禁止の並び）も名指しする。
 */
internal object HardCauseText {
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
}
