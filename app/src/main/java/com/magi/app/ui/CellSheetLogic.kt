package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.canDo
import com.magi.app.v6.formatDay
import com.magi.app.v6.pinned

/**
 * 勤務表のセル編集シートの純ロジック（Compose 非依存＝ホスト JVM で固定する）。
 * ボタンの固定配置・1 行の状態・おすすめの点をここに置き、画面は並べるだけにする。
 */

/** シフトボタン 1 枠。[canDo]=false は枠を残したまま「外」で灰色にする。 */
internal data class ShiftSlot(val shift: Int, val canDo: Boolean)

internal const val CELL_SHEET_COLUMNS = 4

/** 枠に出すシフト＝データの中で誰か 1 人でも担当できるもの（データごとに 1 回。誰も担当できなければ全部）。 */
internal fun sheetShifts(shiftCount: Int, allowedByStaff: List<Set<Int>>): List<Int> {
    val any = allowedByStaff.flatten().toSet()
    return (0 until shiftCount).filter { it in any }.ifEmpty { (0 until shiftCount).toList() }
}

/**
 * 枠は [shifts] の順で固定（職員・割当/希望の切替で動かない）。行の末尾の空きは null。
 * [leftHand] は各行を左右反転する（空きも含めて反転＝左手でも同じシフトは同じ枠）。
 */
internal fun cellSheetSlots(shifts: List<Int>, canDo: Set<Int>, leftHand: Boolean = false): List<List<ShiftSlot?>> =
    shifts.map { ShiftSlot(it, it in canDo) }.chunked(CELL_SHEET_COLUMNS).map { row ->
        (row + List(CELL_SHEET_COLUMNS - row.size) { null }).let { if (leftHand) it.reversed() else it }
    }

internal enum class CellSeverity { HARD, SOFT, NONE }

/** [cause] は接頭辞（必須/要調整）を除いた原因だけ（希望を守っている板挟みの 2 行目に使う）。 */
internal data class CellStatus(val severity: CellSeverity, val text: String, val cause: String = "")

/** [#41] 手動固定のセルに違反が残るときの状態の 1 行の言い方。 */
internal const val PIN_BLOCKED_NOTE = "手動固定のため直せません"

/** セル・人員(当日の今のシフト)・回数(この職員の今のシフト) の族を重い順に並べる。族名は `vio-` なしの族キー。 */
internal fun cellStatusFamilies(cellClasses: List<String>, needClasses: List<String>, countClasses: List<String>): List<String> =
    (cellClasses + needClasses + countClasses).map { familyOfVioClass(it) }.distinct()
        .sortedByDescending { MirrorKeys.weightOf(it) }

/**
 * 1 行の状態。最も重い族について、原因と関わる人・日・数をチェッカーと同じ盤面から言う。
 * 族の判定はチェッカーの印（[families]）に従い、ここでは中身の言い換えだけをする。
 */
internal fun cellStatusLine(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, j: Int, families: List<String>): CellStatus {
    if (families.isEmpty()) return CellStatus(CellSeverity.NONE, "違反なし")
    val top = families.first()
    val hard = families.any { it in MirrorKeys.hard }
    val detail = familyDetail(state, p, s, i, j, top) ?: (breakdownLabels[top] ?: top)
    // [#41] 手動固定のセルは違反を数えて見せたまま、最適化器も直し方も動かさないことを言う。
    val more = (if (families.size > 1) "（ほか${families.size - 1}件）" else "") +
        (if (i in 0 until p.S && j in 0 until p.T && p.pinned(i, j)) "。$PIN_BLOCKED_NOTE" else "")
    return if (hard) CellStatus(CellSeverity.HARD, "⚠ 必須：$detail$more", "$detail$more")
    else CellStatus(CellSeverity.SOFT, "⚠ 要調整：$detail$more", "$detail$more")
}

