package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.LocalDate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.MirrorKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

internal fun decodeCsvBytes(bytes: ByteArray): String {
    val utf8 = runCatching {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    val text = utf8 ?: runCatching { String(bytes, charset("MS932")) }.getOrElse { String(bytes, Charsets.UTF_8) }
    return text.removePrefix("﻿")
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagiApp(vm: MagiViewModel = viewModel(), themeMode: Int = 0, onThemeMode: (Int) -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // [保存] バックグラウンド遷移(ON_STOP/ON_PAUSE)で保留中の編集を即時永続化する。
    //   制約編集などはデバウンス保存のため、即背景化→プロセス破棄だと失われ得る。その保険。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP ||
                event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                vm.saveNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // [Web反映/Wake Lock] 最適化(前景)中は画面を消灯させない＝計算の中断・ライブ表示の停止を防ぐ。
    val rootView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(ui.running) { rootView.keepScreenOn = ui.running }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var editingCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var oneHand by rememberSaveable { mutableStateOf(false) }
    var proMode by rememberSaveable { mutableStateOf(false) }   // [プロ編集] 表示モード（false=かんたん / true=プロ）
    var editScope by rememberSaveable { mutableStateOf(0) }   // [Web反映] 編集タブ: 0=月次 / 1=年次マスター
    var wishConfirm by remember { mutableStateOf(0) } // >0: 担当外件数の確認ダイアログ表示
    var rosterCsvChoice by remember { mutableStateOf<String?>(null) } // !=null: 勤務表/希望 取込選択ダイアログ
    var pendingCsvImport by remember { mutableStateOf<String?>(null) } // !=null: 取込種別の選択ダイアログ
    var pendingExportKind by remember { mutableStateOf<String?>(null) } // staff/wishes/cons: コンポーネント別出力
    var guidedFix by remember { mutableStateOf(false) }              // [operator_ux §5] 「なおすのを手伝って」対話

    val openJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (text != null) vm.load(text)
            }
        }
    }

    val openCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { decodeCsvBytes(it.readBytes()) }
                    }.getOrNull()
                }
                if (text != null) {
                    // 取込種別はオペレーターが選択する（自動判定しない）。
                    pendingCsvImport = text
                }
            }
        }
    }

    val saveJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.Default) { vm.exportJson() }
                if (json != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = withContext(Dispatchers.Default) { vm.exportCsv() }
                if (csv != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use {
                                // UTF-8 BOM を付与。日本の Excel は BOM 無し UTF-8 を CP932 と誤読し文字化けするため、
                                // BOM(EF BB BF) を先頭に書いて Unicode(UTF-8) と認識させる。取込側は removePrefix で BOM 除去済。
                                it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                                it.write(csv.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                }
            }
        }
    }

    val saveComponentCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val kind = pendingExportKind; pendingExportKind = null
        if (uri != null && kind != null) {
            scope.launch {
                val csv = withContext(Dispatchers.Default) {
                    when (kind) {
                        "staff" -> vm.exportStaffCsv()
                        "wishes" -> vm.exportWishesCsv()
                        "cons" -> vm.exportConstraintsCsv()
                        else -> null
                    }
                }
                if (csv != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use {
                                it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                                it.write(csv.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                }
            }
        }
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { vm.exportLogs() }
                if (text != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }

    val saveLogJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { vm.exportLogsJson() }
                if (text != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 許可有無に関わらず計算は継続。許可時のみ完了通知が表示される。 */ }
    val onBgOptimize: () -> Unit = {
        notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        vm.runInBackground()
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    val loadSample: () -> Unit = {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val asset = runCatching { ctx.assets.open("sample_state_v6.json") }.getOrElse { ctx.assets.open("sample_state.json") }
                    asset.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text != null) vm.load(text)
        }
    }
    val openJson: () -> Unit = { openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }

    Scaffold(
        topBar = { MagiTopBar(ui) },
        bottomBar = {
            Column {
                if (ui.loaded) BottomCommandBar(ui, vm)
                MagiBottomNav(tab) { tab = it }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .padding(top = if (oneHand) 120.dp else 0.dp) // 片手モード: 内容を親指の届く下方へ
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (!ui.loaded && tab != 4) {
                // [⛏6] 「新規に作る」→ 最小データで開始し、編集タブ(年次マスター)へ誘導。
                //   そこで E6 の折りたたみ節＋⛏12 の一括追加でシフト/グループ/スタッフを育てる。
                EmptyStateCard(onOpen = openJson, onSample = loadSample, onNew = { vm.initBlankState(); tab = 2; editScope = 1 })
            } else when (tab) {
                0 -> {
                    InterruptedBanner(ui, onRerun = { vm.runV6FullOptimize() }, onDismiss = { vm.dismissInterrupted() })
                    // [operator_ux §3] 思考誘導ホーム：いまの状態に応じて「次にやること」を1枚＋大ボタン1つで提示。
                    OperatorNextActionCard(
                        ui = ui,
                        onMake = { vm.runV6FullOptimize() },
                        onDraft = { vm.generateSimple() },
                        onStop = { vm.stop() },
                        onExport = { saveCsvLauncher.launch("magi_schedule_${System.currentTimeMillis()}.csv") },
                        onSchedule = { tab = 1 },
                        onFix = { guidedFix = true },
                        onSetup = { tab = 2 },
                    )
                    LiveScheduleCard(ui)
                    // [冗長性削減] StatusHero(状態三重表示) / SummaryCard(統計は「ようす」と重複＋開発用語) /
                    //   QuickActionGrid(下部ナビと4/6重複) は home から除外。詳細統計は「ようす」タブへ集約。
                    CopilotCard(ui, onGoEdit = { tab = 2 }, onSoftPolish = { vm.runSoftPolish() })
                    CoverageDiagnosisCard(ui)
                    SettingIssuesCard(ui, onFix = { vm.applySettingFix(it) }, onGoEdit = { tab = 2 })
                    ActionCard(ui, vm, onBgOptimize = onBgOptimize)
                    AlternativesCard(ui, onApply = { vm.applyAlternative(it) })
                }
                1 -> {
                    val openEditor: (Int, Int) -> Unit = { i, j ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editingCell = i to j
                    }
                    // [B1] 結果(読取ws6)/下書き(ws7) モード分離。既定=結果(読取)。確定結果が無ければ下書き扱い。
                    var editing by rememberSaveable { mutableStateOf(false) }
                    var copyConfirm by rememberSaveable { mutableStateOf(false) }
                    var wishBulkOpen by rememberSaveable { mutableStateOf(false) }
                    val canRead = ui.hasResultSnapshot
                    val effectiveEditing = editing || !canRead
                    ScheduleModeCard(
                        editing = effectiveEditing,
                        canRead = canRead,
                        onSelect = { editing = it },
                        onCommit = { vm.commitEditingToResult() },
                        onCopy = { copyConfirm = true },
                    )
                    // [校正] 希望の反映は「下書き（直す）」時だけ表示。読取結果には適用できないため
                    //   読取時に出すのは冗長＝誤操作のもと。
                    if (effectiveEditing) WishApplyCard(ui, onApply = {
                        val oos = vm.wishOutOfScopeCount()
                        if (oos > 0) wishConfirm = oos else vm.applyWishes(false)
                    })
                    val gridUi = if (effectiveEditing) ui else ui.copy(schedule = ui.resultSchedule)
                    val onCell: (Int, Int) -> Unit = if (effectiveEditing) openEditor else { _, _ -> vm.hintReadOnly() }
                    ScheduleGrid(gridUi, onCellClick = onCell, proMode = proMode,
                        onBulkSet = { cells, k -> if (effectiveEditing) vm.setCells(cells, k) else vm.hintReadOnly() })
                    StaffCalendarCard(gridUi, onCellClick = onCell)
                    TallyCard(gridUi, vm, onFix = { staff, shift -> tab = 3; vm.findFixSuggestions(staff, shift) })
                    if (effectiveEditing) MismatchExtractCard(ui, onOpenCell = openEditor)
                    if (effectiveEditing) {
                        OutlinedButton(onClick = { wishBulkOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("希望シフトの一括操作（曜日／全体）")
                        }
                    }
                    if (wishBulkOpen) {
                        WishBulkSheet(ui, vm, presetWeekday = 0, onDismiss = { wishBulkOpen = false })
                    }
                    if (copyConfirm) {
                        AlertDialog(
                            onDismissRequest = { copyConfirm = false },
                            title = { Text("結果を下書きに複製しますか？") },
                            text = { Text("いまの下書きは破棄され、確定済みの「結果」で置き換わります。「元に戻す」で取り消せます。") },
                            confirmButton = { DialogConfirmButton("複製する", onClick = { copyConfirm = false; vm.copyResultToEditing() }) },
                            dismissButton = { DialogDismissButton(onClick = { copyConfirm = false }) },
                        )
                    }
                }
                2 -> {
                    SetupGuideCard(ui, vm)
                    // [Web反映] 毎月変える「月次」と、たまにしか触らない「年次マスター」を分けて誤編集を防ぐ。
                    MagiSegmentedControl(options = listOf("月次（毎月）", "年次マスター"), selected = editScope, onSelect = { editScope = it })
                    if (editScope == 0) {
                        MonthPickerCard(ui, vm)
                        WishCard(ui, vm)
                        NeedDayCard(ui, vm)
                    } else {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("制度・人員が変わったときだけ編集してください。毎月の調整は「月次」へ。",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        // [E6案A] 年次の長大スクロールを、不要カードを畳んで削減。基本情報のみ既定で展開。
                        //   展開状態は CollapsibleSection 内の rememberSaveable で保持(回転/再構成に耐える)。
                        CollapsibleSection("基本情報（シフト・グループ・スタッフ）", "yr_ws1", initiallyExpanded = true) { Ws1Card(ui, vm) }
                        CollapsibleSection("回数設定（適切回数・個人/群レンジ）", "yr_count") { CountSettingsCard(ui, vm) }
                        CollapsibleSection("スキルグループ", "yr_skillg") { SkillGroupCard(ui, vm) }
                        CollapsibleSection("スキルのルール（C41s・C42s）", "yr_skillc") { SkillConstraintsCard(ui, vm) }
                        // [発見性] cons41(群の1日人数)/cons42(組み合わせ禁止)を、スキル版(C41s/C42s)と対称な専用節に。
                        //   従来は「ルール（並び・窓）」に6family埋もれ、見出しから群の人数/組み合わせ設定と分からなかった。
                        CollapsibleSection("グループのルール（C41 1日の人数・C42 組み合わせ禁止）", "yr_groupc") {
                            ConstraintsCard(ui, vm, title = "グループのルール（C41 1日の人数・C42 組み合わせ禁止）",
                                keys = setOf("cons41", "cons42"))
                        }
                        CollapsibleSection("ルール（並び・窓）", "yr_cons") {
                            ConstraintsCard(ui, vm, title = "ルールの編集（勤務の並び・回数）",
                                keys = setOf("cons1", "cons2", "cons3", "cons3n", "cons3m", "cons3mn"))
                        }
                        CollapsibleSection("個人の回数（下限/上限）", "yr_range") { StaffRangeCard(ui, vm) }
                    }
                }
                3 -> {
                    // [N2/⛏11] プロ表示トグルを分析タブ上部に常設（従来は設定タブの外観カード内＝
                    //   タブ往復が必要だった）。proMode は共有状態なので設定側トグルと同期する。
                    MagiSegmentedControl(options = listOf("一般", "プロ"), selected = if (proMode) 1 else 0, onSelect = { proMode = it == 1 })
                    // [冗長性/用語] 「ようす」は やさしい俯瞰＋チェック＋内訳 のみ。開発用の V6 1ヶ月俯瞰
                    //   (HARD Core/Guard・Apt/Equalize/covU 等の生指標) は詳細設定(上級者)へ移設。
                    // [プロ編集] プロ表示モードのときは数値診断（V6 1ヶ月俯瞰・生指標）を前面に出す。
                    if (proMode) V6DashboardCard(ui.v6)
                    if (proMode) WeightTableCard()   // [N2/⛏11] スコアの重み根拠（最適化器と一致）
                    OverviewDashboard(ui)
                    CheckSummaryView(ui)
                    BreakdownCard(ui, onFocusStaff = { vm.findFixSuggestions(it) })
                    BottleneckCard(ui)
                    FixSuggestionCard(ui, onSearch = { vm.findFixSuggestions(null) }, onApply = { vm.applyFixSuggestion(it) })
                    // [校正] 開発用の ColorSettingsView（英語名・生の制約コード/WARN/CRITICAL露出）と
                    // FlagsView（実験フラグ）は一般ユーザー画面から除外。詳細設定は上級者向けに別途。
                }
                else -> {
                    AppearanceCard(themeMode, onThemeMode, oneHand, { oneHand = it }, proMode) { proMode = it }
                    ShiftColorCard(ui, vm)
                    DataActionsCard(
                        ui = ui,
                        onOpenJson = openJson,
                        onSample = loadSample,
                        onSaveJson = { saveJsonLauncher.launch("magi_state_${System.currentTimeMillis()}.json") },
                        onOpenCsv = { openCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                        onSaveCsv = { saveCsvLauncher.launch("magi_schedule_${System.currentTimeMillis()}.csv") },
                        onCheck = { vm.refreshCheck() },
                        onSaveStaffCsv = { pendingExportKind = "staff"; saveComponentCsvLauncher.launch("magi_staff_${System.currentTimeMillis()}.csv") },
                        onSaveWishesCsv = { pendingExportKind = "wishes"; saveComponentCsvLauncher.launch("magi_wishes_${System.currentTimeMillis()}.csv") },
                        onSaveConstraintsCsv = { pendingExportKind = "cons"; saveComponentCsvLauncher.launch("magi_constraints_${System.currentTimeMillis()}.csv") },
                    )
                    SettingsCard(ui, vm)
                    OperatorLogView(ui)
                    // [screen_spec #12/#168] 実験フラグ(FlagsView)・ログ(LogsCard)・色トークン(ColorSettingsView)を
                    // 折りたたみの詳細設定(既定=閉)に隔離。通常運用では触らない開発/上級項目。
                    AdvancedSettingsSection(
                        ui = ui,
                        vm = vm,
                        onExportLog = { saveLogLauncher.launch("magi_log_${System.currentTimeMillis()}.txt") },
                        onExportJson = { saveLogJsonLauncher.launch("magi_log_${System.currentTimeMillis()}.json") },
                    )
                }
            }
            ui.message?.let { MessageBar(it) }
            Spacer(Modifier.height(12.dp)) // 下部コマンドバー分の余白
        }
        val cell = editingCell
        if (cell != null) {
            ShiftPickerSheet(
                ui = ui,
                vm = vm,
                cell = cell,
                onPick = { k ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.setCell(cell.first, cell.second, k)
                    editingCell = null
                },
                onDismiss = { editingCell = null },
            )
        }
        if (guidedFix) {
            GuidedFixDialog(ui, vm, onDismiss = { guidedFix = false }, onRerun = { guidedFix = false; vm.runV6FullOptimize() })
        }
        pendingCsvImport?.let { csvText ->
            AlertDialog(
                onDismissRequest = { pendingCsvImport = null },
                title = { Text("取込種別を選択") },
                text = {
                    Text(
                        "この CSV を何として取り込みますか？\n\n" +
                            "・データ全体（新規）：勤務表テンプレ/ユニット列形式を新しいデータとして読み込み\n" +
                            "・勤務表（重ね合わせ）：氏名,1日,2日… の表を現在の割り当てに重ねる\n" +
                            "・スタッフ一覧：氏名,グループ,スキル（所属群/スキルを更新）\n" +
                            "・希望シフト：氏名,日,希望シフト（希望を置換）\n" +
                            "・各制約：種別タグ付き（制約一式・個人レンジを置換）",
                    )
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogConfirmButton("データ全体（新規）", onClick = {
                            if (com.magi.app.v6.RosterCsvImport.detect(csvText)) { rosterCsvChoice = csvText } else { vm.importCsvSmart(csvText) }
                            pendingCsvImport = null
                        })
                        DialogConfirmButton("勤務表（重ね合わせ）", onClick = { vm.importCsv(csvText); pendingCsvImport = null })
                        DialogConfirmButton("スタッフ一覧", onClick = { vm.importStaffCsv(csvText); pendingCsvImport = null })
                        DialogConfirmButton("希望シフト", onClick = { vm.importWishesCsv(csvText); pendingCsvImport = null })
                        DialogConfirmButton("各制約", onClick = { vm.importConstraintsCsv(csvText); pendingCsvImport = null })
                    }
                },
                dismissButton = { DialogDismissButton(onClick = { pendingCsvImport = null }) },
            )
        }
        rosterCsvChoice?.let { csvText ->
            AlertDialog(
                onDismissRequest = { rosterCsvChoice = null },
                title = { Text("CSVの取り込み方法") },
                text = {
                    Text(
                        "この勤務表CSVを、どちらとして取り込みますか？\n\n" +
                            "・勤務表：表のとおり、いまの割り当てとして読み込みます。\n" +
                            "・希望シフト：表をスタッフの希望として読み込み、勤務表は空から作成して最適化で希望を尊重します。",
                    )
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogConfirmButton("勤務表として取り込む", onClick = { vm.importRosterAs(csvText, false); rosterCsvChoice = null })
                        DialogDismissButton(onClick = { vm.importRosterAs(csvText, true); rosterCsvChoice = null }, text = "希望シフトとして取り込む")
                    }
                },
                dismissButton = { DialogDismissButton(onClick = { rosterCsvChoice = null }) },
            )
        }
        if (wishConfirm > 0) {
            AlertDialog(
                onDismissRequest = { wishConfirm = 0 },
                title = { Text("担当外の希望を含めますか？") },
                text = { Text("担当できないグループの希望が ${wishConfirm} 件あります。含めて反映すると担当不可の配置になります（違反として検出されます）。") },
                confirmButton = {
                    DialogConfirmButton("含めて反映", onClick = { vm.applyWishes(true); wishConfirm = 0 })
                },
                dismissButton = {
                    DialogDismissButton(onClick = { vm.applyWishes(false); wishConfirm = 0 }, text = "担当内のみ反映")
                },
            )
        }
    }
}

/**
 * [DefragLiveView 移植] 計算中の最良盤面ライブ表示。実行中のみ・折りたたみ。前回スナップショットと比較し
 * 変化セルを赤枠でハイライト（操作不可の読取専用。オペレーターに「組んでいる様子」を見せて安心させる）。
 */

@Composable
internal fun MagiTopBar(ui: UiState) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
                Text(
                    "MAGI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("勤務表", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (ui.loaded) {
                val ok = ui.hasResult && ui.bestHard == 0L
                val label: String; val fg: Color; val bg: Color
                when {
                    ui.running -> {
                        // [進捗の見える化] バッジに改善の手応えを添える: hard残あり→⚠数、hard=0→soft改善(init→best)。
                        val prog = when {
                            ui.bestHard > 0L -> " ⚠${ui.bestHard}"
                            ui.initSoft > 0L && ui.bestSoft in 0 until ui.initSoft -> " ${ui.initSoft}→${ui.bestSoft}"
                            else -> ""
                        }
                        label = "実行中$prog"; fg = MaterialTheme.colorScheme.onPrimaryContainer; bg = MaterialTheme.colorScheme.primaryContainer
                    }
                    ok -> { label = "配布可"; fg = MaterialTheme.colorScheme.onTertiaryContainer; bg = MaterialTheme.colorScheme.tertiaryContainer }
                    ui.hasResult -> { label = "未解決 ${ui.bestHard}"; fg = MaterialTheme.colorScheme.onErrorContainer; bg = MaterialTheme.colorScheme.errorContainer }
                    else -> { label = "未計算"; fg = MaterialTheme.colorScheme.onSurfaceVariant; bg = MaterialTheme.colorScheme.surfaceVariant }
                }
                Surface(color = bg, shape = MaterialTheme.shapes.small) {
                    Text(label, color = fg, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}


@Composable
internal fun BottomCommandBar(ui: UiState, vm: MagiViewModel) {
    val cs = MaterialTheme.colorScheme
    // 一本指: 主要操作を画面下部に全幅・大ボタン(60dp)で常設。指の届く範囲で押し外しにくい。文脈で 停止/作成/最適化。
    Surface(color = cs.surface, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.canUndo && !ui.running) {
                OutlinedButton(
                    onClick = { vm.undo() },
                    modifier = Modifier.height(60.dp).semantics { contentDescription = "直前の操作を元に戻す" },
                ) { Text("元に戻す") }
                Spacer(Modifier.width(10.dp))
            }
            // [Web反映] やり直し（手動修正ループ）。元に戻した直後だけ出す。
            if (ui.canRedo && !ui.running) {
                OutlinedButton(
                    onClick = { vm.redo() },
                    modifier = Modifier.height(60.dp).semantics { contentDescription = "元に戻した操作をやり直す" },
                ) { Text("やり直し") }
                Spacer(Modifier.width(10.dp))
            }
            when {
                ui.running -> Button(
                    onClick = { vm.stop() },
                    modifier = Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("やめる", style = MaterialTheme.typography.titleMedium)
                }
                !ui.hasResult -> Button(
                    // [統一] ラベル「勤務表をつくる」＝本最適化（思考誘導カードの大ボタンと同一動作）。
                    //   下書きは思考誘導カードの補助「下書きをつくる」が担う（同名ラベルで別動作の不整合を解消）。
                    onClick = { vm.runV6FullOptimize() },
                    modifier = Modifier.weight(1f).height(60.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("勤務表をつくる", style = MaterialTheme.typography.titleMedium)
                }
                else -> Button(
                    onClick = { vm.runV6FullOptimize() },
                    modifier = Modifier.weight(1f).height(60.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("もう一度つくる", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}


@Composable
internal fun MagiBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("ホーム", Icons.Filled.Home, "ホーム"),
        Triple("勤務表", Icons.Filled.DateRange, "勤務表"),
        Triple("編集", Icons.Filled.Edit, "初期設定と制約の編集"),
        Triple("分析", Icons.Filled.Assessment, "分析と違反"),
        Triple("設定", Icons.Filled.Settings, "設定とデータ"),
    )
    NavigationBar {
        items.forEachIndexed { i, item ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = { Icon(item.second, contentDescription = item.third) },
                label = { Text(item.first, style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
            )
        }
    }
}

/** 前回の計算が中断（プロセスkill等）された場合の復帰バナー。入力は復元済みで、ワンタップ再実行できる。 */

@Composable
internal fun InterruptedBanner(ui: UiState, onRerun: () -> Unit, onDismiss: () -> Unit) {
    if (!ui.interruptedRun || ui.running) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("前回の計算は中断されました", style = MaterialTheme.typography.titleMedium)
            Text(ui.interruptedInfo ?: "入力は自動保存済みです。もう一度実行できます。",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRerun, enabled = ui.loaded,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("もう一度実行") }
                OutlinedButton(onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
            }
        }
    }
}


@Composable
internal fun EmptyStateCard(onOpen: () -> Unit, onSample: () -> Unit, onNew: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            Text("勤務表データを開きましょう", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("保存済みのデータを開く、サンプルから始める、または空から新しく作れます。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("データを開く", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onSample, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("サンプルで試す", style = MaterialTheme.typography.labelLarge)
            }
            // [⛏6] ゼロから作る起点。最小データで開始し、編集タブ(年次マスター)へ誘導する。
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("新規に作る（空から）", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}


@Composable
internal fun MessageBar(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(14.dp))
    }
}
