package com.magi.app.ui

/** 直し方を当てたあと、同じ問題について「どうなったか」を 1 行で返す（3.643.0）。改善手の適用（前後の件数が分かる）用。 */
internal fun fixOutcomeText(label: String, hardBefore: Int, hardAfter: Int, softBefore: String, softAfter: String, afterBreakdown: Map<String, Int> = emptyMap()): String {
    val left = hardText(afterBreakdown, hardAfter.toLong())
    return when {
        hardAfter == 0 && hardBefore > 0 -> "$label を適用しました。必須違反はなくなりました（要調整 $softBefore→$softAfter）。"
        hardAfter < hardBefore -> "$label を適用しました。必須違反 $hardBefore→$hardAfter。ほかの必須違反は $left 残っています。"
        else -> "$label を適用しました。必須違反は変わらず $left（要調整 $softBefore→$softAfter）。"
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
        (if (newHard > 0) "新たな必須違反が ${newHard}か所あります。" else if (after.hard > 0) "新たな必須違反はありません（全体の必須違反は ${hardText(after.breakdown, after.hard.toLong())}）。" else "")
}

/** 「なおし方を見る」で 1 人を入れたあと（人員不足の枠が対象）。[stillMiss]＝再検査後にその枠でまだ足りない人数（null＝解消）。 */
internal fun guidedFixOutcomeText(dayLabel: String, shiftSymbol: String, stillMiss: Int?, hardAfter: Int, breakdown: Map<String, Int> = emptyMap()): String =
    if (stillMiss == null) "$dayLabel の「$shiftSymbol」の人員不足を解消しました。" +
        (if (hardAfter > 0) "ほかの必須違反は ${hardText(breakdown, hardAfter.toLong())} 残っています。" else "必須違反はなくなりました。")
    else "$dayLabel の「$shiftSymbol」はまだ ${stillMiss}人 足りません。必須違反は ${hardText(breakdown, hardAfter.toLong())}です。"

/** 改善の 1 手の名を画面の言葉へ（エンジンの名は照合の鍵にも使う＝表示のときだけ言い換える）。合わなければそのまま。 */
internal fun fixDisplayLabel(label: String): String {
    Regex("^（玉突き連鎖）(\\d+)人・(\\d+)マスを順に入れ替え$").find(label)?.let { m -> return "${m.groupValues[1]}人の勤務を順に入れ替える（${m.groupValues[2]}か所）" }
    Regex("^（再最適化）(.+) の(\\d+)名を最適割当$").find(label)?.let { m -> return "${m.groupValues[1]} の ${m.groupValues[2]}人の勤務を組み直す" }
    Regex("^（連鎖）(.+) の「(.+)」不足を(\\d+)コマ補充$").find(label)?.let { m -> return "${m.groupValues[1]}さんの ${m.groupValues[2]} を ${m.groupValues[3]}回増やす" }
    return label
}

/** 改善の案の 1 行目＝効き目（必須違反の変化、無ければ要調整の変化）。どちらも動かなければ null。 */
internal fun fixBenefitLine(s: com.magi.app.v6.FixSuggestion): String? =
    hardChangeLine(s.diff) ?: familyDeltaList(s.diff.filter { it.first !in com.magi.app.v6.MirrorKeys.hard }).takeIf { it.isNotEmpty() }?.let { "要調整の変化：$it" }

internal const val NO_FIX_SUGGESTION_TEXT = "今の勤務表で、1手で減らせる案は見つかりませんでした。上の職員を押すと、その人に絞って探します"

/** 自動で整えた結果の通知（採用したか＝[adopted]）。要調整は単位ごと、必須違反は残りの有無だけ言う。 */
internal fun polishResultText(adopted: Boolean, softBefore: String, softAfter: String, hardAfter: Int, hardAfterText: String): String =
    if (adopted) "自動で整えました。要調整 $softBefore → $softAfter" + if (hardAfter == 0) "（必須違反はなしのまま）" else "（必須違反 $hardAfterText）"
    else "自動で整えましたが、要調整は減りませんでした（勤務表は変えていません）"

/** 人員不足の候補 1 人の影響（「休 8回 → 7回（下限 8回を割ります）」）。今の勤務が無ければ null。 */
internal fun guidedCandidateImpact(fromSym: String?, before: Int, lo: Int?): String? =
    fromSym?.let { "$it ${before}回 → ${before - 1}回" + if (lo != null && before - 1 < lo) "（下限 ${lo}回を割ります）" else "" }

/** 人員不足を直し終えたときの、ほかの必須違反（人員不足を除く）。無ければ null。 */
internal fun otherHardAfterShortage(breakdown: Map<String, Int>): String? =
    unitTotals(breakdown, com.magi.app.v6.MirrorKeys.hard.filter { it != "covU" })?.let { "ほかの必須違反（$it）は分析タブで" }

internal const val SHORTAGE_DONE_TITLE = "人員不足は直し終わりました"