private fun familyDetail(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, j: Int, fam: String): String? {
    fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: "?"
    fun name(x: Int) = state.staff.getOrNull(x)?.name ?: "#$x"
    fun day(d: Int) = formatDay(state.startDate, d).substringBefore('(')
    val cur = s.getOrNull(i)?.getOrNull(j) ?: -1
    val count = { k: Int -> s[i].count { it == k } }
    return when (fam) {
        "c3w" -> p.wish.getOrNull(i)?.getOrNull(j + 1)?.takeIf { it >= 0 }?.let { "翌日(${sym(it)})への前日禁止（${sym(cur)}）" }
        "c3n", "c3mn" -> {
            val list = if (fam == "c3n") p.cons3n else p.cons3mn
            forbiddenRunAt(p, s, i, j, list)?.let { (seq, j0) ->
                "${breakdownLabels[fam]} ${seq.joinToString("→") { sym(it) }}（${day(j0)}〜${day(j0 + seq.size - 1)}）"
            }
        }
        "c3", "c3m" -> {
            val list = if (fam == "c3") p.cons3 else p.cons3m
            list.firstOrNull { it.seq.firstOrNull() == cur }?.let { c ->
                "${breakdownLabels[fam]} ${c.seq.joinToString("→") { sym(it) }} が${day(j)}から続かない"
            }
        }
        "c42s", "c42" -> {
            val skill = fam == "c42s"
            val grp = if (skill) p.ssk else p.sgrp
            val partners = LinkedHashSet<Int>()
            for (c in if (skill) p.cons42s else p.cons42) for (x in 0 until p.S) {
                if (x == i) continue
                val xk = s[x][j]
                if (grp[i] == c.g1 && cur == c.s1 && grp[x] == c.g2 && xk == c.s2) partners.add(x)
                if (grp[i] == c.g2 && cur == c.s2 && grp[x] == c.g1 && xk == c.s1) partners.add(x)
            }
            partners.takeIf { it.isNotEmpty() }?.let { ps ->
                ps.joinToString("・") { "${name(it)}(${sym(s[it][j])})" } + "との${if (skill) "スキルグループペア禁止" else "グループペア禁止"}"
            }
        }
        "covU", "covO" -> if (cur < 0) null else {
            val lo = p.need1[cur][j]
            val hi = if (p.use2 && p.need2[cur][j] >= 0) p.need2[cur][j] else lo
            val n = (0 until p.S).count { s[it][j] == cur }
            if (fam == "covU") "${day(j)}の${sym(cur)}が人員不足（必要${lo}人に${n}人）"
            else "${day(j)}の${sym(cur)}が人員過剰（適正${hi}人に${n}人）"
        }
        "c41s", "c41" -> if (cur < 0) null else {
            val skill = fam == "c41s"
            val grp = if (skill) p.ssk else p.sgrp
            (if (skill) p.cons41s else p.cons41).firstOrNull { it.shiftIdx == cur && it.groupIdx == grp[i] }?.let { c ->
                val n = (0 until p.S).count { grp[it] == c.groupIdx && s[it][j] == cur }
                "${breakdownLabels[fam]}：${sym(cur)}が${n}人（${c.l}〜${c.u}人）"
            }
        }
        "c1" -> c1CellText(c1Shortages(p, s), s, i, j, ::sym, ::day)
        "pref" -> p.wish.getOrNull(i)?.getOrNull(j)?.takeIf { it >= 0 }?.let { "希望は${sym(it)}（今は${sym(cur)}）" }
        "groupViol" -> "${sym(cur)}は${name(i)}の担当外"
        "low" -> if (cur < 0) null else "${sym(cur)}が${count(cur)}回（下限${p.rangeLo[i][cur]}）"
        "high" -> if (cur < 0) null else "${sym(cur)}が${count(cur)}回（上限${p.rangeHi[i][cur]}）"
        "apt" -> if (cur < 0) null else "${sym(cur)}が${count(cur)}回（適切${p.apt[i][cur]}回）"
        "c2" -> if (cur < 0) null else p.cons2.firstOrNull { it.shiftIdx == cur }?.let { "${sym(cur)}が${count(cur)}回（個人の合計${it.count}回）" }
        else -> null
    }
}

/** セル (i,j) を含む、完全に一致した禁止の並び（最初の 1 つ）と開始日。 */
private fun forbiddenRunAt(p: Problem, s: Array<IntArray>, i: Int, j: Int, list: List<com.magi.app.v6.C3>): Pair<IntArray, Int>? {
    for (c in list) {
        val d = c.seq.size
        for (j0 in (j - d + 1).coerceAtLeast(0)..j) {
            if (j0 + d > p.T) continue
            if ((0 until d).all { s[i][j0 + it] == c.seq[it] }) return c.seq to j0
        }
    }
    return null
}

