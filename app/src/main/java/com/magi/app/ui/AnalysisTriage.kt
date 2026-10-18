package com.magi.app.ui

import com.magi.app.v6.C1PlateauDiagnosis
import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.ForbiddenRunDiagnosis
import com.magi.app.v6.IssueKind
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.SettingIssue

/**
 * [3.471.0/分析タブ再構築] 分析タブの1画面トリアージを組み立てる **Compose 非依存の純関数**。
 *
 * ## なぜ族で決め打ちしないか
 * 提示された初版のモックは「連勤・並び／窓の要件／曜日の偏り」を**族の名前だけで**
 * 「🤖 最適化エンジンが自動調整する項目・手動修正は不要です」と断定していた。これは
 * 3.263.0（covU「玉突き」診断の楽観バイアス）・3.322.0（C1 が直せなかった理由の構造化）・
 * 3.344.0（「充足可能」と「どう組んでも解消できません」の矛盾）で**逆向きに直してきた誤表示**
 * そのもので、c1 と c3n はデータ次第で最適化では絶対に消せない構造的な壁になる。
 *
 * そこで分類の軸を「族が HARD か SOFT か」ではなく **「データを直さない限り消えないか」** に置く。
 * 判定は新しいロジックを作らず、**既にある診断の結論だけ**を読む:
 *  - `settingIssues`（検査2b-2 / 2b-3 / 6b / 6c ほか＝実行前に分かる構造的破綻）
 *  - `forbiddenDiag`（3.280.0＝禁止連続の run ごとに崩せるか実際に検証済み）
 *  - `c1Plateau`（3.322.0＝窓の要件が**なぜ**直せなかったかの観測。観測が無ければ理由を語らない）
 *  - `coverageDiag`（人員不足/過剰の充足可否と「いまの希望のままでは不能」）
 *
 * ## 断定しないこと
 * 実行前は `c1Plateau`/`forbiddenDiag` が無い（まだ何も観測していない）ので、SOFT 族は
 * **「エンジン探索対象（未計算）」**であって「手動修正は不要」ではない。注記でそれを明示する。
 *
 * 表示のみ・読み取り専用＝スコアリング/エンジンは一切不変。
 */
internal enum class TriageBand { BLOCKER, SEARCH }

/** 上段/中段に出す1行。`count` は族の件数、weekly/fair だけ L1 偏差なので `unit` が "pt" になる。 */
internal data class TriageRow(
    val label: String,
    val count: Int,
    val unit: String,
    val detail: String,
    /** 族キー（`MirrorKeys.all`）。settingIssues 由来の行は null。 */
    val family: String? = null,
    /** 診断が「データを直さない限り消えない」と判定して上段へ上げた行か。 */
    val promoted: Boolean = false,
    /** タップで修復フローへ渡す職員（null=全体探索 or 導線なし）。 */
    val staff: Int? = null,
    /** settingIssues 由来の行の種類＝「設定へ」の着地先（族の行は null）。 */
    val kind: IssueKind? = null,
)

internal data class AnalysisTriage(
    val computed: Boolean,
    /** 🔴 上段: 必須違反＋診断が構造的な壁と判定した族。 */
    val blockers: List<TriageRow>,
    /** 🔴 上段: 設定の破綻（`settingIssues` を種類ごとに集約）。 */
    val issues: List<TriageRow>,
    /** ⏳/🟡 中段: エンジンが挑戦する残りの族。 */
    val searching: List<TriageRow>,
    /** 📊 下段: 0件の族（ゼロサプレッションして畳む）。 */
    val okFamilies: List<String>,
    /** 📊 下段: 残っている族。 */
    val busyFamilies: List<String>,
    /** 中段の注記（断定しない文言）。 */
    val searchNote: String,
) {
    val hasAnything: Boolean get() = blockers.isNotEmpty() || issues.isNotEmpty() || searching.isNotEmpty()
}

