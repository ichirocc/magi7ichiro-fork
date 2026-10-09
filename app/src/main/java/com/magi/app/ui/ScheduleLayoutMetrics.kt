package com.magi.app.ui

import java.time.LocalDate

/** 勤務表タブの縦の寸法（dp）の単一の置き場。Composable と host テストが同じ定数を読む。表示のみ・採点不変。 */
internal object ScheduleLayoutMetrics {
    const val ROW_DP = 48
    const val DAY_HEADER_DP = 72
    const val TOP_BAR_DP = 60
    const val TOP_BAR_WITH_VIOLATION_DP = 72
    const val NAV_BAR_DP = 80

    const val COMMAND_BUTTON_DP = 60
    const val COMMAND_BAR_PAD_V_DP = 10
    const val COMMAND_BAR_DP = COMMAND_BUTTON_DP + 2 * COMMAND_BAR_PAD_V_DP

    const val MERGED_BAR_PAD_V_DP = 2
    const val MERGED_BAR_DP = COMMAND_BUTTON_DP + 2 * MERGED_BAR_PAD_V_DP
    const val ICON_BUTTON_DP = 48

    const val OLD_SCHEDULE_NAV_BAR_DP = 60

    const val SHEET_PEEK_DP = 144
    const val SHEET_PEEK_TOUR_RECOMMEND_DP = 238
    const val SHEET_MAX_FRACTION = 0.62f

    private const val FILTER_BAR_DP = 120
    private const val SEARCH_BAR_DP = 72
    /** [3.648.0] 必須チップ・種別フィルタ（チップ 2 段）・凡例の短い一覧＋検索を 1 枚にした道具カード（違反があるとき）。 */
    private const val TOOLS_CARD_DP = 136
    private const val SECTION_TITLE_DP = 32
    private const val OLD_HINT_DP = 28
    private const val CARD_PAD_DP = 16
    private const val GAP_DP = 20
    private const val SPACER_SMALL_DP = 4
    private const val SPACER_GRID_TOP_DP = 12
    private const val SPACER_BELOW_TITLE_DP = 8

    /** タブ本体の縦スクロールが先頭のとき、日ヘッダの上にあるもの。after＝道具カード 1 枚（3.648.0）、
     *  before＝旧フィルタ＋検索の 2 枚と旧タイトル/ヒント（3.481.0 前。3.481.0〜3.647.0 の 2 枚構成は 264）。 */
    fun bodyAboveAtTop(after: Boolean): Int {
        if (after) return SPACER_SMALL_DP + TOOLS_CARD_DP + GAP_DP + CARD_PAD_DP + SPACER_GRID_TOP_DP
        return SPACER_SMALL_DP + FILTER_BAR_DP + GAP_DP + SEARCH_BAR_DP + GAP_DP + CARD_PAD_DP + SPACER_GRID_TOP_DP +
            SECTION_TITLE_DP + SPACER_BELOW_TITLE_DP + OLD_HINT_DP + SPACER_BELOW_TITLE_DP
    }

    /** タブを開いた直後にグリッドのカード上端へ寄せた状態（カード余白とグリッド上の余白だけが残る）。 */
    fun bodyAboveAtEntryAfter(): Int = CARD_PAD_DP + SPACER_GRID_TOP_DP

    fun bottomStackDp(after: Boolean, sheetOpen: Boolean, running: Boolean = false): Int = when {
        !after -> OLD_SCHEDULE_NAV_BAR_DP + COMMAND_BAR_DP + NAV_BAR_DP
        sheetOpen && !running -> NAV_BAR_DP
        else -> MERGED_BAR_DP + NAV_BAR_DP
    }

    /** 見えるセル行数＝floor((画面 − 上バー − 下の積み − 日ヘッダ − シート − 本文の上の余白) / 48)。 */
    fun visibleRows(usableHeightDp: Int, bottomStack: Int, sheetDp: Int, bodyAbove: Int, topBar: Int = TOP_BAR_DP): Int =
        ((usableHeightDp - topBar - bottomStack - DAY_HEADER_DP - sheetDp - bodyAbove) / ROW_DP).coerceAtLeast(0)
}

/** 勤務表タブの下部バー。タブ・シート・最適化・違反巡回から 1 つに決まる（描画は MagiApp）。 */
internal enum class BottomBarMode { NONE, COMMAND, MERGED_WEEK, MERGED_VIOLATION, HIDDEN }

/**
 * 勤務表タブ以外＝従来の BottomCommandBar。勤務表タブ＝週送り・元に戻す・つくるを 1 本に統合。
 * シートを開いていて最適化中でなければバーごと隠す（シートが代わる）。実行中は「やめる」を必ず残す。
 * 違反巡回は、シートが閉じているときだけバー側の違反ナビ（開いている間はシート側の前/次）。
 */
internal fun bottomBarMode(loaded: Boolean, scheduleTab: Boolean, sheetOpen: Boolean, busy: Boolean, violationNav: Boolean): BottomBarMode = when {
    !loaded -> BottomBarMode.NONE
    !scheduleTab -> BottomBarMode.COMMAND
    sheetOpen && !busy -> BottomBarMode.HIDDEN
    violationNav && !sheetOpen -> BottomBarMode.MERGED_VIOLATION
    else -> BottomBarMode.MERGED_WEEK
}

/** タブを開いた（別タブ→勤務表）ときだけ、グリッドの上端へ縦スクロールする。シート・要確認一覧からの注目があれば各自の位置決めに任せる。 */
internal fun shouldScrollToGridTop(prevTab: Int, tab: Int, loaded: Boolean, sheetOpen: Boolean, focusPending: Boolean): Boolean =
    tab == 1 && prevTab != 1 && loaded && !sheetOpen && !focusPending

/**
 * [3.648.0] 横スクロールで実際に見えている日の範囲（0 始まり）。左端は最も近い列、列数はビューポートに収まる数
 * （端数は四捨五入＝px 丸めで 7 列が 6.98 列に見える誤差を吸収）、最後の日で止める。測れていなければ null。
 * 旧: 月曜区切りの週の範囲を出していたため、10/1(木) 始まりで 1〜7 日が見えているのに「10/1」「〜4」と読めた。
 */
internal fun visibleDayRange(scrollPx: Int, viewportPx: Int, cellWpx: Int, days: Int): IntRange? {
    if (cellWpx <= 0 || viewportPx <= 0 || days <= 0) return null
    val first = Math.round(scrollPx.toFloat() / cellWpx).coerceIn(0, days - 1)
    val cols = ((viewportPx + cellWpx / 2) / cellWpx).coerceAtLeast(1)
    return first..(first + cols - 1).coerceAtMost(days - 1)
}

/** 勤務表グリッド左上に出す日の範囲（2 行）。同じ月なら「10/1」「〜7」、月をまたぐなら「10/29」「〜11/4」。 */
internal fun weekRangeCaption(startDate: String, firstDay: Int, lastDay: Int): Pair<String, String>? = runCatching {
    val a = LocalDate.parse(startDate).plusDays(firstDay.toLong())
    val b = LocalDate.parse(startDate).plusDays(lastDay.toLong())
    "${a.monthValue}/${a.dayOfMonth}" to (if (a.monthValue == b.monthValue) "〜${b.dayOfMonth}" else "〜${b.monthValue}/${b.dayOfMonth}")
}.getOrNull()
