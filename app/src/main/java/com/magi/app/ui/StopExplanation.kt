package com.magi.app.ui

import com.magi.app.v6.V6FinalPort

/** 次の一手の種類（ホームの結果カードは既存のボタンへ対応づける）。 */
internal enum class StopNext { NONE, MORE_TIME, REVIEW_STAFFING, REVIEW_WISHES, FIND_FIX }

/** 停滞で早く終えた理由と次の一手の一文（3.643.0、`docs/stall_escape.md` §12）。内部名を出さない。純関数＝ホストでテストする。 */
internal data class StopExplanation(val line: String, val next: StopNext)

internal fun stopExplanationOf(s: V6FinalPort.StopSummary): StopExplanation? {
    val hard = s.remainingHard
    if (hard == 0) {
        return if (s.earlyStop) StopExplanation("改善が止まったので ${s.usedSec} 秒で終えました（予算 ${s.budgetSec} 秒）。必須違反はありません。", StopNext.NONE) else null
    }
    return when (s.kind) {
        V6FinalPort.StopKind.DEADLINE ->
            StopExplanation("制限時間（${s.budgetSec} 秒）いっぱいまで探しました。最後の改善から ${s.stalledSec} 秒で、必須違反は ${hard} 件残っています。", StopNext.MORE_TIME)
        V6FinalPort.StopKind.PLATEAU_FLOOR ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard} 件は、担当できる人数が足りない人員不足で、探索では減りません。", StopNext.REVIEW_STAFFING)
        V6FinalPort.StopKind.WISH_FLOOR ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard} 件は希望どうしのぶつかりで、いまの希望のままでは減りません。", StopNext.REVIEW_WISHES)
        V6FinalPort.StopKind.C3N_WALL_CERTIFIED ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard} 件は禁止の並びで、本人の希望で固定された並びです。希望を変えない限り崩せません。", StopNext.REVIEW_WISHES)
        V6FinalPort.StopKind.C3N_WALL_EMPIRICAL ->
            StopExplanation("${s.usedSec} 秒で終えました。残る必須違反 ${hard} 件は禁止の並びで、1 マスの変更・玉突き・隣の日の調整では崩せませんでした。", StopNext.FIND_FIX)
        V6FinalPort.StopKind.NORMAL_STALL ->
            StopExplanation("改善が ${s.stalledSec} 秒止まったので ${s.usedSec} 秒で終えました（予算 ${s.budgetSec} 秒）。必須違反 ${hard} 件が残っています。", StopNext.FIND_FIX)
    }
}

internal fun stopNextLabel(n: StopNext): String? = when (n) {
    StopNext.NONE -> null
    StopNext.MORE_TIME -> "時間を増やして再作成する"
    StopNext.REVIEW_STAFFING -> "担当を見直す（人を増やす）"
    StopNext.REVIEW_WISHES -> "希望を見直す"
    StopNext.FIND_FIX -> "直し方を探す"
}