/** weekly/fair は件数でなく L1 偏差の合計。単位を分けないと「186件」と読めてしまう。 */
/** 族の数の単位。weekly/fair は L1 偏差なので "pt"、ほかは件（配布前確認も同じ単位を使う）。 */
/** 族の数え方（報告の内訳の単位）。回数の下限・上限・適切回数は回の差、人員は人の差、公平化・曜日の偏りは pt、ほかは件。 */
internal fun unitOf(family: String) = when (family) {
    "fair", "weekly" -> "pt"
    "low", "high", "apt" -> "回"
    "covU", "covO" -> "人"
    else -> "件"
}

private val UNIT_ORDER = listOf("件", "人", "回", "pt")

/** 単位ごとの合計（例「3件・2人・6回」）。単位の違う量は 1 つの数に足さない（統合仕様 INV-07）。全部 0 なら null。 */
internal fun unitTotals(breakdown: Map<String, Int>, families: Collection<String>, includePt: Boolean = true): String? {
    val by = families.groupBy { unitOf(it) }.mapValues { (_, fs) -> fs.sumOf { breakdown[it] ?: 0 } }
    return UNIT_ORDER.filter { includePt || it != "pt" }.mapNotNull { u -> by[u]?.takeIf { it > 0 }?.let { "$it$u" } }
        .takeIf { it.isNotEmpty() }?.joinToString("・")
}

/** 必須違反の数を単位ごとに（例「3件・2人」）。内訳が無いときだけ件で言う。 */
internal fun hardText(breakdown: Map<String, Int>, n: Long): String = unitTotals(breakdown, MirrorKeys.hard) ?: "${n}件"

/** 実行中の上部の札（3.693.0）。必須違反は単位ごとに（件と人を足さない＝INV-07）。必須 0 のときは数を出さない。札の幅に合わせて「必須」＝凡例・ホームの見出しと同じ短い語。 */
internal fun runningBadgeLabel(breakdown: Map<String, Int>, bestHard: Long): String =
    if (bestHard > 0L) "組んでいます 必須${hardText(breakdown, bestHard)}" else "組んでいます"

/** 族ごとの増減を単位ごとに足した言い方（例「1件減る・2人増える」）。同じ単位の中だけで足す。全部 0 なら null。 */
internal fun unitDeltaText(diff: List<Pair<String, Int>>, families: Collection<String>): String? {
    val by = HashMap<String, Int>()
    for ((k, d) in diff) if (k in families && d != 0) by[unitOf(k)] = (by[unitOf(k)] ?: 0) + d
    return UNIT_ORDER.mapNotNull { u -> by[u]?.takeIf { it != 0 }?.let { d -> "${kotlin.math.abs(d)}$u${if (d < 0) "減る" else "増える"}" } }
        .takeIf { it.isNotEmpty() }?.joinToString("・")
}

/** 族ごとの増減（例「下限 −6回・禁止の並び +1件」）。合計の数は出さない。 */
internal fun familyDeltaList(diff: List<Pair<String, Int>>): String =
    diff.filter { it.second != 0 }.joinToString("・") { (k, d) -> "${breakdownLabels[k] ?: k} ${if (d < 0) "−${-d}" else "+$d"}${unitOf(k)}" }

/** 必須違反と要調整を単位ごとに（通知・ログの 1 行）。 */
internal fun reportCountsText(breakdown: Map<String, Int>): String =
    "必須違反 ${unitTotals(breakdown, MirrorKeys.hard) ?: "なし"}・要調整 ${unitTotals(breakdown, MirrorKeys.soft) ?: "なし"}"

private fun labelOf(family: String) = breakdownLabels[family] ?: family

/** ホームのでき具合に添える残り。要調整は単位ごとに（pt の公平化・曜日の偏りは出さない）。
 *  pt だけ残ってもでき具合は 100% でないので「解消済み」とは言わない。 */
