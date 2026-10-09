package com.magi.app.ui

/** 直し方を当てたあと、同じ問題について「どうなったか」を 1 行で返す（3.643.0）。改善手の適用（前後の件数が分かる）用。 */
internal fun fixOutcomeText(label: String, hardBefore: Int, hardAfter: Int, totalBefore: Int, totalAfter: Int): String = when {
    hardAfter == 0 && hardBefore > 0 -> "$label を当てました。必須違反はなくなりました（合計 $totalBefore→$totalAfter）。"
    hardAfter < hardBefore -> "$label を当てました。必須違反 $hardBefore→$hardAfter。ほかの必須違反は $hardAfter 件残っています。"
    else -> "$label を当てました。必須違反は変わらず $hardAfter 件（合計 $totalBefore→$totalAfter）。"
}

/** 「なおし方を見る」で 1 人を入れたあと（人員不足の枠が対象）。[stillMiss]＝再検査後にその枠でまだ足りない人数（null＝解消）。 */
internal fun guidedFixOutcomeText(dayLabel: String, shiftSymbol: String, stillMiss: Int?, hardAfter: Int): String =
    if (stillMiss == null) "$dayLabel の「$shiftSymbol」の人員不足を解消しました。" +
        (if (hardAfter > 0) "ほかの必須違反は $hardAfter 件残っています。" else "必須違反はなくなりました。")
    else "$dayLabel の「$shiftSymbol」はまだ ${stillMiss}人 足りません。必須違反は $hardAfter 件です。"
