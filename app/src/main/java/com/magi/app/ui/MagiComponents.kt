package com.magi.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription

/**
 * MAGI 共通コンポーネント（docs/magi_design_system.md §4）。
 * Material3 を一次ソースとし、添付テイスト（角丸・余白・大数値・セグメント）を素直に表現する。
 * 指1本操作前提: タップ標的は最小 48dp、セグメントは最小 44dp 高。
 */

/** §4.5 セグメントコントロール（外観モード / 制約レベル / カレンダー表示切替）。 */
@Composable
fun MagiSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cs.surfaceVariant,
        shape = MagiCorners.capsule,
    ) {
        Row(
            Modifier.padding(4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { i, label ->
                val active = i == selected
                Surface(
                    onClick = { onSelect(i) },
                    // [a11y/touch] Surface(onClick) は 48dp 自動補完が無いため明示。選択状態を読み上げに公開。
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = active },
                    // [Planner テイスト] 選択中はやわらかい色付きピル（白の段差でなく穏やかな塗り）。
                    color = if (active) cs.primaryContainer else Color.Transparent,
                    contentColor = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    shape = MagiCorners.capsule,
                    tonalElevation = 0.dp,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            // [レイアウト整合] 狭幅(320dp)/フォント拡大でも制御ラベルが硬クリップせず省略表示へ退避。左右に微小padding。
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** §4.6 中央大数値ゲージ（満足度 / 総合スコア）。Canvas を使わず大数値＋線で軽量に表現。 */
@Composable
fun MagiScoreGauge(
    score: Int,
    max: Int = 100,
    label: String,
    sub: String? = null,
    accent: Color? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tint = accent ?: cs.primary
    val ratio = if (max > 0) (score.toFloat() / max).coerceIn(0f, 1f) else 0f
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$score", style = MaterialTheme.typography.displaySmall, color = tint, fontWeight = FontWeight.Bold)
            Text(" / $max", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp),
            color = tint,
            trackColor = cs.surfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, textAlign = TextAlign.Center)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** §4.8 カテゴリタグ（軽い色付きピル）。色は意味色（MagiAccent）やシフト色を渡す。 */
@Composable
fun MagiTagChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    // [UD監査] 淡い塗り(α0.14)上の生アクセント文字は 橙2.7:1/緑3.4:1 で不足。実効背景（白地に合成後）に
    //   対して WCAG を保証（不足時は自動で濃色へ）。
    val chipBg = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface)
    Surface(
        modifier = modifier,
        // [Planner テイスト] やわらかい塗り＋細い同系ボーダーで、クリーム地でも輪郭がやさしく締まるピル。
        color = color.copy(alpha = 0.14f),
        contentColor = ensureReadable(chipBg, color),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.heightIn(min = 18.dp)) }
            // [判読性] タグチップ文字を 13sp→15sp(labelLarge/SemiBold)へ。
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 補助の操作（塗り＞外枠＞これ の 3 段の最下段）。文字だけではボタンに見えなかったので薄い背景を敷く（2026-10-23 ユーザー決定）。 */
@Composable
internal fun MagiTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurface
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, contentPadding = contentPadding,
        colors = ButtonDefaults.textButtonColors(containerColor = tint.copy(alpha = 0.08f), contentColor = contentColor, disabledContainerColor = tint.copy(alpha = 0.04f)),
        content = content)
}

/** 1 枚のカード、または親のカードの中に入れるときは枠なし（[bare]）。中身の余白は [content] に渡す（枠なしは 0）。 */
@Composable
internal fun MagiCardOrPlain(bare: Boolean, content: @Composable (PaddingValues) -> Unit) {
    if (bare) content(PaddingValues(0.dp))
    else Card(Modifier.fillMaxWidth()) { content(PaddingValues(16.dp)) }
}

