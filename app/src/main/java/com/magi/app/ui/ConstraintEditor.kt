package com.magi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.magi.app.v6.V6SanityPort
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * ws3-5 constraint editor, rendered as a card in the single-scroll layout. Adding or
 * removing a constraint rebuilds state and re-runs the unified violation check
 * (see MagiViewModel.mutateConstraints -> refreshCheck), so the breakdown/score/v6
 * panels above update automatically. Edited constraints are written back on JSON save.
 */
@Composable
internal fun ConstraintsCard(
    ui: UiState,
    cv: ConstraintsView,
    onEvent: (MagiEvent) -> Unit,
    title: String = "ルールの編集（勤務の並び・回数）",
    keys: Set<String>? = null,
) {
    // [3.482.0] 追加ダイアログの対象 (族, 1番目のプリセット)。並び族の「○○の次を追加」は起点をプリセットして開く。
    var addTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }
    // [制約編集] 行タップで既存行を変更（追加ダイアログのプリフィル版）。実機指摘「登録した制約の変更ができない」。
    var editTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // [発見性] keys 指定時はその family だけ描画。群の C41/C42 を専用節に分けて見つけやすくするため。
    val families = cv.families.let { all -> if (keys == null) all else all.filter { it.key in keys } }

    // [Apple の配置] 族ごとに島（見出しは島の外の上、行の間は細い区切り線、いちばん下に「＋ 追加」）。色は UD トークンのまま。
    Column(Modifier.fillMaxWidth()) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        Box(Modifier.padding(horizontal = 16.dp)) { ConstraintHelpExpander(families) }
        families.forEach { fam ->
            val enabled = !ui.running
            val seq = fam.key.startsWith("cons3") && fam.key != "cons3w"
            val rows = ArrayList<@Composable () -> Unit>()
            if (fam.rows.isEmpty()) {
                rows += { GroupedRow("(なし)", enabled = false, onClick = null) }
            } else if (seq) {
                // [3.482.0 編集タブ簡素化] 並び4族は「起点シフトごとのチップ」に集約（データは C3Row のまま＝表示の集約のみ）。
                rows += seqFamilyRows(fam, cv, enabled,
                    onEdit = { idx -> editTarget = fam.key to idx },
                    onDelete = { idx -> onEvent(MagiEvent.Constraint.Remove(fam.key, idx)) },
                    onAddWith = { first -> addTarget = fam.key to first })
            } else {
                fam.rows.forEachIndexed { idx, row ->
                    rows += { ConstraintRow(row, enabled = enabled, onEdit = { editTarget = fam.key to idx },
                        onDelete = { onEvent(MagiEvent.Constraint.Remove(fam.key, idx)) }) }
                }
            }
            // [3.483.0 E-10] 並び系は起点ごとに「＋」があるため、末尾の追加は「新しい起点で追加」と区別。
            rows += { GroupedAddRow(if (seq) "新しい起点で追加する" else "追加する", enabled = enabled) { addTarget = fam.key to null } }
            GroupedSection(fam.title, rows = rows)
        }
    }

    addTarget?.let { (fam, first) -> ConstraintDialog(fam, cv, onEvent, presetFirst = first, onClose = { addTarget = null }) }
    editTarget?.let { (k, i) -> ConstraintDialog(k, cv, onEvent, editIndex = i, onClose = { editTarget = null }) }
}

/**
 * [3.482.0 編集タブ簡素化] 並び族（必須/禁止/推奨/回避）を**起点シフトごと**にまとめて表示する。
 * 「【Dﾃ の次の日】 [B4 ×][A4 ×]… ＋追加」の形＝ユーザー提示案の画面2。
 * - 2連（起点→次）はチップ。タップ=変更、× =削除（どちらも従来の行操作と同じ vm API）。
 * - 3連以上の並びは従来の行表示のまま（チップで表せない）。起点の見出しの下に並べる。
 * - 1つだけの並びは起点の見出しに入れず「X（1つだけ）」の行で先頭に並べる。
 * - 「○○の次を追加」（＋ はボタンのアイコン）は追加ダイアログを起点プリセットで開く（ダイアログ側の重複ガードが効く）。
 * データは `C3Row` のまま（表示の集約だけ）。族の見出し・詳しい説明・削除確認の意味論も不変。
 */
