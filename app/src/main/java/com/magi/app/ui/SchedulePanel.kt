package com.magi.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * [3.718.0] 勤務表の前に出す画面の共通の殻（2026-10-18 ユーザー提示の仕様「画面高さの約 1/3 以内」「背面ロックを行わないモードレス」）。
 * 下に寄せた画面の 1/3 までのパネルで、中は縦にだけスクロール（横のスクロールは表だけ）。背面の表は暗くせず、そのまま押せる。
 * 閉じ方はセルのシートと同じ＝✕・戻る・パネルの外のタップ。見出しと✕はパネルの上端（画面の 2/3 の高さ＝親指が届く）。
 */
@Composable
internal fun SchedulePanel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val maxH = panelMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp
    val topCorner = MaterialTheme.shapes.extraLarge
    BackHandler(onBack = onDismiss)
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true)) {
        Surface(modifier = Modifier.fillMaxWidth().heightIn(max = maxH), color = cs.surfaceContainerLow,
            shape = topCorner.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)), tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogHeader(title, onClose = onDismiss)
                content()
            }
        }
    }
}
