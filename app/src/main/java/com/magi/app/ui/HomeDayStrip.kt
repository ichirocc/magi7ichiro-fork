package com.magi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val DAY_STRIP_TITLE = "日ごとのようす"

/**
 * iOS の天気の「時間ごとの帯」にならった日ごとの印（ホーム）。日付・曜日の下に印＝人員不足の日は赤の丸、必須違反のセルがある日は数。
 * 横スクロールを必須にしない（operator_ux）ので、問題のある日だけを折り返して並べ、問題の無い日は数で言う。押すとその日の勤務表へ。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeDayStrip(ui: UiState, onDay: (Int) -> Unit) {
    val marks = remember(ui.days, ui.startDate, ui.needViolations, ui.needFamilies, ui.violationCellFamilies) { dayStripMarks(ui) }
    if (marks.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    MagiCardOrPlain(false) { pad ->
        Column(Modifier.padding(pad)) {
            Text(DAY_STRIP_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(dayStripSummary(marks), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in marks.filterNot { it.ok }) {
                    Column(
                        Modifier.widthIn(min = 44.dp).heightIn(min = 48.dp).clickable { onDay(m.day) }
                            .clearAndSetSemantics { contentDescription = dayMarkSpoken(m) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(m.dow, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(m.dom, style = MaterialTheme.typography.labelLarge)
                        Box(Modifier.padding(top = 4.dp).size(20.dp), contentAlignment = Alignment.Center) {
                            when {
                                m.short -> Surface(shape = CircleShape, color = cs.error, modifier = Modifier.size(14.dp)) {}
                                m.hardCells > 0 -> Text("${m.hardCells}", style = MaterialTheme.typography.labelMedium, color = cs.error)
                                else -> Surface(shape = CircleShape, color = cs.outlineVariant, modifier = Modifier.size(6.dp)) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [iOS のヘルスケア「ハイライト」] この作成の前後を 1 行（作成のあと、変化があったときだけ）。 */
@Composable
internal fun RunDeltaHighlight(line: String) {
    MagiCardOrPlain(false) { pad ->
        Column(Modifier.padding(pad)) {
            Text(RUN_DELTA_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