internal fun homeRemainingLabel(bestHard: Long, shortDays: Int, breakdown: Map<String, Int>, wishHard: Int = 0): String {
    val softText = unitTotals(breakdown, MirrorKeys.soft, includePt = false)
    val ptN = MirrorKeys.soft.filter { unitOf(it) == "pt" }.sumOf { breakdown[it] ?: 0 }
    return when {
        bestHard > 0L -> "必須 残り${unitTotals(breakdown, MirrorKeys.hard) ?: "${bestHard}件"}" + (if (wishHard > 0) "（うち希望のまま ${minOf(wishHard.toLong(), bestHard)}件）" else "")
        shortDays > 0 -> "人員不足の日 残り${shortDays}日"
        softText != null -> "必須は解消・要調整 $softText"
        ptN > 0 -> "必須は解消・残りは偏りのみ"
        else -> "解消済み"
    }
}

/** ホームの主ボタンの 1 行に収める字数（全幅のボタンで折り返さない上限）。 */
internal const val HOME_BUTTON_MAX_CHARS = 18

/**
 * ホームの主ボタンに最初の対象（名前・日）を前置きする（「アリフ 10/8の希望を見る」）。対象が無いとき、または
 * 前置きで [HOME_BUTTON_MAX_CHARS] を超えるときは元の文言のまま（対象は「次に確認する問題」の行が名指しする）。
 */
internal fun homeTargetLabel(base: String, name: String?, day: String?, fontScale: Float = 1f): String {
    val t = listOfNotNull(name?.takeIf { it.isNotBlank() }, day?.takeIf { it.isNotBlank() }).joinToString(" ")
    if (t.isEmpty()) return base
    val label = if (base == HOME_WISH_BUTTON) "${t}の希望を見る" else "${t}の$base"
    // 文字拡大では 1 行に入る字数も縮む（18 字 ÷ 倍率、3.719.0）。
    return if (label.length <= (HOME_BUTTON_MAX_CHARS / fontScale).toInt()) label else base
}

internal const val HOME_WISH_BUTTON = "ぶつかっている希望を見る"

/** `SettingIssue` の種類 → 画面に出す見出し（英字符号を出さない＝`docs/operator_ux.md`）。 */
private fun issueKindLabel(kind: IssueKind) = when (kind) {
    IssueKind.WISH -> "希望の設定"
    IssueKind.CONSTRAINT -> "決まりの設定"
    IssueKind.DEMAND -> "必要人数の設定"
    IssueKind.RANGE -> "回数の設定"
}

/**
 * 同種を1行に畳む＝「古泉・Dﾃ」「山本・Dﾃ」…と1人1行で伸びていたのを
 * 「回数の設定 8件（古泉/山本 ほか）」にする。名前は先頭2件＋「ほか」まで。
 */
private fun aggregateIssues(issues: List<SettingIssue>): List<TriageRow> =
    issues.groupBy { it.kind }
        .map { (kind, list) ->
            // [実機バグ修正] 1件だけの種類は場所を切り詰めず理由(problem)まで出す。場所だけを18文字で切ると
            //   設定の重複警告が勤務表の違反に読めた（経緯: history 3.515.2）。
            val detail = if (list.size == 1) {
                val s = list.single()
                if (s.problem.isBlank()) s.where else "${s.where}。${s.problem}"
            } else {
                list.take(2).joinToString("/") { it.where.take(18) } + (if (list.size > 2) " ほか" else "")
            }
            TriageRow(
                label = issueKindLabel(kind),
                count = list.size,
                unit = "件",
                detail = detail,
                kind = kind,
            )
        }
        .sortedByDescending { it.count }

/**
 * 上段へ上げてよい SOFT 族か。**診断が実際に観測・検証した結論だけ**を根拠にする。
 * 観測が無いとき（`causeUnknown` など）は上げない＝「直せない理由」を語らない。
 */
