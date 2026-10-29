package com.magi.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 操作の結果の通知（iOS 26 の浮く札から）。画面の端から浮く角丸の面で、影は付けない（下部タブと同じ決まり）。
 * 角はカードと同じ [CornerMetrics.CARD_DP]＝1 行ならカプセルに見え、複数行は角丸の四角のまま。
 */
@Composable
fun MagiNoticeCapsule(data: SnackbarData, isError: Boolean) {
    val cs = MaterialTheme.colorScheme
    val bg = if (isError) cs.errorContainer else cs.inverseSurface
    val fg = if (isError) cs.onErrorContainer else cs.inverseOnSurface
    Surface(
        shape = RoundedCornerShape(CornerMetrics.CARD_DP.dp),
        color = bg,
        contentColor = fg,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MagiSpacing.lg, vertical = MagiSpacing.sm),
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (isError) cs.error else cs.inversePrimary)) { Text(label) }
            }
        }
    }
}
