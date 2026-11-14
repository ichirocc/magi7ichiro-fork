package com.magi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 確認・入力・知らせのダイアログを、画面の下端から出る札にする（iOS のアクションシート・シートに寄せる。3.727.82）。
 * 引数は Material の AlertDialog と同じ＝呼び出し側は名前を替えるだけ。ボタンは全幅の行で縦に並べ、確定を上、取り消しを
 * いちばん下に置く（親指が届く下端に確定がある）。中身が長いときは中身の側だけが縮む（ボタンは常に見える）。
 * 影・半透明は使わない（DESIGN.md）。[containerColor] は指定が無ければシートの既定の地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MagiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = state,
        modifier = modifier,
        containerColor = if (containerColor == Color.Unspecified) BottomSheetDefaults.ContainerColor else containerColor,
        dragHandle = { MagiSheetGrabber(state) },
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
            title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() } }
            text?.let {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            HorizontalDivider(color = cs.outlineVariant)
            DialogActionRow(confirmButton)
            dismissButton?.let {
                HorizontalDivider(color = cs.outlineVariant)
                DialogActionRow(it)
            }
        }
    }
}

/** 札の 1 行のボタン（全幅・高さ 48dp 以上・中央）。 */
@Composable
private fun DialogActionRow(button: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) { button() }
    }
}

/**
 * 選ぶ値を出すカプセルのボタン（iOS のメニューボタン。右端に上下の山形）。押すと呼び出し側のメニューが開く。
 */
@Composable
internal fun MagiMenuButton(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    monospace: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant,
        shape = MagiCorners.capsule,
        modifier = modifier.heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f),
            )
            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** メニューの 1 行。選んでいる行は左に ✓（iOS のメニュー）。選択の無いメニュー（操作の一覧）は [selected]＝null。 */
@Composable
internal fun MagiMenuItem(
    text: String,
    onClick: () -> Unit,
    selected: Boolean? = null,
    monospace: Boolean = false,
) {
    DropdownMenuItem(
        text = { Text(text, fontFamily = if (monospace) FontFamily.Monospace else null) },
        onClick = onClick,
        leadingIcon = if (selected == null) null else {
            { if (selected) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) }
        },
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            role = Role.Button
            if (selected != null) this.selected = selected
        },
    )
}
