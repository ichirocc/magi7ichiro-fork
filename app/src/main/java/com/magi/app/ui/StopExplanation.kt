package com.magi.app.ui

import com.magi.app.v6.V6FinalPort

/** 次の一手の種類（ホームの結果カードは既存のボタンへ対応づける）。 */
internal enum class StopNext { NONE, MORE_TIME, REVIEW_STAFFING, REVIEW_WISHES, FIND_FIX }

/** 停滞で早く終えた理由と次の一手の一文（3.643.0、`docs/stall_escape.md` §12）。内部名を出さない。純関数＝ホストでテストする。 */
internal data class StopExplanation(val line: String, val next: StopNext)

internal fun stopExplanationOf(s: V6FinalPort.StopSummary, breakdown: Map<String, Int> = emptyMap()): StopExplanation? {
    val hard = hardText(breakdown, s.remainingHard.toLong())
    // 使った時間は後処理まで含めた全体、無改善の秒数は探索の終了時点の値（どちらの時点かを文で言う）。
    val used = "使った時間 ${s.usedSec} 秒・探索時間 ${s.budgetSec} 秒"
    // 必須 0 なら早く終えた理由は言わない（結果カードの見出しで足りる）。
    if (s.remainingHard == 0) return null
    return when (s.kind) {
        V6FinalPort.StopKind.DEADLINE ->
            StopExplanation("探索時間（${s.budgetSec} 秒）いっぱいまで探しました。探索の終了時点で、最後の改善から ${s.stalledSec} 秒が経過していました。最終結果の必須違反は ${hard}です。",
                // 探索時間がすでに上限なら「延ばす」は勧めない。
                if (s.budgetSec < com.magi.app.v6.MAX_OPTIMIZE_SEC) StopNext.MORE_TIME else StopNext.FIND_FIX)
        V6FinalPort.StopKind.PLATEAU_FLOOR ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard}は、担当できる職員が足りないための人員不足です。解消するには、担当条件や配置できる職員の見直しが必要です。", StopNext.REVIEW_STAFFING)
        V6FinalPort.StopKind.WISH_FLOOR ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard}は、同時には満たせない希望によるものです。解消するには、対象の希望を調整する必要があります。", StopNext.REVIEW_WISHES)
        V6FinalPort.StopKind.C3N_WALL_CERTIFIED ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard}は禁止の並びで、希望によって勤務が固定されています。解消には希望の調整が必要です。", StopNext.REVIEW_WISHES)
        V6FinalPort.StopKind.C3N_WALL_EMPIRICAL ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard}は禁止の並びです。1 マスの変更、複数の勤務の入れ替え（玉突き）、隣接日の調整を試しましたが、解消する案は見つかりませんでした。", StopNext.FIND_FIX)
        V6FinalPort.StopKind.NORMAL_STALL ->
            StopExplanation("${s.stalledSec} 秒間、改善が見られなかったため、探索を早めに終了しました（$used）。必須違反 ${hard}が残っています。", StopNext.FIND_FIX)
    }
}

/** 次の一手は直す場所（タブと節）から言う＝カードの文はボタンでないので、行き先を文で示す。 */
internal fun stopNextLabel(n: StopNext): String? = when (n) {
    StopNext.NONE -> null
    StopNext.MORE_TIME -> "設定タブの「勤務表をつくる時間」で探索時間を延ばし、再作成する"
    StopNext.REVIEW_STAFFING -> "編集タブの「年間マスター」①で、担当できる職員を確認する"
    StopNext.REVIEW_WISHES -> "編集タブの「月次条件」で希望を見直す"
    StopNext.FIND_FIX -> "分析タブで直し方を探す"
}