/** シフトボタンの印。[recommended]＝緑の点、[hardRisk]＝置くと必須の族が増える（警告の印）。 */
internal data class ShiftMarks(val recommended: Set<Int> = emptySet(), val hardRisk: Set<Int> = emptySet())

/**
 * セル (i,j) を各候補にしたときの印。おすすめは、必須のあるセルなら必須が減りどの必須族も増えない、
 * 要調整だけのセルなら重み付きの合計が減りどの必須族も増えない候補（違反の無いセルには付けない）。
 * [stillWanted] が false を返したら打ち切る（セルが変わったときの取り消し）。
 */
internal fun evaluateShiftMarks(
    state: MagiState, s: Array<IntArray>, i: Int, j: Int, severity: CellSeverity, candidates: List<Int>,
    stillWanted: () -> Boolean = { true },
): ShiftMarks {
    val cur = s[i][j]
    val base = UnifiedViolationChecker.check(state, s)
    val rec = LinkedHashSet<Int>()
    val risk = LinkedHashSet<Int>()
    val trial = Array(s.size) { s[it].copyOf() }
    for (k in candidates) {
        if (!stillWanted()) return ShiftMarks()
        if (k == cur) continue
        trial[i][j] = k
        val r = UnifiedViolationChecker.check(state, trial)
        val noNewHard = MirrorKeys.hard.none { (r.breakdown[it] ?: 0) > (base.breakdown[it] ?: 0) }
        if (!noNewHard) { risk.add(k); continue }
        val better = when (severity) {
            CellSeverity.HARD -> r.hard < base.hard
            CellSeverity.SOFT -> r.weightedScore < base.weightedScore
            CellSeverity.NONE -> false
        }
        if (better) rec.add(k)
    }
    return ShiftMarks(rec, risk)
}

/**
 * 1 行の回数（例「Aｱ 7(適8)▼ Dﾃ 4(下5)▼」）。この職員の回数の族があるシフトだけ、目安（下限/上限/適切）と ▼▲。
 * [countClasses] は回数キー "i,k" → クラス列（`UiState.countFamilies`）。
 */
internal fun staffCountShort(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, countClasses: Map<String, List<String>>): String {
    val parts = ArrayList<String>()
    for (k in 0 until p.K) {
        val fams = countClasses[VioKey.count(i, k)].orEmpty().map { it.removePrefix("vio-") }
        if (fams.isEmpty()) continue
        val n = s[i].count { it == k }
        val (ref, under) = when {
            "low" in fams -> "下${p.rangeLo[i][k]}" to true
            "high" in fams -> "上${p.rangeHi[i][k]}" to false
            "aptLow" in fams -> "適${p.apt[i][k]}" to true
            "aptHigh" in fams -> "適${p.apt[i][k]}" to false
            else -> (p.cons2.firstOrNull { it.shiftIdx == k }?.let { "計${it.count}" } ?: "") to true
        }
        parts.add("${state.shifts.getOrNull(k)?.kigou ?: "?"} $n${if (ref.isEmpty()) "" else "($ref)"}${if (under) "▼" else "▲"}")
    }
    return parts.joinToString(" ")
}

/** 日送りボタンの日付「7日(水)」（範囲外は null＝押せない）。 */
internal fun adjacentDayLabel(startDate: String, days: Int, j: Int): String? =
    j.takeIf { it in 0 until days }?.let { d -> formatDay(startDate, d).let { f -> if ('/' in f) f.substringAfter('/').replaceFirst("(", "日(") else f } }

/** 希望タブの現在値の注記。 */
internal fun wishTabState(wish: Int?, current: Int): String = when {
    wish == null -> "未登録"
    wish == current -> "反映済"
    else -> "未反映"
}

/** 本人の希望どおりのセルに違反がある＝希望を崩すボタンを既定にせず、「他の人で補う」を先に出す。 */
internal fun isWishDilemma(wish: Int?, current: Int, severity: CellSeverity): Boolean =
    wish != null && wish >= 0 && wish == current && severity != CellSeverity.NONE

internal fun wishKeptLine(wishSymbol: String): String = "本人の希望（$wishSymbol）を守っています"

/**
 * 違反を順に見る巡回の順（日→職員）。必須のセルを先に、要調整は必須が 0 件か [includeSoft] のときだけ。
 * 人員・回数の族はセルを持たないので巡回には入らない（日ヘッダ・名前の横の印から開く）。
 */
