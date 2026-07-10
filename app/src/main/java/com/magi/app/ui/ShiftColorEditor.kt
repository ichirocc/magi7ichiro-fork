package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** "#rrggbb"/"#rgb" -> Compose Color。同パッケージ(ScheduleGrid)と共有。不正値はグレー。 */
internal fun hexToColor(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    val full = when (h.length) {
        3 -> buildString { h.forEach { append(it); append(it) } }
        6 -> h
        else -> "888888"
    }
    val v = full.toIntOrNull(16) ?: 0x888888
    return Color((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
}

private val COLOR_PALETTE = listOf(
    "#8195a8", "#a96bff", "#4fa89c", "#d7a13b", "#6fa56b", "#5fb3d4",
    "#a89a86", "#6e6354", "#9d8a64", "#c0563f", "#3f6fc0", "#7a4fd0",
)

/**
 * colors 移植: シフトの表示色設定。
 * shiftColors[kigou] の上書きを編集。既定はカテゴリ別の色（resolveShiftColor）。
 * 表示専用のため採点・エンジンに影響しない。勤務表グリッドに反映される。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftColorCard(ui: UiState, vm: MagiViewModel) {
    var target by remember { mutableStateOf<String?>(null) }
    var vioPicker by remember { mutableStateOf(false) }
    val shifts = vm.shiftColorList()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("シフトの表示色", style = MaterialTheme.typography.titleMedium)
            Text(
                "勤務表に表示される各シフトの色。タップして変更できます（既定はシフト種別ごとの色）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shifts.isEmpty()) {
                Text(
                    "（データ未読込）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // [校正] 縦長の一覧をやめ、スウォッチ＋記号のコンパクトなチップを折り返しグリッドに。
                //   カスタム色は枠色（primary）で「指定」を表現（テキスト列を削減＝冗長解消）。
                val cs = MaterialTheme.colorScheme
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shifts.forEach { sc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .border(if (sc.custom) 2.dp else 1.dp, if (sc.custom) cs.primary else cs.outline, MaterialTheme.shapes.medium)
                                .clickable(enabled = !ui.running) { target = sc.kigou }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Swatch(sc.hex, 24.dp)
                            Text(sc.kigou, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            // [違反色] 違反セルの枠・マーカー色をタップで変更（保存される）。空＝テーマの赤。
            Divider()
            Text("違反の色（必須違反）", style = MaterialTheme.typography.titleSmall)
            Text("必須違反（枠・マーカー・集計の不足）の基準色。種別ごとの色や要調整の色は 設定 → 詳細設定 → 違反種別の色 で変更できます。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val vc = MaterialTheme.colorScheme
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .border(if (ui.violationColorHex.isNotBlank()) 2.dp else 1.dp,
                        if (ui.violationColorHex.isNotBlank()) vc.primary else vc.outline, MaterialTheme.shapes.medium)
                    .clickable(enabled = !ui.running) { vioPicker = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Swatch(ui.violationColorHex.ifBlank { "#BA1A1A" }, 24.dp)
                Text(if (ui.violationColorHex.isBlank()) "既定（赤）・タップで変更" else "指定の色・タップで変更",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (vioPicker) {
        ColorPickerDialog(
            kigou = "違反",
            currentHex = ui.violationColorHex,
            onPick = { hex -> vm.setViolationColor(hex); vioPicker = false },
            onReset = { vm.resetViolationColor(); vioPicker = false },
            onClose = { vioPicker = false },
        )
    }
    target?.let { kg ->
        val current = shifts.firstOrNull { it.kigou == kg }
        ColorPickerDialog(
            kigou = kg,
            currentHex = current?.hex ?: "",
            onPick = { hex -> vm.setShiftColor(kg, hex); target = null },
            onReset = { vm.resetShiftColor(kg); target = null },
            onClose = { target = null },
        )
    }
}

@Composable
private fun Swatch(hex: String, sizeDp: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(sizeDp)
            .background(hexToColor(hex), MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), MaterialTheme.shapes.small),
    )
}

@Composable
internal fun ColorPickerDialog(
    kigou: String,
    currentHex: String,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { DialogConfirmButton("閉じる", onClick = onClose) },
        dismissButton = { DialogDismissButton(onClick = onReset, text = "既定に戻す") },
        title = { DialogHeader("「$kigou」の色", onClose) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(currentHex, 28.dp)
                    Text("  現在の色", style = MaterialTheme.typography.bodyMedium)
                }
                Text("色を選ぶ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                COLOR_PALETTE.chunked(5).forEach { rowColors ->
                    // [不具合修正] 固定40dp×6＋余白がダイアログ幅を超え、6個目が切れて「大きさ不揃い」だった。
                    //   幅いっぱいを等分(weight)＋正方形(aspectRatio)にし、全スウォッチ均等・切れ無し・タップ可に。
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { hex ->
                            val selected = hex.equals(currentHex, ignoreCase = true)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(hexToColor(hex), RoundedCornerShape(8.dp))
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onPick(hex) }
                                    // [a11y] 色のみの選択肢に読み上げ名を付与。
                                    .semantics { contentDescription = "色 $hex" + (if (selected) "・選択中" else "") },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Text("✓", color = hexToColor(pickFg(hex)), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        },
    )
}

/** 簡易: 明度から文字色を選ぶ（チェック印用）。 */
private fun pickFg(bgHex: String): String {
    val h = bgHex.trim().removePrefix("#")
    val v = (if (h.length == 6) h else "888888").toIntOrNull(16) ?: 0x888888
    val r = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val b = v and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b)
    return if (lum > 140) "#14110d" else "#fbf4e8"
}

