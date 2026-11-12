package com.magi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * 島（inset grouped list、2026-11-11「アップル社の最新のアプリのインターフェースやデザインを借りる」→ 配置と構造だけ・色は UD のまま）。
 * 入れ物・見出し・注記は既存の [MagiGroup] と同じ（設定の一覧と同じ見た目）。行の間に細い区切り線を入れる。
 */
@Composable
internal fun GroupedSection(header: String?, footer: String? = null, rows: List<@Composable () -> Unit>, inCard: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    // カードの中では入れ物を重ねない（箱の中の箱・字下げの二重を避ける）＝行と区切り線だけ、行の左右の余白はカードの余白に任せる。
    if (inCard) {
        CompositionLocalProvider(LocalGroupedInset provides 0.dp) {
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    row()
                }
            }
        }
        return
    }
    MagiGroup(header?.takeIf { it.isNotBlank() }, footer?.takeIf { it.isNotBlank() }) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = cs.outlineVariant)
            row()
        }
    }
}

/** 島の行の左の余白（島の中は 16dp、カードの中は 0＝カードの余白と重ねない）。 */
internal val LocalGroupedInset = staticCompositionLocalOf { 16.dp }

/**
 * 島の 1 行。[onClick] があれば行全体（[trailing] を除く）が押せて、右端に「›」（このアプリの「押せる」の印）。
 * [trailing] は「›」の右の小さな操作（削除など）。押し間違えないよう 12dp 空ける（ux_test_checklist A3 は 8dp 以上）。
 */
@Composable
internal fun GroupedRow(text: String, enabled: Boolean, onClick: (() -> Unit)?, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
                .padding(start = LocalGroupedInset.current, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            if (onClick != null) Text("›", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 島のいちばん下の「＋ ○○」の行（主色の文字＝島の中の追加）。 */
@Composable
internal fun GroupedAddRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = LocalGroupedInset.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** 島のいちばん下の赤い文字の行（まとめて消すなど。押した時点で実行＝元に戻すで取り消す）。 */
@Composable
internal fun GroupedDestructiveRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = LocalGroupedInset.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) cs.error else cs.onSurface.copy(alpha = 0.38f))
    }
}

/** 島の行の右端の「削除」（赤い文字・押せる大きさ 48dp。確認なしで即時は従来どおり）。 */
@Composable
internal fun GroupedDeleteButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("削除", color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

/**
 * 1 つを選ぶ一覧（Apple の設定の「チェックの付く行」）。選択肢が 4 つ以上か、語が長くて区切りの切替に入らないときに使う。
 * 行は 48dp・選んだ行の右端に「✓」・行の間は細線。2〜3 択の短い語は [MagiSegmentedControl]。
 */
@Composable
internal fun ChoiceRows(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        options.forEachIndexed { i, label ->
            if (i > 0) HorizontalDivider(color = cs.outlineVariant)
            val active = i == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(i) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f))
                if (active) Text("✓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f), modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
