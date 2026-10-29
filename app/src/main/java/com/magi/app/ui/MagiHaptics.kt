package com.magi.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** [MagiHaptic] を端末の振動へ。振動の有無は端末の「タップ時の振動」設定に従う（アプリに独自のスイッチは置かない）。 */
@Composable
fun rememberMagiHaptics(): (MagiHaptic) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { h: MagiHaptic ->
            view.performHapticFeedback(when (h) {
                MagiHaptic.CONFIRM -> HapticFeedbackConstants.CONFIRM
                MagiHaptic.REJECT -> HapticFeedbackConstants.REJECT
            })
        }
    }
}