private fun promotedSoftFamilies(
    computed: Boolean,
    c1Plateau: C1PlateauDiagnosis?,
    coverage: CoverageDiagnosis?,
): Map<String, String> {
    if (!computed) return emptyMap()
    val out = LinkedHashMap<String, String>()
    if (c1Plateau != null && c1Plateau.hasEntries) {
        val e = c1Plateau.entries.first()
        val more = c1Plateau.entries.size - 1
        out["c1"] = e.label + (if (more > 0) "ほか${more}件" else "") + "。${e.recommendedAction(::labelOf)}"
    }
    val stuckSurplus = coverage?.surpluses?.filter { it.blockedFamily != null }.orEmpty()
    if (stuckSurplus.isNotEmpty()) {
        val first = stuckSurplus.first()
        val fam = labelOf(first.blockedFamily!!)
        val slots = "${first.dayLabel}の「${first.shiftSymbol}」" + (if (stuckSurplus.size > 1) "など${stuckSurplus.size}枠" else "")
        // 曜日の偏り・グループ内のばらつきには緩める設定が無い＝手で動かす案内にする。
        val remedy = if (first.blockedFamily in setOf("weekly", "fair")) "勤務表で手で動かすと減らせます。" else "${fam}の設定を緩めると動きます。"
        out["covO"] = "${slots}は、動かす手は「${fam}」が優先されるため採用されません。$remedy"
    }
    return out
}

/** 崩せない禁止の並びの直す場所（`ForbiddenRunDiagnosis` の案内と同じく、希望の調整か担当の追加）。 */
private const val C3N_WALL_FIX = "希望（編集タブ＞月次条件）か担当（編集タブ＞年間マスター①）を見直してください。"

/** 必須違反の行に添える「なぜ残るか」。診断が無い/観測が無いときは空文字＝何も主張しない。 */
private fun hardDetail(
    family: String,
    coverage: CoverageDiagnosis?,
    forbidden: ForbiddenRunDiagnosis?,
    startDate: String,
): String = when (family) {
    "c3n" -> when {
        forbidden == null || !forbidden.hasRuns -> ""
        forbidden.allBlocked -> "この希望・担当のままでは崩せません（${forbidden.totalRuns}件すべて）。$C3N_WALL_FIX"
        else -> forbidden.runs.firstOrNull { !it.escapable }?.let {
            val span = DayText.range(startDate, it.startDay, it.startDay + maxOf(it.cells.size, 1) - 1)
            "${it.staffName}さんの $span の「${it.seqLabel}」は、この希望・担当のままでは崩せません。$C3N_WALL_FIX"
        } ?: "崩す手が残っています。下の行から直し方を探せます。"
    }
    "covU" -> when {
        coverage == null || !coverage.hasShortage -> ""
        coverage.allBlockedNow -> "いまの希望のままでは、試した入れ替えの範囲で埋まりませんでした（${coverage.totalShortfall}人・自動作成で見つかる場合があります）。"
        coverage.provenBlockedSlots > 0 -> "${coverage.provenBlockedSlots}枠はいまの希望のままでは埋まりません。"
        coverage.searchMissSlots > 0 -> "${coverage.searchMissSlots}枠は試した入れ替えでは埋まりませんでした。"
        else -> "担当を動かせば埋まる見込みです。"
    }
    else -> ""
}