internal fun violationTour(ui: UiState, includeSoft: Boolean = false): List<Pair<Int, Int>> {
    val cells = ui.violationCellFamilies.mapNotNull { (key, fams) ->
        val i = VioKey.first(key) ?: return@mapNotNull null
        val j = VioKey.second(key) ?: return@mapNotNull null
        Triple(i, j, fams.any { isHardCellViolation(it) })
    }
    val hard = cells.filter { it.third }
    val pick = if (hard.isNotEmpty() && !includeSoft) hard else cells
    return pick.sortedWith(compareBy({ !it.third }, { it.second }, { it.first })).map { it.first to it.second }
}

/** 巡回の次のセル（今のセルの次。今が巡回に無ければ先頭、末尾なら先頭へ戻る）。空なら null。 */
internal fun nextTourCell(tour: List<Pair<Int, Int>>, current: Pair<Int, Int>?): Pair<Int, Int>? {
    if (tour.isEmpty()) return null
    val at = tour.indexOf(current)
    return tour[if (at < 0) 0 else (at + 1) % tour.size]
}

/** 「他の人で補う」: 本人を動かさず、その日のセルを含む手だけ（本人の希望は守ったまま）。 */
internal fun fixesByOthers(list: List<com.magi.app.v6.FixSuggestion>, day: Int, except: Int): List<com.magi.app.v6.FixSuggestion> =
    list.filter { s -> s.ops.none { it.staff == except } && s.ops.any { it.day == day } }

/**
 * セル詳細（「詳しく」）の行: セルに重なった族をすべて、重い順に「必須・原因」「要調整・原因」で 1 行ずつ。
 */
internal fun cellDetailLines(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, j: Int, families: List<String>): List<String> =
    families.map { fam ->
        val d = familyDetail(state, p, s, i, j, fam) ?: breakdownLabels[fam] ?: fam
        (if (fam in MirrorKeys.hard) "必須・" else "要調整・") + d
    }

/** セルシートの族のクラス。印の無い日でも不足区間の中なら期間の制約を読めるようにする（すでに数に入っている日など）。 */
internal fun sheetCellClasses(display: List<String>, inC1Shortage: Boolean): List<String> =
    if (!inC1Shortage || "vio-c1" in display) display else display + "vio-c1"

/**
 * セルシートの評価の版。状態の 1 行・おすすめの印・回数の 1 行はこの版が変わったときだけ同じ入力から作り直す。
 * 盤面・希望・設定（editRev）・検査世代のどれかが変われば変わる（元に戻す/やり直すも同じ）。
 */
internal data class CellSheetRev(val checkRev: Long, val editRev: Int, val schedule: List<List<Int>>, val wishes: Map<String, Int>)

internal fun cellSheetRev(ui: UiState): CellSheetRev = CellSheetRev(ui.checkRev, ui.editRev, ui.schedule, ui.wishes)

/** その場の直し方探しの状態（計算・チェック待ち／未開始／探索中／完了／失敗）。スピナーは探索中だけ。 */
internal enum class FixPanelState { WAIT_CHECK, NOT_STARTED, RUNNING, DONE, FAILED }

internal fun fixPanelState(running: Boolean, fixSearching: Boolean, doneKey: String, failedKey: String, key: String): FixPanelState = when {
    fixSearching -> FixPanelState.RUNNING
    running -> FixPanelState.WAIT_CHECK
    doneKey == key -> FixPanelState.DONE
    failedKey == key -> FixPanelState.FAILED
    else -> FixPanelState.NOT_STARTED
}

/** 通知の「元に戻す」は、その操作が今も元に戻すの先頭にあるときだけ効く（後の別の操作を戻さない）。 */
internal fun noticeUndoApplies(topSerial: Long?, noticeSerial: Long): Boolean = topSerial != null && topSerial == noticeSerial

/** 操作の通知を出している間、検査の進み具合などの通常の文言では置き換えない（失敗・拒否だけは置き換える）。 */
internal fun messageMayReplaceNotice(noticeShowing: Boolean, isError: Boolean): Boolean = !noticeShowing || isError

/** セルを 1 つ変えたときの Snackbar（「元に戻す」付き）。 */
internal fun cellChangedMessage(name: String, day: Int, symbol: String): String = "$name ${day + 1}日を${symbol}に変更しました"
