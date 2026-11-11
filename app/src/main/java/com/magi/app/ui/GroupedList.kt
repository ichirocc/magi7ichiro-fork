package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 島（Apple の inset grouped list の配置を借りたもの、2026-11-11 ユーザー「アップル社の最新のアプリのインターフェースやデザインを借りる」
 * → 配置と構造だけ・色は UD トークンのまま）。角の丸い面に行を並べ、行の間は細い区切り線。見出しは島の外の上（小さく控えめ）、
 * 説明は外の下。角はカードと同じ [CornerMetrics.CARD_DP]。押せる大きさは 48dp のまま（片手一本指）。
 */
@Composable
internal fun GroupedSection(header: String?, footer: String? = null, rows: List<@Composable () -> Unit>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (!header.isNullOrBlank()) {
            Text(header, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp).semantics { heading() })
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CornerMetrics.CARD_DP.dp)).background(cs.surface)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 1.dp, color = cs.outlineVariant)
                row()
            }
        }
        if (!footer.isNullOrBlank()) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

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
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = if (onClick == null) cs.onSurfaceVariant else cs.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            if (onClick != null) Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
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
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** 島の行の右端の「削除」（赤い文字・押せる大きさ 48dp。確認なしで即時は従来どおり）。 */
@Composable
internal fun GroupedDeleteButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("削除", color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}