internal fun analysisTriage(ui: UiState): AnalysisTriage {
    // [3.475.0/論理監査] 「計算済み」はエンジンが走った事実で決める。旧: hasResult＝手編集・取込でも true に
    //   なる「盤面がある」旗を読んでいたため、読み込んで1セル直しただけで「計算後に残っている項目」と語っていた。
    val computed = ui.engineRan
    val counts = ui.breakdown
    val promoted = promotedSoftFamilies(computed, ui.c1Plateau, ui.coverageDiag)

    val blockers = ArrayList<TriageRow>()
    val searching = ArrayList<TriageRow>()
    val ok = ArrayList<String>()
    val busy = ArrayList<String>()

    // 重い順に並べる＝重み階層（groupViol > pref > covU > c3n > low > high > …）と表示順を一致させる。
    val ordered = MirrorKeys.all.sortedByDescending { MirrorKeys.weightOf(it) }
    for (fam in ordered) {
        val n = counts[fam] ?: 0
        if (n <= 0) { ok.add(labelOf(fam)); continue }
        busy.add(labelOf(fam))
        val row = TriageRow(labelOf(fam), n, unitOf(fam), "", fam)
        when {
            fam in MirrorKeys.hard ->
                blockers.add(row.copy(detail = hardDetail(fam, ui.coverageDiag, ui.forbiddenDiag, ui.startDate)))
            promoted.containsKey(fam) ->
                blockers.add(row.copy(detail = promoted.getValue(fam), promoted = true))
            else -> searching.add(row)
        }
    }

    val note = if (computed) {
        "作成後も残っている項目です。設定を変えないと消えないものは上に分けています。"
    } else if (ui.relaxedBoard) {
        "設定を緩めて手順を当てた盤面の概算です（再作成する前）。"
    } else {
        "実行前の概算です。期間の制約・禁止の並びなど、設定の組み合わせによっては、つくったあとも残る場合があります。"
    }
    return AnalysisTriage(
        computed = computed,
        blockers = blockers,
        issues = aggregateIssues(ui.settingIssues),
        searching = searching,
        okFamilies = ok,
        busyFamilies = busy,
        searchNote = note,
    )
}

/** 分析タブの主ボタン（1 つだけ）。巡回できるなら巡回、人員不足だけならなおし方、それ以外は出さない。 */
internal enum class AnalysisPrimary { TOUR, GUIDED, NONE }

/** 分析タブの状態の段: 一文・3 点まで・主ボタン（[analysisSummary]）。 */
internal data class AnalysisSummary(val sentence: String, val points: List<String>, val primary: AnalysisPrimary, val primaryLabel: String?)

/** 設定の見直しの行の全幅ボタン（節の名を言う）。 */
internal fun issueSettingsLabel(kind: IssueKind?): String = when (kind) {
    IssueKind.WISH -> "希望の設定を開く"
    IssueKind.DEMAND -> "必要人数の設定を開く"
    null -> "設定を開く"
    else -> settingsLabelFor(yearSectionForIssueKind(kind) ?: "")
}

/**
 * 分析タブの状態の段。一文は上部の札（[boardStatusLines]）と同じ区別で決める。
 * [tourCount]＝巡回できる必須違反の数、[fixResolved]＝変更案で直せる必須違反の数（null＝未探索・古い＝出さない）。
 */
/** 分析タブの一文（「必須3件 · 要調整12件」）。 */
internal fun analysisCountLine(hardLabel: String, softLabel: String): String = "必須$hardLabel · 要調整$softLabel"

internal fun analysisSummary(ui: UiState, tourCount: Int, fixResolved: Int?): AnalysisSummary {
    val hard = ui.bestHard
    val evaluated = ui.checkRev > 0 && !ui.running
    val sentence = when {
        ui.running -> "勤務表をつくっています。表示は途中の値です。"
        !ui.hasResult -> "まだ作っていません。"
        !ui.engineRan && !ui.ranBeforeEdit -> "手で編集した勤務表です（自動作成はまだ）。"
        hard > 0 || ui.bestSoft > 0 -> analysisCountLine(if (hard > 0) hardText(ui.breakdown, hard) else "0件", unitTotals(ui.breakdown, MirrorKeys.soft) ?: "0件")
        else -> "必須違反も要調整もありません。"
    }
    // [3.713.0] 件数は一文（「必須3件 · 要調整12件」）が言う＝点は変更案で直せる数だけ。
    val points = if (!ui.hasResult && !ui.running) emptyList() else buildList {
        if (evaluated && hard > 0 && fixResolved != null) add("変更案で直せる必須違反 ${fixResolved}か所")
    }
    val shortDays = ui.coverageDiag?.shortfalls?.filter { it.miss > 0 }?.map { it.dayIndex }?.distinct()?.size ?: 0
    val active = hard > 0 && !ui.running && ui.hasResult
    val primary = when {
        active && tourCount > 0 -> AnalysisPrimary.TOUR
        active && shortDays > 0 -> AnalysisPrimary.GUIDED
        else -> AnalysisPrimary.NONE
    }
    val label = when (primary) {
        AnalysisPrimary.TOUR -> guidedTourButton(tourCount)
        AnalysisPrimary.GUIDED -> "なおし方を見る（${shortDays}日）"
        AnalysisPrimary.NONE -> null
    }
    return AnalysisSummary(sentence, points, primary, label)
}

