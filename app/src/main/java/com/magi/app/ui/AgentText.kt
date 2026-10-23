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

internal const val AGENT_RUNNING_LINE = "代行で仕上げています…（必須違反を減らす変更を順に当てています）"
internal const val AGENT_TOGGLE_LABEL = "作成のあと代行で仕上げる"
internal const val AGENT_TOGGLE_NOTE = "必須違反が残ったとき、安全な勤務の変更だけを自動で当てます。希望を外す・設定を緩めるは判断を待ちます。"

/** カードの見出し。何もしなかった輪は出さない（null）。 */
internal fun agentHeadline(r: AgentRecord): String? =
    if (r.steps.isEmpty()) null
    else "代行で ${r.steps.size} 手を当てました（" +
        (if (r.hardBefore != r.hardAfter) "必須違反 ${r.hardBefore}→${r.hardAfter}" else "要調整 ${r.softBefore}→${r.softAfter}") + "）"

/** 見出しの下の 1 行: 残りが人の判断かどうか。 */
internal fun agentNextLine(r: AgentRecord): String =
    if (r.hardAfter == 0) "必須違反はありません。配布前の確認に進めます。"
    else "残り ${r.hardAfter} 件は勤務の変更だけでは直せません。"

/** 判断待ちの内訳（ホームの判断のカード・設定ミスのカードにある承認の数）。空なら判断待ちは無い。 */
internal fun agentPendingItems(wishes: Int, relaxFound: Boolean, issues: Int): List<String> = listOfNotNull(
    wishes.takeIf { it > 0 }?.let { "希望の見直し ${it}件" },
    if (relaxFound) "設定を緩める候補" else null,
    issues.takeIf { it > 0 }?.let { "設定ミスの直し ${it}件" },
)

internal const val AGENT_NO_STEP_HEADLINE = "代行で当てられる勤務の変更はありませんでした"
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