@OptIn(ExperimentalLayoutApi::class)
private fun seqFamilyRows(
    fam: ConstraintFamilyView, cv: ConstraintsView, enabled: Boolean,
    onEdit: (Int) -> Unit, onDelete: (Int) -> Unit, onAddWith: (String) -> Unit,
): List<@Composable () -> Unit> {
    val out = ArrayList<@Composable () -> Unit>()
    // 行の生パターン（追加ダイアログと同じ正規化=先頭から最初の空白まで・最大5）。
    val pats = fam.rows.indices.map { idx -> normalizeSeq(cv.rowValues(fam.key, idx) ?: emptyList()) }
    // 1つだけの行は「X の次の日」の見出しに入れない（X→X と読めてしまう）。
    for (idx in pats.indices.filter { pats[it].size == 1 }) {
        out += {
            Column {
                ConstraintRow("${pats[idx][0]}（1つだけ）", enabled = enabled, onEdit = { onEdit(idx) }, onDelete = { onDelete(idx) })
                if (fam.key == "cons3n") Text(singleForbiddenNote(pats[idx][0]), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }
    }
    val firsts = pats.filter { it.size >= 2 }.map { it[0] }.distinct()
    for (first in firsts) {
        val idxs = pats.indices.filter { pats[it].size >= 2 && pats[it][0] == first }
        val pairIdxs = idxs.filter { pats[it].size == 2 }
        val otherIdxs = idxs.filter { pats[it].size != 2 }
        out += {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // [3.483.0 E-11] 見出しに族の意味（禁止/必須/推奨/回避）を含める。[3.515.4] 本文サイズの太字（DESIGN.md §3.3）。
                Text(seqStartHeading(fam.key, first), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                // 守る/推奨は行ごとに別に数える＝同じ起点に2本あると、どちらかは必ず崩れる。
                if (pairIdxs.size >= 2 && (fam.key == "cons3" || fam.key == "cons3m")) {
                    Text("それぞれ別に数えます（どれか1つでよい、ではありません）", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pairIdxs.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (idx in pairIdxs) {
                            val next = pats[idx][1]
                            // [a11y] 本体のタップは変更、× は削除（行操作と同じ 2 操作を保つ）。
                            RemovableChip(next, enabled, onClick = { onEdit(idx) }, onRemove = { onDelete(idx) }, removeDescription = "$first→$next を削除")
                        }
                    }
                }
            }
        }
        for (idx in otherIdxs) out += { ConstraintRow(fam.rows[idx], enabled = enabled, onEdit = { onEdit(idx) }, onDelete = { onDelete(idx) }) }
        out += { GroupedAddRow("$first の次を追加", enabled = enabled) { onAddWith(first) } }
    }
    return out
}

/**
 * [3.409.14] 既定で閉じた「ⓘ 詳しい説明」。本文は [constraintHelp]（Compose 非依存＝ホストの
 * ConstraintHelpTest が族との過不足を固定）から、見出しは表示中の families から取る＝カードの
 * 一覧と同じソースなので、族の改名・追加でここだけ古くなることがない。
 * 常時表示にしないのは 3.129.0/3.396.0 の方針（説明文は読まれない・貼り紙で形を補わない）と、
 * ユーザー指示「詳しい説明をアプリにも」を両立させるため＝読みたい人がタップしたときだけ全文を出す。
 */
@Composable
private fun ConstraintHelpExpander(families: List<ConstraintFamilyView>) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DisclosureText(if (open) "ⓘ 詳しい説明" else "ⓘ 詳しい説明（それぞれの条件の意味）", open,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                families.forEach { fam ->
                    constraintHelp[fam.key]?.let { body ->
                        Column {
                            Text(fam.title, style = MaterialTheme.typography.titleSmall)
                            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(CONSTRAINT_HELP_FOOTER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 制約1行: 行のタップ=変更（右端の「›」）、右端の赤い「削除」。[3.396.0] 触れる大きさだけでは押せると読めない
 * （貼り紙が要った・3.130.0「変更ができない」）→ 押せる印を形で示す。旧「編集」「削除」の 2 ボタンは続く行で上下に接した。
 */
@Composable
private fun ConstraintRow(row: String, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, editable: Boolean = true) {
    GroupedRow(row, enabled = enabled, onClick = if (editable) onEdit else null) { GroupedDeleteButton(enabled = enabled, onClick = onDelete) }
}

/** [校正] スキルグループの C41/C42（cons41s/cons42s）をスキルグループ定義の直下に co-locate。
 *  汎用ルール（ユニット群）と混ざって埋もれていた問題を解消し、見つけやすくする。 */
@Composable
internal fun SkillConstraintsCard(ui: UiState, cv: ConstraintsView, onEvent: (MagiEvent) -> Unit) {
    var addFamily by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val families = cv.skillFamilies
    val enabled = !ui.running
    Column(Modifier.fillMaxWidth()) {
        // [3.427.0] カードの識別に要る1文だけ（族の列挙は直下の見出しと重複）。
        Text("上の「スキルグループ」に対する専用ルールです。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (cv.skillGroupKigou.isEmpty()) {
            Text("先に上で「スキルグループ」を追加すると設定できます。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            // スキルグループが無くても行は残る（未評価・設定の見直しが削除を促す）＝削除だけできるように出す。
            val orphans = families.filter { it.rows.isNotEmpty() }
            if (orphans.isNotEmpty()) {
                Text("スキルグループが無いため、次のルール${orphans.sumOf { it.rows.size }}件は使われていません。要らなければ削除できます。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                orphans.forEach { fam ->
                    GroupedSection(fam.title, rows = fam.rows.mapIndexed { idx, row ->
                        @Composable { ConstraintRow(row, enabled = enabled, onEdit = {}, editable = false,
                            onDelete = { onEvent(MagiEvent.Constraint.Remove(fam.key, idx)) }) }
                    })
                }
            }
        } else {
            Box(Modifier.padding(horizontal = 16.dp)) { ConstraintHelpExpander(families) }
            families.forEach { fam ->
                val rows = ArrayList<@Composable () -> Unit>()
                if (fam.rows.isEmpty()) rows += { GroupedRow("(なし)", enabled = false, onClick = null) }
                fam.rows.forEachIndexed { idx, row ->
                    rows += { ConstraintRow(row, enabled = enabled, onEdit = { editTarget = fam.key to idx },
                        onDelete = { onEvent(MagiEvent.Constraint.Remove(fam.key, idx)) }) }
                }
                rows += { GroupedAddRow("追加する", enabled = enabled) { addFamily = fam.key } }
                GroupedSection(fam.title, rows = rows)
            }
        }
    }
    val fam = addFamily
    if (fam != null) ConstraintDialog(fam, cv, onEvent, onClose = { addFamily = null })
    editTarget?.let { (k, i) -> ConstraintDialog(k, cv, onEvent, editIndex = i, onClose = { editTarget = null }) }
}

/** 追加・変更を兼ねる制約ダイアログ。editIndex 指定時は既存行の値をプリフィルし、確定で同じ位置を置換。 */
@Composable
private fun ConstraintDialog(
    family: String, cv: ConstraintsView, onEvent: (MagiEvent) -> Unit,
    editIndex: Int? = null, presetFirst: String? = null, onClose: () -> Unit,
) {
    val shifts = cv.shiftKigou
    val groups = cv.groupKigou
    val skills = cv.skillGroupKigou
    val shiftsOpt = listOf("") + shifts
    val init = remember(family, editIndex) { editIndex?.let { cv.rowValues(family, it) } }
    val mode = if (editIndex != null) "を変更" else "を追加"
    val okLabel = if (editIndex != null) "変更する" else "追加する"
    fun commit(values: List<String>, add: () -> Unit) {
        if (editIndex != null) onEvent(MagiEvent.Constraint.Update(family, editIndex, values)) else add()
        onClose()
    }
    fun dup(values: List<String>) = cv.rowDuplicate(family, values, excludeIndex = editIndex)

    when (family) {
        "cons1" -> {
            var d1 by remember { mutableStateOf(init?.getOrNull(0) ?: "") }
            var sk by remember { mutableStateOf(init?.getOrNull(1) ?: shifts.firstOrNull() ?: "") }
            var d2 by remember { mutableStateOf(init?.getOrNull(2) ?: "") }
            val err = cons1InputError(d1, d2, cv.dayCount)
            val isDup = dup(listOf(d1, sk, d2))
            Shell("期間の制約$mode", okLabel, onClose, { commit(listOf(d1, sk, d2)) { onEvent(MagiEvent.Constraint.AddCons1(d1, sk, d2)) } },
                d1.isNotBlank() && sk.isNotBlank() && d2.isNotBlank() && err == null && !isDup, dirty = listOf(d1, sk, d2) != remember { listOf(d1, sk, d2) }) {
                NumField("何日間", d1, isError = err != null) { d1 = it }
                Picker("シフト", shifts, sk) { sk = it }
                NumField("必要数(以上)", d2, isError = err != null) { d2 = it }
                InputHint(err ?: DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons2" -> {
            var sk by remember { mutableStateOf(init?.getOrNull(0) ?: shifts.firstOrNull() ?: "") }
            var c by remember { mutableStateOf(init?.getOrNull(1) ?: "") }
            val err = cons2InputError(c, cv.dayCount)
            val isDup = dup(listOf(sk, c))
            Shell("個人の合計$mode", okLabel, onClose, { commit(listOf(sk, c)) { onEvent(MagiEvent.Constraint.AddCons2(sk, c)) } },
                sk.isNotBlank() && c.isNotBlank() && err == null && !isDup, dirty = listOf(sk, c) != remember { listOf(sk, c) }) {
                Picker("シフト", shifts, sk) { sk = it }
                NumField("合計(以上)", c, isError = err != null) { c = it }
                InputHint(err ?: DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons41" -> {
            var gk by remember { mutableStateOf(init?.getOrNull(0) ?: groups.firstOrNull() ?: "") }
            var sk by remember { mutableStateOf(init?.getOrNull(1) ?: shifts.firstOrNull() ?: "") }
            var l by remember { mutableStateOf(init?.getOrNull(2) ?: "") }
            var u by remember { mutableStateOf(init?.getOrNull(3) ?: "") }
            // [3.403.0] 下限>上限は engine の `z < l || z > u` で**どの人数でも必ず違反**＝期間の全日が違反になる。
            //   事後診断(V6SanityPort 検査2f)は出していたが、画面は素通しで確定できた＝入力時に止める。
            val bad = V6SanityPort.rangeOrderConflict(l, u) != null
            val blank = rangeBothBlank(l, u)
            val isDup = dup(listOf(gk, sk, l, u))
            Shell("グループのレンジ（1日の人数）$mode", okLabel, onClose, { commit(listOf(gk, sk, l, u)) { onEvent(MagiEvent.Constraint.AddCons41(gk, sk, l, u)) } },
                gk.isNotBlank() && sk.isNotBlank() && !bad && !blank && !isDup, dirty = listOf(gk, sk, l, u) != remember { listOf(gk, sk, l, u) }) {
                Picker("グループ", groups, gk) { gk = it }
                Picker("シフト", shifts, sk) { sk = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("下限(空=0)", l, Modifier.weight(1f), isError = bad) { l = it }
                    NumField("上限(空=無制限)", u, Modifier.weight(1f), isError = bad) { u = it }
                }
                InputHint(if (bad) RANGE_ORDER_HINT else if (blank) RANGE_BOTH_BLANK_HINT else DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons42" -> {
            var g1 by remember { mutableStateOf(init?.getOrNull(0) ?: groups.firstOrNull() ?: "") }
            var s1 by remember { mutableStateOf(init?.getOrNull(1) ?: shifts.firstOrNull() ?: "") }
            var g2 by remember { mutableStateOf(init?.getOrNull(2) ?: groups.firstOrNull() ?: "") }
            var s2 by remember { mutableStateOf(init?.getOrNull(3) ?: shifts.firstOrNull() ?: "") }
            val isDup = dup(listOf(g1, s1, g2, s2))
            Shell("グループペア禁止$mode", okLabel, onClose, { commit(listOf(g1, s1, g2, s2)) { onEvent(MagiEvent.Constraint.AddCons42(g1, g2, s1, s2)) } },
                g1.isNotBlank() && s1.isNotBlank() && g2.isNotBlank() && s2.isNotBlank() && !isDup, dirty = listOf(g1, s1, g2, s2) != remember { listOf(g1, s1, g2, s2) }) {
                Picker("グループ1", groups, g1) { g1 = it }
                Picker("シフト1", shifts, s1) { s1 = it }
                Picker("グループ2", groups, g2) { g2 = it }
                Picker("シフト2", shifts, s2) { s2 = it }
                InputHint(DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons41s" -> {
            var gk by remember { mutableStateOf(init?.getOrNull(0) ?: skills.firstOrNull() ?: "") }
            var sk by remember { mutableStateOf(init?.getOrNull(1) ?: shifts.firstOrNull() ?: "") }
            var l by remember { mutableStateOf(init?.getOrNull(2) ?: "") }
            var u by remember { mutableStateOf(init?.getOrNull(3) ?: "") }
            val bad = V6SanityPort.rangeOrderConflict(l, u) != null   // [3.403.0] cons41 と同じ（群かスキル群かの違いだけ）
            val blank = rangeBothBlank(l, u)
            val isDup = dup(listOf(gk, sk, l, u))
            Shell("スキルグループのレンジ（1日の人数）$mode", okLabel, onClose, { commit(listOf(gk, sk, l, u)) { onEvent(MagiEvent.Constraint.AddCons41s(gk, sk, l, u)) } },
                gk.isNotBlank() && sk.isNotBlank() && !bad && !blank && !isDup, dirty = listOf(gk, sk, l, u) != remember { listOf(gk, sk, l, u) }) {
                Picker("スキルグループ", skills, gk) { gk = it }
                Picker("シフト", shifts, sk) { sk = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("下限(空=0)", l, Modifier.weight(1f), isError = bad) { l = it }
                    NumField("上限(空=無制限)", u, Modifier.weight(1f), isError = bad) { u = it }
                }
                InputHint(if (bad) RANGE_ORDER_HINT else if (blank) RANGE_BOTH_BLANK_HINT else DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons42s" -> {
            var g1 by remember { mutableStateOf(init?.getOrNull(0) ?: skills.firstOrNull() ?: "") }
            var s1 by remember { mutableStateOf(init?.getOrNull(1) ?: shifts.firstOrNull() ?: "") }
            var g2 by remember { mutableStateOf(init?.getOrNull(2) ?: skills.firstOrNull() ?: "") }
            var s2 by remember { mutableStateOf(init?.getOrNull(3) ?: shifts.firstOrNull() ?: "") }
            val isDup = dup(listOf(g1, s1, g2, s2))
            Shell("スキルグループペア禁止$mode", okLabel, onClose, { commit(listOf(g1, s1, g2, s2)) { onEvent(MagiEvent.Constraint.AddCons42s(g1, g2, s1, s2)) } },
                g1.isNotBlank() && s1.isNotBlank() && g2.isNotBlank() && s2.isNotBlank() && !isDup, dirty = listOf(g1, s1, g2, s2) != remember { listOf(g1, s1, g2, s2) }) {
                Picker("スキルグループ1", skills, g1) { g1 = it }
                Picker("シフト1", shifts, s1) { s1 = it }
                Picker("スキルグループ2", skills, g2) { g2 = it }
                Picker("シフト2", shifts, s2) { s2 = it }
                InputHint(DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons3w" -> {
            var x by remember { mutableStateOf(init?.getOrNull(0) ?: shifts.firstOrNull() ?: "") }
            var y by remember { mutableStateOf(init?.getOrNull(1) ?: shifts.firstOrNull() ?: "") }
            val isDup = dup(listOf(x, y))
            Shell("希望の前日に禁止$mode", okLabel, onClose, { commit(listOf(x, y)) { onEvent(MagiEvent.Constraint.AddCons3w(x, y)) } },
                x.isNotBlank() && y.isNotBlank() && !isDup, dirty = listOf(x, y) != remember { listOf(x, y) }) {
                Picker("希望シフト（希望で固定されたもの）", shifts, x) { x = it }
                Picker("その前日に置けないシフト", shifts, y) { y = it }
                InputHint(DUPLICATE_ROW_HINT.takeIf { isDup })
            }
        }
        "cons3", "cons3n", "cons3m", "cons3mn" -> {
            // [3.482.0] 「○○の次を追加」から開いたときは1番目を起点でプリセット（editIndex と排他）。
            var a by remember { mutableStateOf(init?.getOrNull(0) ?: presetFirst ?: shifts.firstOrNull() ?: "") }
            var b by remember { mutableStateOf(init?.getOrNull(1) ?: "") }
            var c by remember { mutableStateOf(init?.getOrNull(2) ?: "") }
            var d by remember { mutableStateOf(init?.getOrNull(3) ?: "") }
            var e by remember { mutableStateOf(init?.getOrNull(4) ?: "") }
            val kind = seqFamilyJp(family)
            // [3.482.0 入口ガード] 同じ並びが既にあれば OK を無効化し、どの族に登録済みかを枠の下で言う
            //   （族をまたぐ同一の並びも対象＝禁止と回避に同じ並びを二重掛けしても回避側は無意味）。
            val dupFam = cv.duplicateOf(family, listOf(a, b, c, d, e), excludeIndex = editIndex)
            // 途中の空欄は後ろが黙って切れる（1番目だけの禁止になりうる）＝CSV 取込と同じく断る。
            val gap = seqHasGap(listOf(a, b, c, d, e))
            Shell(kind + mode, okLabel, onClose, { commit(listOf(a, b, c, d, e)) { onEvent(MagiEvent.Constraint.AddCons3(family, listOf(a, b, c, d, e))) } },
                a.isNotBlank() && dupFam == null && !gap, dirty = listOf(a, b, c, d, e) != remember { listOf(a, b, c, d, e) }) {
                Text("並び (上から順・最大5連日 / 空=ここで終了)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dupFam != null) {
                    Text("この並びは「$dupFam」に登録済みです。並びを変えると確定できます。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                InputHint("途中に空欄があります。上から詰めて選んでください".takeIf { gap })
                if (family == "cons3n" && a.isNotBlank() && b.isBlank() && !gap) {
                    Text(singleForbiddenNote(a), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Picker("1番目", shifts, a) { a = it }
                Picker("2番目", shiftsOpt, b) { b = it }
                Picker("3番目", shiftsOpt, c) { c = it }
                Picker("4番目", shiftsOpt, d) { d = it }
                Picker("5番目", shiftsOpt, e) { e = it }
            }
        }
        else -> onClose()
    }
}

@Composable
private fun InputHint(text: String?) {
    if (text != null) Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
}

/** [dirty] なら ✕・キャンセル・外側タップ・戻るのどれでも破棄の確認を挟む（3.646.0 U01: シフト種別の編集と同じ）。 */
@Composable
private fun Shell(
    title: String,
    okLabel: String,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    addEnabled: Boolean,
    dirty: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = { if (dirty) confirmDiscard = true else onClose() }
    MagiAlertDialog(
        onDismissRequest = requestClose,
        confirmButton = { DialogConfirmButton(okLabel, enabled = addEnabled, onClick = onAdd) },
        dismissButton = { DialogDismissButton(onClick = requestClose) },
        title = { DialogHeader(title, requestClose) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        },
    )
    if (confirmDiscard) DiscardConfirmDialog(onDiscard = { confirmDiscard = false; onClose() }, onContinue = { confirmDiscard = false })
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier = Modifier.width(150.dp), isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        // [3.403.0] 入力エラーは枠の色そのもので示す（OutlinedTextField の組み込み）。説明文で補わない。
        isError = isError,
        // [レイアウト整合] 既定は width(150)。Row 内2連で並べる箇所は weight(1f) を渡してダイアログ幅からの溢れ(欠け)を防ぐ。
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun Picker(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            MagiMenuButton(if (selected.isBlank()) "(なし)" else selected, onClick = { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    MagiMenuItem(if (opt.isBlank()) "(なし)" else opt, onClick = { onSelect(opt); open = false }, selected = opt == selected, monospace = true)
                }
            }
        }
    }
}