/** 前回の作成と同じ設定（状態指紋）から同じ盤面（盤面指紋）が出たか。初回は false。 */
internal fun isSameRunResult(prev: Pair<Long, Long>?, now: Pair<Long, Long>): Boolean = prev != null && prev == now

/** 盤面か設定が変わったら出さない。[3.716.0] 設定は状態指紋で照合（C# と同じ。editRev だと色の変更で消え、元に戻すで同じ盤面に戻っても出ない）。 */
internal fun sameResultVisible(note: SameResultNote?, stateKey: Long, schedule: List<List<Int>>): Boolean =
    note != null && note.stateKey == stateKey && note.schedule == schedule

internal fun sameResultNotice(hard: Int): String =
    if (hard > 0) "前回と同じ結果です。条件を変えない限り、この必須違反${hard}件は消えません。" else "前回と同じ結果です。"

/** 同じ依頼（起点）・同じ盤面と設定で続いた案 0 件の回数。 */
internal data class EmptySearchStreak(val origin: String, val board: Long, val state: Long, val count: Int)

/** 案があれば途切れる（null）。起点・盤面・設定のどれかが違えば 1 から数え直す。[3.716.0] 自動の探索（[auto]）は数えも途切れさせもしない（人の操作の回数だけ）。 */
internal fun nextEmptySearchStreak(prev: EmptySearchStreak?, origin: String, board: Long, state: Long, found: Int, auto: Boolean = false): EmptySearchStreak? = when {
    auto -> prev
    found > 0 -> null
    prev != null && prev.origin == origin && prev.board == board && prev.state == state -> prev.copy(count = prev.count + 1)
    else -> EmptySearchStreak(origin, board, state, 1)
}

internal const val REPEATED_EMPTY_LINE = "続けて案がありませんでした"
internal const val NO_FIX_TITLE = "案はありません"
/** [3.714.0] 案なしの主ボタン（残っている問題の巡回の先頭へ）と閉じる。 */
internal const val NO_FIX_LIST_BUTTON = "問題一覧"
internal const val NO_FIX_CLOSE_BUTTON = "閉じる"
internal const val NO_FIX_LINE = "設定か希望の確認が必要です"
/** [3.716.0] 締切で打ち切られた探索の 1 行（設定の確認を求めない。理由の TIME の行は本文に残る）。 */
internal const val NO_FIX_TIMEOUT_LINE = "時間内に見つかりませんでした"

/** 案なしの説明の 1 行: 締切で打ち切られたら時間の行、続けて空なら続けて空の行、そうでなければ定型の 1 行（3.711.0 の行を残す）。 */
internal fun noFixLine(repeated: Boolean, timedOut: Boolean = false): String = when {
    timedOut -> NO_FIX_TIMEOUT_LINE
    repeated -> REPEATED_EMPTY_LINE
    else -> NO_FIX_LINE
}

internal enum class RepeatedEmptyAction(val label: String) { WISHES("希望を見る"), SETTINGS("設定の確認を見る") }

/** 続けて 0 件の対象の行き先。希望がぶつかるセルなら希望、設定ミスの診断が出ていれば設定、どちらも無ければボタンなし。 */
internal fun repeatedEmptyAction(wishOnTarget: Boolean, settingIssueKnown: Boolean): RepeatedEmptyAction? = when {
    wishOnTarget -> RepeatedEmptyAction.WISHES
    settingIssueKnown -> RepeatedEmptyAction.SETTINGS
    else -> null
}