/**
 * iOS の設定アプリの一覧の形（2026-10-23）: 入れ物の外に小さな見出し、入れ物の中は同じ高さの行を細い線で区切る、下に注記。
 * 不透明・影なし（UD 固定・DESIGN P3）。行は [MagiGroupRow]。
 */
@Composable
internal fun MagiGroup(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp).semantics { heading() })
        Surface(color = cs.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Column(content = content) }
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** 一覧の 1 行（押すと [onClick]）。右端に「›」（開閉の行は ▸／▾）。最後の行は [divider]=false。 */
@Composable
internal fun MagiGroupRow(label: String, enabled: Boolean = true, trailing: String = "›", divider: Boolean = true, color: Color = Color.Unspecified,
                          open: Boolean? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (open != null) Modifier.semantics { stateDescription = disclosureState(open) } else Modifier).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                color = when { !enabled -> cs.onSurface.copy(alpha = 0.38f); color.isSpecified -> color; else -> cs.primary })
            Text(trailing, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })   // 印は読み上げない
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = cs.outlineVariant)
    }
}

/**
 * 問題の 1 行（2026-10-29 画面案）: 先頭の印 [lead]、太字の名前 [title]（null なら [sub] だけ）、その下に理由 [sub]、右に札 [tag] と [trailing]。
 * 行全体を押せる。読み上げは名前・理由・札をまとめて 1 つ（印と › は読まない）。
 */
@Composable
internal fun ProblemItemRow(title: String?, sub: String, tag: String? = null, tagColor: Color = Color.Unspecified, divider: Boolean = true,
                            selected: Boolean = false, lead: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {
                                Text("›", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
                            }, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val spoken = listOfNotNull(title, sub.takeIf { it.isNotBlank() }, tag).joinToString("、")
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (selected) cs.primaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clearAndSetSemantics { }) { lead() }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = spoken }) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, style = if (title != null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = if (title != null) cs.onSurfaceVariant else cs.onSurface, maxLines = a11yMaxLines(LocalDensity.current.fontScale), overflow = TextOverflow.Ellipsis)
            }
            if (tag != null) Text(tag, style = MaterialTheme.typography.labelMedium, color = if (tagColor.isSpecified) tagColor else cs.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { })
            trailing()
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 12.dp), color = cs.outlineVariant)
    }
}

/** 問題の印（丸に「!」。必須は赤、要調整は [color] に橙を渡す）。 */
@Composable
internal fun HardMark(color: Color = MaterialTheme.colorScheme.error) {
    Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text("!", style = MaterialTheme.typography.labelLarge, color = ensureReadable(color, Color.White), fontWeight = FontWeight.Bold)
    }
}

/** カレンダーの日付の数字（iOS のカレンダーの形、2026-10-24）: 選んだ日は塗った丸に白い数字。枠や四角の塗りは使わない。 */
@Composable
internal fun CalendarDayNumber(day: Int, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    // 文字の拡大で数字が丸からはみ出さないよう、大きさは最小 32dp（固定にしない）。
    Box(Modifier.defaultMinSize(32.dp, 32.dp).then(if (selected) Modifier.background(cs.primary, CircleShape) else Modifier).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text("$day", style = MaterialTheme.typography.bodyLarge, color = if (selected) cs.onPrimary else cs.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** 開閉の状態の読み上げ（▸／▾ の印は読み上げに渡さない）。 */
internal fun disclosureState(open: Boolean): String = if (open) "開いています" else "閉じています"

/**
 * 開閉や行き先の印つきの文字（2026-10-24 視覚弱点者対応）。見た目は「{label} ▸／▾」、読み上げは [label] と状態だけ
 * （記号は「右向きの三角」などと読まれて意味が通らない）。[open] が null なら行き先の印 ▸ で状態は読まない。
 */
@Composable
internal fun DisclosureText(label: String, open: Boolean?, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) {
    Row(if (open != null) Modifier.semantics { stateDescription = disclosureState(open) } else Modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(if (open == true) " ▾" else " ▸", style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
    }
}
