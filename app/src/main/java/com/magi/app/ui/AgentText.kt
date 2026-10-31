package com.magi.app.ui

/** 代行役（AX）が 1 回の輪で当てた手の記録（画面のカードと操作ログに使う）。 */
data class AgentRecord(
    val steps: List<String>,
    val hardBefore: Int, val hardAfter: Int,
    val softBefore: String, val softAfter: String,
    val stop: String,
    val undoSerial: Long = 0L,   // 代行の Undo 段（先頭にあるときだけカードから戻せる）
)

/** 代行の 1 回の輪の上限（探索 1 回は `AgentLoop.SEARCH_MS`）。 */
internal const val AGENT_BUDGET_MS = 30_000L
/** 1 手の案が尽きたときの「自動で整える」の上限（秒）。 */
internal const val AGENT_WIDEN_MAX_SEC = 10

internal const val AGENT_RUNNING_LINE = "代行で仕上げています…（安全な勤務の変更を順に当てています）"
internal const val AGENT_TOGGLE_LABEL = "作成のあと代行で仕上げる"
internal const val AGENT_TOGGLE_NOTE = "必須違反が残ったら、安全な勤務の変更だけを自動で当てます。希望と設定の変更は承認を待ちます。"

/** カードの見出し。何もしなかった輪は出さない（null）。要調整は動いた単位の 1 つだけ（全単位は「当てた手」の中＝[agentSoftDetail]）。 */
internal fun agentHeadline(r: AgentRecord): String? =
    if (r.steps.isEmpty()) null
    else "代行で ${r.steps.size} 手を当てました（" +
        (if (r.hardBefore != r.hardAfter) "必須違反 ${r.hardBefore}→${r.hardAfter}" else "要調整 ${softHeadDelta(r.softBefore, r.softAfter)}") + "）"

/** 通知の 1 行（カードと同じ文を 2 か所に出さない＝内訳はカード）。 */
internal fun agentNotice(r: AgentRecord): String? = if (r.steps.isEmpty()) null else "代行で ${r.steps.size} 手を当てました"

/** 「当てた手」を開いたときの要調整の全単位。 */
internal fun agentSoftDetail(r: AgentRecord): String = "要調整 ${r.softBefore} → ${r.softAfter}"

/** 「147件・3人・80回」と「140件・4人・70回」から、値が動いた最初の単位を「147→140件」で（消えた単位は 0）。どれも動かなければ先頭の単位。 */
internal fun softHeadDelta(before: String, after: String): String {
    val tok = Regex("^(\\d+)(\\D+)$")
    fun parse(s: String) = s.split("・").mapNotNull { tok.find(it.trim())?.destructured?.let { (n, u) -> u to n } }
    val b = parse(before); val a = parse(after).toMap()
    if (b.isEmpty()) return "$before→$after"
    val (u, n) = b.firstOrNull { (u, n) -> a[u] != n } ?: b.first()
    return "$n→${a[u] ?: "0"}$u"
}

/** 判断待ちの内訳（ホームの判断のカード・設定ミスのカードにある承認の数）。空なら判断待ちは無い。 */
internal fun agentPendingItems(wishes: Int, relaxFound: Boolean, issues: Int): List<String> = listOfNotNull(
    wishes.takeIf { it > 0 }?.let { "希望の見直し ${it}件" },
    if (relaxFound) "設定を緩める候補" else null,
    issues.takeIf { it > 0 }?.let { "設定ミスの直し ${it}件" },
)

internal const val AGENT_RESUME_NOTE = "承認すると、代行が続きを回します。"

/** 判断待ちの 1 行。 */
internal fun agentPendingLine(items: List<String>): String? =
    items.takeIf { it.isNotEmpty() }?.joinToString("・", prefix = "判断待ち：")

/** 輪の止まり方（操作ログ用）。 */
internal fun agentStopWord(stop: com.magi.app.v6.AgentLoop.Stop): String = when (stop) {
    com.magi.app.v6.AgentLoop.Stop.NO_CANDIDATE -> "当てられる手が尽きた"
    com.magi.app.v6.AgentLoop.Stop.ROUND_LIMIT -> "手数の上限"
    com.magi.app.v6.AgentLoop.Stop.TIME_LIMIT -> "時間の上限"
    com.magi.app.v6.AgentLoop.Stop.CANCELLED -> "中断"
}

/** 代行は「作成のあと」だけ動く: 作成が終わった時点の設定の指紋 [runKey] と今の指紋 [nowKey] が同じとき。作成の後に制約を変えたら動かない。 */
internal fun agentAfterRun(runKey: Long?, nowKey: Long): Boolean = runKey != null && runKey == nowKey
