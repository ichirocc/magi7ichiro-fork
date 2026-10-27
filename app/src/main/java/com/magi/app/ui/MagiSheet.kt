package com.magi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** シートの段の語（読み上げ・押したときの動き）。 */
internal fun sheetGrabberLabels(expanded: Boolean): Pair<String, String> =
    if (expanded) "全体" to "半分にする" else "半分" to "全体に広げる"

/**
 * 下から出るシートの上端のつまみ（iOS のシートの段）。半分で開き、つまみをタップすると全体⇄半分。
 * 引き上げ・引き下げも使えるが、片手一本指（ドラッグ不可）でも段を変えられるようにタップで切り替える。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MagiSheetGrabber(state: SheetState) {
    val scope = rememberCoroutineScope()
    val expanded = state.currentValue == SheetValue.Expanded
    val (now, action) = sheetGrabberLabels(expanded)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = action, role = Role.Button) {
                scope.launch { runCatching { if (expanded) state.partialExpand() else state.expand() } }
            }
            .semantics { stateDescription = now },
        contentAlignment = Alignment.Center,
    ) { BottomSheetDefaults.DragHandle() }
}
