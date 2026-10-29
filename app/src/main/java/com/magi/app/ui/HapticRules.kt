package com.magi.app.ui

/** 触覚の手応えの種類（iOS 26 の種類分けから）。確定＝盤面や条件を書き換えた、拒否＝失敗・止められた。 */
enum class MagiHaptic { CONFIRM, REJECT }

/** 通知 1 件に付ける手応え。失敗だけを拒否で知らせ、ふつうの結果は振動させない（通知のたびに震えないように）。 */
internal fun hapticForMessage(isError: Boolean): MagiHaptic? = if (isError) MagiHaptic.REJECT else null

/** 「つくる」が終わったときの手応え。走っていた → 止まった、の 1 回だけ。 */
internal fun hapticForRunEnd(wasRunning: Boolean, running: Boolean, failed: Boolean): MagiHaptic? = when {
    !wasRunning || running -> null
    failed -> MagiHaptic.REJECT
    else -> MagiHaptic.CONFIRM
}
