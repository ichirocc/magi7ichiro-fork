package com.magi.app.ui

/** 直し方を当てたあと、同じ問題について「どうなったか」を 1 行で返す（3.643.0）。改善手の適用（前後の件数が分かる）用。 */
internal fun fixOutcomeText(label: String, hardBefore: Int, hardAfter: Int, softBefore: String, softAfter: String, afterBreakdown: Map<String, Int> = emptyMap()): String {
    val left = hardText(afterBreakdown, hardAfter.toLong())
    return when {
        hardAfter == 0 && hardBefore > 0 -> "$label を当てました。必須違反はなくなりました（要調整 $softBefore→$softAfter）。"
        hardAfter < hardBefore -> "$label を当てました。必須違反 $hardBefore→$hardAfter。ほかの必須違反は $left 残っています。"
        else -> "$label を当てました。必須違反は変わらず $left（要調整 $softBefore→$softAfter）。"
    }
}

/**
 * 当てたあとの内訳（違反セルの半自動修正仕様 §8、3.663.0）: 解消・減少した違反と、新たに生じた問題を分けて言う。
 * 数は報告の内訳（族ごとの件数、曜日の偏り・公平化は pt）。件数が増えなくても別の場所に生まれた違反は「場所が変わった」と言う
 * （[com.magi.app.v6.ViolationIdentity]）。必須の残りは [fixOutcomeText] が言う＝ここでは「新たな必須違反なし」と全体の 0 件を言い分けるだけ。
 */
internal fun fixResultDetail(before: com.magi.app.v6.ViolationReport, after: com.magi.app.v6.ViolationReport): String {
    val fams = (before.breakdown.keys + after.breakdown.keys).sortedByDescending { com.magi.app.v6.MirrorKeys.weightOf(it) }
    fun label(f: String) = breakdownLabels[f] ?: f
    fun d(f: String) = (after.breakdown[f] ?: 0) - (before.breakdown[f] ?: 0)
    val down = fams.filter { d(it) < 0 }.map { "${label(it)} ${-d(it)}${unitOf(it)}" }
    val up = fams.filter { d(it) > 0 }
    val moved = com.magi.app.v6.ViolationIdentity.diff(before, after).appeared.map { it.family }.toSet()
        .filter { it !in up && (after.breakdown[it] ?: 0) > 0 }.sortedByDescending { com.magi.app.v6.MirrorKeys.weightOf(it) }
    val appeared = up.map { "${label(it)} +${d(it)}${unitOf(it)}" } + moved.map { "${label(it)}（場所が変わった）" }
    val newHard = com.magi.app.v6.ViolationIdentity.newHard(before, after).size
    return (if (down.isEmpty()) "解消・減少した違反: なし。" else "解消・減少した違反: ${down.joinToString("・")}。") +
        (if (appeared.isEmpty()) "新たに生じた問題: なし。" else "新たに生じた問題: ${appeared.joinToString("・")}。") +
        (if (newHard > 0) "新たな必須違反が $newHard か所あります。" else if (after.hard > 0) "新たな必須違反はありません（全体の必須違反は ${hardText(after.breakdown, after.hard.toLong())}）。" else "")
}

/** 「なおし方を見る」で 1 人を入れたあと（人員不足の枠が対象）。[stillMiss]＝再検査後にその枠でまだ足りない人数（null＝解消）。 */
internal fun guidedFixOutcomeText(dayLabel: String, shiftSymbol: String, stillMiss: Int?, hardAfter: Int, breakdown: Map<String, Int> = emptyMap()): String =
    if (stillMiss == null) "$dayLabel の「$shiftSymbol」の人員不足を解消しました。" +
        (if (hardAfter > 0) "ほかの必須違反は ${hardText(breakdown, hardAfter.toLong())} 残っています。" else "必須違反はなくなりました。")
    else "$dayLabel の「$shiftSymbol」はまだ ${stillMiss}人 足りません。必須違反は ${hardText(breakdown, hardAfter.toLong())}です。"
