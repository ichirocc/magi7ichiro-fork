package com.magi.app.ui

import com.magi.app.model.MagiState
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.RelaxTrial
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.canDo
import com.magi.app.v6.lockTo
import com.magi.app.v6.pinned
import com.magi.app.v6.wishLocked

/**
 * 勤務表のセル編集シートの純ロジック（Compose 非依存＝ホスト JVM で固定する）。
 * ボタンの固定配置・1 行の状態・おすすめの点をここに置き、画面は並べるだけにする。
 */

/** 職員 [i] の担当できるシフト。データ未読込（一覧が空）のときだけ全部。担当できるシフトが 0 件の職員は空のまま（「外」を押せる扱いにしない）。 */
internal fun sheetCanDo(allowedByStaff: List<Set<Int>>, i: Int, shiftCount: Int): Set<Int> =
    if (allowedByStaff.isEmpty()) (0 until shiftCount).toSet() else allowedByStaff.getOrElse(i) { emptySet() }

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

/** [#41] 手動固定のセルに違反が残るときの状態の 1 行の言い方（「直せない」と言わない＝周囲のセルを変えて解消できる場合はある）。 */
internal const val PIN_BLOCKED_NOTE = "このセルは手動固定のため、自動では変更しません"

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
    // [#41] 手動固定のセルは違反を数えて見せたまま、最適化器がこのセルを動かさないことを言う。
    val more = (if (families.size > 1) "（ほか${families.size - 1}件）" else "") +
        (if (i in 0 until p.S && j in 0 until p.T && p.pinned(i, j)) "。$PIN_BLOCKED_NOTE" else "")
    return if (hard) CellStatus(CellSeverity.HARD, "⚠ 必須：$detail$more", "$detail$more")
    else CellStatus(CellSeverity.SOFT, "⚠ 要調整：$detail$more", "$detail$more")
}

private fun familyDetail(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, j: Int, fam: String): String? {
    fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: "?"
    fun name(x: Int) = state.staff.getOrNull(x)?.name ?: "#$x"
    fun group(x: Int) = state.staff.getOrNull(x)?.let { state.groups.getOrNull(it.groupIdx)?.name }?.takeIf { it.isNotBlank() }?.let { "グループ「$it」" } ?: "この職員"
    fun day(d: Int) = DayText.short(state.startDate, d)
    val cur = s.getOrNull(i)?.getOrNull(j) ?: -1
    val count = { k: Int -> s[i].count { it == k } }
    // 氏名とこの日はシートの見出しにある＝ここでは繰り返さない（見出しと違う日・範囲だけ書く）。
    return when (fam) {
        "c3w" -> p.wish.getOrNull(i)?.getOrNull(j + 1)?.takeIf { it >= 0 }?.let {
            "${breakdownLabels[fam]} ${sym(cur)}→${sym(it)}（${DayText.range(state.startDate, j, j + 1)}）"
        }
        "extWish" -> (0 until p.K).filter { p.extBanned(i, j, it) }.takeIf { it.isNotEmpty() }?.let { ks ->
            "拡張希望（${ks.joinToString("・") { sym(it) }}以外）に${sym(cur)}が入っています"
        }
        "c3n", "c3mn" -> {
            val list = if (fam == "c3n") p.cons3n else p.cons3mn
            forbiddenRunAt(p, s, i, j, list)?.let { (seq, j0) ->
                "${breakdownLabels[fam]} ${seq.joinToString("→") { sym(it) }}（${day(j0)}〜${day(j0 + seq.size - 1)}）"
            }
        }
        "c3", "c3m" -> {
            val list = if (fam == "c3") p.cons3 else p.cons3m
            list.firstOrNull { it.seq.firstOrNull() == cur }?.let { c ->
                "${breakdownLabels[fam]} ${c.seq.joinToString("→") { sym(it) }} がこの日から続かない"
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
            // チェッカーと同じ実効値: 不足は covUCell(got=0)、過剰は「どちらかの必要数以下なら過剰でない」ので大きい方。
            val lo = p.covUCell(cur, j, 0)
            val hi = maxOf(p.need1[cur][j], if (p.use2) p.need2[cur][j] else -1)
            val n = (0 until p.S).count { s[it][j] == cur }
            if (fam == "covU") "${sym(cur)}が人員不足（必要${lo}人に${n}人）"
            else "${sym(cur)}が人員過剰（適正${hi}人に${n}人）"
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
        "groupViol" -> "${sym(cur)}は${group(i)}の担当外"
        "low" -> if (cur < 0) null else "${sym(cur)}が${count(cur)}回（下限${p.rangeLo[i][cur]}）"
        "high" -> if (cur < 0) null else if (p.rangeHi[i][cur] == 0 && cur != p.restIdx) {
            if (p.wish.getOrNull(i)?.getOrNull(j) == cur) ZERO_CAP_WISH_TEXT else ZERO_CAP_TEXT
        } else "${sym(cur)}が${count(cur)}回（上限${p.rangeHi[i][cur]}）"
        "apt" -> if (cur < 0) null else "${sym(cur)}が${count(cur)}回（適切${p.apt[i][cur]}回）"
        "c2" -> if (cur < 0) null else p.cons2.firstOrNull { it.shiftIdx == cur }?.let { "${sym(cur)}が${count(cur)}回（個人の合計${it.count}回）" }
        else -> null
    }
}

/** セル (i,j) を含む、完全に一致した禁止の並び（最初の 1 つ）と開始日。 */
internal fun forbiddenRunAt(p: Problem, s: Array<IntArray>, i: Int, j: Int, list: List<com.magi.app.v6.C3>): Pair<IntArray, Int>? {
    for (c in list) {
        val d = c.seq.size
        for (j0 in (j - d + 1).coerceAtLeast(0)..j) {
            if (j0 + d > p.T) continue
            if ((0 until d).all { s[i][j0 + it] == c.seq[it] }) return c.seq to j0
        }
    }
    return null
}

internal const val ZERO_CAP_TEXT = "個人の上限0（入れない指定）のシフトです。残るのは要調整です"
internal const val ZERO_CAP_WISH_TEXT = "本人の希望が個人の上限0（入れない指定）のシフトに載っています。残るのは要調整です"

/** 同じ違反のもう一方のセルの日（板挟みで他の人の手が無いときの行き先）。禁止の並びは一致した並びの他の日、
 *  希望の前日の禁止は印のある前日⇄希望の翌日。チェッカーの印だけから決め、希望は触らない。 */
internal fun violationPartnerDays(p: Problem, s: Array<IntArray>, i: Int, j: Int, families: List<String>, cellFamilies: Map<String, List<String>>): List<Int> {
    val out = LinkedHashSet<Int>()
    for (fam in families) when (fam) {
        "c3n", "c3mn" -> forbiddenRunAt(p, s, i, j, if (fam == "c3n") p.cons3n else p.cons3mn)?.let { (seq, j0) -> for (d in j0 until j0 + seq.size) if (d != j) out += d }
        "c3w" -> if (j + 1 < p.T) out += j + 1
    }
    if (j > 0 && "vio-c3w" in cellFamilies[VioKey.cell(i, j - 1)].orEmpty()) out += j - 1
    return out.filter { it in 0 until p.T }.sorted()
}

internal fun partnerCellLabel(startDate: String, day: Int, single: Boolean): String =
    if (single) "同じ違反のもう一方のセル（${DayText.full(startDate, day)}）を見る" else "同じ違反のほかのセル（${DayText.full(startDate, day)}）を見る"

/** シフトボタンの印。[recommended]＝緑の点、[hardRisk]＝置くと必須の族が増える（警告の印）。 */
internal data class ShiftMarks(val recommended: Set<Int> = emptySet(), val hardRisk: Set<Int> = emptySet())

internal const val SINGLE_CELL_NOTE = "1 マスでは直りません。前後の日の組み合わせが必要です。"

/** おすすめが無く、置ける候補（今の値を除く）がすべて必須を増やす印なら、この 1 マスだけでは直らない。印の計算前（空）は false。 */
internal fun singleCellHopeless(marks: ShiftMarks, candidates: Collection<Int>, current: Int): Boolean {
    val others = candidates.filter { it != current }
    return marks.recommended.isEmpty() && others.isNotEmpty() && others.all { it in marks.hardRisk }
}

/** 全部 ⚠ の理由 1 行（無ければ null）: 本人の希望のセル→希望と違う勤務、前日（翌日）から続く禁止の並び→その日の勤務を名指し。
 *  候補ごとの増える必須の形が混ざるときは言わない（推測を書かない）。 */
internal fun allRiskReason(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, j: Int, marks: ShiftMarks, candidates: Collection<Int>): String? {
    val cur = s[i][j]
    if (!singleCellHopeless(marks, candidates, cur)) return null
    fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: "?"
    if (p.wish.getOrNull(i)?.getOrNull(j) == cur) return "${sym(cur)} は本人の希望なので、ほかへ変えると希望と違う勤務になります"
    val trial = Array(s.size) { s[it].copyOf() }
    var fromPrev = true; var fromNext = true
    for (k in marks.hardRisk) {
        trial[i][j] = k
        val run = forbiddenRunAt(p, trial, i, j, p.cons3n) ?: return null
        val (seq, j0) = run
        if (!(j0 < j && trial[i][j - 1] == s[i][j - 1])) fromPrev = false
        if (!(j0 == j && seq.size > 1)) fromNext = false
    }
    val keep = candidates.filter { it !in marks.hardRisk }.joinToString("・") { sym(it) }
    val tail = if (keep.isEmpty()) "どれに変えても禁止の並びになります" else "$keep 以外はどれも禁止の並びになります"
    return when {
        fromPrev && j > 0 -> "前日が ${sym(s[i][j - 1])} なので、$tail"
        fromNext && j + 1 < p.T -> "翌日が ${sym(s[i][j + 1])} なので、$tail"
        else -> null
    }
}

/** 状態の下の「関連セル: 10/9(金) A4（希望・反映済）」（同じ違反のもう一方のセル。無ければ null）。 */
internal fun relatedCellsLine(state: MagiState, s: Array<IntArray>, i: Int, partners: List<Int>): String? {
    if (partners.isEmpty()) return null
    fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: "?"
    return "関連セル: " + partners.joinToString("、") { d ->
        val w = state.wishes["$i,$d"]
        DayText.full(state.startDate, d) + " " + sym(s[i][d]) + (if (w == null) "" else if (w == s[i][d]) "（希望・反映済）" else "（希望・未反映）")
    }
}

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
 * 回数の行（例「Aｱ 7(適切8)▼ Dﾃ 4(下限5)▼」）。この職員の回数の族があるシフトだけ、目安（下限/上限/適切/合計）と ▼▲。
 * 個人の上限0（休を除く）は「Cｱ 2回（上限0＝入れない指定）▲」（2 つ目からは「（上限0）」）。
 * 行頭の「回数 」込みで 360dp の 2 行（[COUNT_LINE_EM]）に収まらなければ目安を 1 字（下/上/適/計）に縮める。
 * [countClasses] は回数キー "i,k" → クラス列（`UiState.countFamilies`）。
 */
internal fun staffCountShort(state: MagiState, p: Problem, s: Array<IntArray>, i: Int, countClasses: Map<String, List<String>>): String {
    fun build(long: Boolean): String {
        val parts = ArrayList<String>()
        var zeroSeen = false
        for (k in 0 until p.K) {
            val fams = countClasses[VioKey.count(i, k)].orEmpty().map { it.removePrefix("vio-") }
            if (fams.isEmpty()) continue
            val n = s[i].count { it == k }
            val sym = state.shifts.getOrNull(k)?.kigou ?: "?"
            if ("high" in fams && p.rangeHi[i][k] == 0 && k != p.restIdx) {
                parts.add("$sym ${n}回（${if (zeroSeen) "上限0" else ZERO_CAP_NOTE}）▲"); zeroSeen = true; continue
            }
            fun r(l: String, sh: String, v: Int) = "${if (long) l else sh}$v"
            val (ref, under) = when {
                "low" in fams -> r("下限", "下", p.rangeLo[i][k]) to true
                "high" in fams -> r("上限", "上", p.rangeHi[i][k]) to false
                "aptLow" in fams -> r("適切", "適", p.apt[i][k]) to true
                "aptHigh" in fams -> r("適切", "適", p.apt[i][k]) to false
                else -> (p.cons2.firstOrNull { it.shiftIdx == k }?.let { r("合計", "計", it.count) } ?: "") to true
            }
            parts.add("$sym $n${if (ref.isEmpty()) "" else "($ref)"}${if (under) "▼" else "▲"}")
        }
        return parts.joinToString(" ")
    }
    val long = build(true)
    return if (fitsTwoLines("回数 $long", COUNT_LINE_EM)) long else build(false)
}

internal const val ZERO_CAP_NOTE = "上限0＝入れない指定"

/** セルシートの回数の行の 1 行の字数（360dp−左右 16dp＝328dp を bodySmall 12sp で割った数）。 */
internal const val COUNT_LINE_EM = 27.0

/** この職員の個人の上限0（休を除く・担当できるもの）のシフト。シフトボタンの「上限0」の添え字。 */
internal fun zeroCapShifts(p: Problem, i: Int): Set<Int> =
    if (i !in 0 until p.S) emptySet() else (0 until p.K).filter { it != p.restIdx && p.rangeHi[i][it] == 0 && p.canDo(i, it) }.toSet()

/** 希望タブ: 希望が個人の上限0のシフトのときの 1 行（希望は優先して入る。残るのは要調整）。 */
internal fun wishZeroCapLine(wishSymbol: String): String =
    "${wishSymbol}は個人の上限0（入れない指定）のシフトです。希望どおり入れると要調整に数えます"

/** 回数の行が 2 行に収まるか（全角 1・半角 0.5 の字幅で、[emPerLine] 字ぶん × 2 行）。 */
internal fun fitsTwoLines(text: String, emPerLine: Double): Boolean {
    var line = 0.0; var lines = 1
    for (ch in text) {
        val w = if (ch.code < 0x80 || ch in '\uFF61'..'\uFF9F') 0.5 else 1.0
        if (line + w > emPerLine) { lines++; line = w } else line += w
    }
    return lines <= 2
}

/** 日送りボタンの日付「10/7(水)」（範囲外は null＝押せない）。 */
internal fun adjacentDayLabel(startDate: String, days: Int, j: Int): String? =
    j.takeIf { it in 0 until days }?.let { DayText.full(startDate, it) }

/** 希望タブの現在値の注記。 */
internal fun wishTabState(wish: Int?, current: Int): String = when {
    wish == null -> "未登録"
    wish == current -> "反映済"
    else -> "未反映"
}

/**
 * 希望タブの 1 行。拡張希望（この日はこのシフト以外）がある日は、その内容と反映状況も出す
 * （3.645.2、実機報告: 通常希望が無い日に拡張希望があっても「未登録」と言っていた）。
 */
internal fun wishTabLine(wish: Int?, current: Int, extBanned: Set<Int>?, sym: (Int) -> String, pinned: Boolean): String {
    val ext = extBanned?.takeIf { it.isNotEmpty() }?.let { b -> "${b.sorted().joinToString("・") { sym(it) }} 以外（${if (current in b) "未反映" else "反映済"}）" }
    val core = if (wish == null && ext != null) "希望 $ext"
    else "希望 ${if (wish == null) "—" else sym(wish)}（${wishTabState(wish, current)}）" + (if (ext != null) "・$ext" else "")
    return core + (if (pinned) "・手動固定" else "")
}

/** 本人の希望どおりのセルに違反がある＝希望を崩すボタンを既定にせず、「他の人で補う」を先に出す。 */
internal fun isWishDilemma(wish: Int?, current: Int, severity: CellSeverity): Boolean =
    wish != null && wish >= 0 && wish == current && severity != CellSeverity.NONE

internal fun wishKeptLine(wishSymbol: String): String = "本人の希望（$wishSymbol）を守っています"

/** 希望タブの注記の手動固定版（手動固定は希望より強い＝再作成でも希望へ戻さない。`docs/business-logic.md` の手動固定）。 */
internal const val WISH_TAB_PINNED_NOTE = "希望を変えても勤務表のセルはそのままです（未反映になります）。このセルは手動固定のため、再作成しても希望には合わせません（固定を外すと合わせます）。"

/** 職員 [i] の [j] 日（0 始まり）が拡張希望の指定日なら、その日に禁止のシフト記号（シフト一覧の順）。指定日でなければ null。
 *  日だけで決める＝希望を保存しない日（`ExtWishRules.wishBlockedBy`）と同じ。 */
internal fun extWishDayKigou(extWishes: List<ExtWishView>, i: Int, j: Int, shiftKigou: List<String>): List<String>? {
    val hits = extWishes.filter { it.i == i && (j + 1) in it.days }
    if (hits.isEmpty()) return null
    val ks = hits.flatMap { it.kigou }.toSet()
    return shiftKigou.filter { it in ks } + ks.filter { it !in shiftKigou }
}

internal fun extWishDayNote(kigou: List<String>): String =
    "この日は拡張希望${if (kigou.isEmpty()) "" else "（${kigou.joinToString("・")} 以外）"}の指定日なので、希望は入れられません"

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

/** 巡回の 1 件＝必須違反 1 件（族・職員・関連セルの日・見出し）。1 セルが 2 件に属せば 2 回止まる（`report.hard` の数え方と同じ）。
 *  セルで辿れる族だけ（c3n＝並びの全日、c3w＝前日＋希望の翌日、pref/groupViol/extWish＝1 セル）。人員不足は日ヘッダから＝件数は別に添える。 */
internal data class TourItem(val family: String, val staff: Int, val days: List<Int>, val heading: String) {
    val cell: Pair<Int, Int> get() = staff to days.first()
}

internal fun hardViolationItems(state: MagiState, p: Problem, s: Array<IntArray>, cellFamilies: Map<String, List<String>>): List<TourItem> {
    fun sym(k: Int) = state.shifts.getOrNull(k)?.kigou ?: "?"
    fun span(days: List<Int>) = DayText.range(state.startDate, days.first(), days.last())
    val out = LinkedHashMap<String, TourItem>()
    for ((key, fams) in cellFamilies) {
        val i = VioKey.first(key) ?: continue
        val j = VioKey.second(key) ?: continue
        if (i !in 0 until p.S || j !in 0 until p.T) continue
        for (cls in fams) when (val fam = familyOfVioClass(cls)) {
            "c3n" -> forbiddenRunAt(p, s, i, j, p.cons3n)?.let { (seq, j0) ->
                val days = (j0 until j0 + seq.size).toList()
                out.getOrPut("c3n,$i,$j0,${seq.joinToString(",")}") {
                    TourItem(fam, i, days, "${breakdownLabels["c3n"]} ${seq.joinToString("→") { sym(it) }} ・ ${span(days)}")
                }
            }
            "c3w" -> { val days = listOf(j, minOf(j + 1, p.T - 1)).distinct()
                out.getOrPut("c3w,$i,$j") { TourItem(fam, i, days, "${breakdownLabels["c3w"]} ${sym(s[i][j])}→${sym(s[i][days.last()])} ・ ${span(days)}") } }
            "pref", "groupViol", "extWish" -> out.getOrPut("$fam,$i,$j") {
                val wish = if (fam == "pref") p.wish.getOrNull(i)?.getOrNull(j)?.takeIf { it >= 0 }?.let { "（希望は${sym(it)}）" }.orEmpty() else ""
                TourItem(fam, i, listOf(j), "${breakdownLabels[fam]} ${sym(s[i][j])}$wish ・ ${DayText.full(state.startDate, j)}")
            }
            else -> {}
        }
    }
    return out.values.sortedWith(compareBy({ it.days.first() }, { it.staff }))
}

/** 勤務表タブの「必須 N ▼」一覧の 1 行。`tourAt` は [hardViolationItems] の何件目か（行を押すと巡回と同じ移動でそのセルを開く）。 */
internal data class HardListRow(val tourAt: Int, val name: String, val heading: String, val wishOnly: Boolean)

/** 必須違反の一覧（巡回の項目を 1 件 1 行）。希望どうしのぶつかり（関連セルがすべて [wishSelfKeys]）は「希望のまま」を付けて後ろへ。 */
internal fun hardViolationRows(items: List<TourItem>, staffNames: List<String>, wishSelfKeys: Set<String>): List<HardListRow> =
    items.mapIndexed { at, it ->
        HardListRow(at, staffNames.getOrNull(it.staff) ?: "職員${it.staff + 1}", it.heading, it.days.all { d -> "${it.staff},$d" in wishSelfKeys })
    }.sortedBy { it.wishOnly }

/** 巡回の見出し「必須違反 2 / 5 ・ 禁止の並び Dﾃ→A4 ・ 10/8〜10/9」。 */
internal fun tourHeading(items: List<TourItem>, at: Int): String? =
    items.getOrNull(at)?.let { "必須違反 ${at + 1} / ${items.size} ・ ${it.heading}" }

/** 巡回に入らない人員不足の件数の 1 行（0 なら null）。 */
internal fun tourCovULine(covU: Int): String? = if (covU > 0) "ほかに人員不足 ${covU}人（日の印から開けます）" else null

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
internal enum class FixPanelState { WAIT_CHECK, NOT_STARTED, RUNNING, DONE, FAILED, CANCELLED }

/** 直し方の探索の締切。セルのシート（手は 1 件でよい）は短く＝押してから 3 秒以内に答えが返る（Nielsen の 1 秒／10 秒の目安、3.646.0 実機報告）。
 *  ホーム・分析の全体探索は従来どおり 8 秒。 */
internal const val FIX_SEARCH_MS = 8000L
internal const val FIX_SEARCH_QUICK_MS = 3000L

/** 探索中の 1 行（最長の秒数を添えて待ち時間の見当をつけてもらう）。 */
internal fun fixSearchingText(quick: Boolean): String =
    if (quick) "この場所の勤務変更案を探しています…（最長 ${FIX_SEARCH_QUICK_MS / 1000} 秒）" else "勤務変更案を探しています…（最長 ${FIX_SEARCH_MS / 1000} 秒）"

/** 見ている問題がある探索中の 2 行（何を減らす案を探しているか）。 */
internal fun targetSearchingLines(t: FixTarget, label: String): List<String> = listOf(
    "${label}の${t.gapWord}を減らす変更案を探しています",
    "ほかの日や職員の変更が必要な場合は、関連する変更もまとめて確認します。",
)

/** 案の変更箇所（「2人・3セル」）。 */
internal fun changeSizeLine(ops: List<com.magi.app.v6.FixCell>): String = "変更箇所：${ops.map { it.staff }.distinct().size}人・${ops.size}セル"

/** 変更案の探索を止めるボタン（勤務表の作成の「やめる」と区別する）。 */
internal const val CANCEL_FIX_SEARCH_BUTTON = "変更案の検索を中止"
internal const val FIX_SEARCH_CANCELLED = "探索を止めました。"
internal const val FIX_SEARCH_RETRY_BUTTON = "もう一度探す"

internal fun fixPanelState(running: Boolean, fixSearching: Boolean, doneKey: String, failedKey: String, key: String, cancelledKey: String = ""): FixPanelState = when {
    fixSearching -> FixPanelState.RUNNING
    running -> FixPanelState.WAIT_CHECK
    doneKey == key -> FixPanelState.DONE
    failedKey == key -> FixPanelState.FAILED
    cancelledKey == key -> FixPanelState.CANCELLED
    else -> FixPanelState.NOT_STARTED
}

/** [S6] セルシートから設定の緩和へ渡す状態。OFFER＝ホームで見つかった組の起点の窓か手順のセル（同じ結果を同じ確定で開く。セルごとに試算はしない）、
 *  SEARCHING＝背景で探している、NO_WALL＝探し終えて組が無い、STOPPED＝途中で止めた／FAILED＝失敗した（どちらもやり直しを出す）、
 *  UNAVAILABLE＝試算できない盤面（未割当など。理由を出す）。 */
internal enum class RelaxHandoff { NONE, SEARCHING, OFFER, NO_WALL, STOPPED, UNAVAILABLE, FAILED }

internal fun relaxHandoff(
    r: RelaxTrial.Result?, searching: Boolean, noWall: Boolean, i: Int, j: Int, stopped: Boolean = false,
    unavailable: String? = null, failed: Boolean = false,
): RelaxHandoff = when {
    r != null && ((r.staff == i && j in r.window) || r.moves.any { it.staff == i && it.day == j }) -> RelaxHandoff.OFFER
    r != null -> RelaxHandoff.NONE
    searching -> RelaxHandoff.SEARCHING
    unavailable != null -> RelaxHandoff.UNAVAILABLE
    noWall -> RelaxHandoff.NO_WALL
    failed -> RelaxHandoff.FAILED
    stopped -> RelaxHandoff.STOPPED
    else -> RelaxHandoff.NONE
}

internal fun relaxUnavailableText(reason: String): String = "設定を緩める試算はできません（$reason）"

/** [S6] 背景の試算の結果を反映してよいか: 最新の試算で、始めたときのデータが今も同じとき（古い失敗で今の状態を上書きしない）。 */
internal fun <C> relaxOutcomeApplies(seq: Long, latestSeq: Long, ctx: C, ctxNow: C?): Boolean = seq == latestSeq && ctx == ctxNow

internal fun relaxHandoffLine(r: RelaxTrial.Result, ui: UiState): String =
    "設定を緩めると、この${relaxTarget(r, ui).what}を解消できる見込みです（上限 ${r.relaxes.size}件）" +
        if (r.rr > 0) "。他の必須違反 ${r.rr}件 は残ります" else ""

/** 通知の「元に戻す」は、その操作が今も元に戻すの先頭にあるときだけ効く（後の別の操作を戻さない）。 */
internal fun noticeUndoApplies(topSerial: Long?, noticeSerial: Long): Boolean = topSerial != null && topSerial == noticeSerial

/** 操作の通知を出している間、検査の進み具合などの通常の文言では置き換えない（失敗・拒否だけは置き換える）。 */
internal fun messageMayReplaceNotice(noticeShowing: Boolean, isError: Boolean): Boolean = !noticeShowing || isError

/** 手動固定を付けたセルが担当外／必須違反を抱えているときの一文（付けない＝空）。固定の挙動は変えない＝知らせるだけ。 */
internal fun pinRegisterHint(canDo: Boolean, families: List<String>): String = when {
    !canDo -> PIN_NOT_CANDO_HINT
    families.any { familyOfVioClass(it) in PIN_HARD_CELL_FAMILIES } -> PIN_HARD_HINT
    else -> ""
}
// 手動固定の通知「…を手動固定しました（自動では変更しません）」の後ろに付く＝同じことを繰り返さない。
internal const val PIN_HARD_HINT = "このセルには必須違反があります。"
internal const val PIN_NOT_CANDO_HINT = "担当外のシフトなので、必須違反が残ります。"
private val PIN_HARD_CELL_FAMILIES = setOf("c3n", "c3w", "pref", "groupViol", "extWish")

/** セルを 1 つ変えたときの Snackbar（「元に戻す」付き）。 */
internal fun cellChangedMessage(name: String, startDate: String, day: Int, symbol: String): String = "${name}さんの ${DayText.full(startDate, day)} を ${symbol} に変更しました"

/** ちら見に出す上位 [n] シフト: 今の割当・希望を先に、残りは担当できるものを枠の順で。 */
internal fun peekShifts(shown: List<Int>, canDo: Set<Int>, current: Int, wish: Int?, n: Int = 4): List<Int> =
    (listOfNotNull(current.takeIf { it >= 0 }, wish?.takeIf { it >= 0 }) + shown.filter { it in canDo })
        .filter { it in shown }.distinct().take(n)

/** ちら見の操作行に並べるシフトの数: 「詳しく ▸」(64dp) と同じ行に 52dp＋間 6dp で入るだけ（1〜4）。360dp 以上の端末は 4。 */
internal fun peekPickCount(contentWidthDp: Int): Int = ((contentWidthDp - 64 + 6) / 58).coerceIn(1, 4)

/** ちら見の見出し: 巡回の見出しから日付の範囲を落とす（日付は 2 段目にある）。 */
internal fun peekHeading(tourHeading: String): String = tourHeading.substringBeforeLast(" ・ ")

internal const val PEEK_HARD_RISK_NOTE = "⚠＝変えると必須違反"

/** [S6] 既にある組の手順がこのセルを動かすなら、その行き先（新しく試算しない）。 */
internal fun peekRecommendation(r: RelaxTrial.Result?, i: Int, j: Int): Int? =
    r?.moves?.firstOrNull { it.staff == i && it.day == j }?.to

/** 1 つのセルに重なった問題の見出し（違反セルの半自動修正仕様 §3-2・§5、3.665.0）。2 件以上のときだけ。 */
internal const val ADDRESS_CHECKING_TEXT = "案ごとに、このセルの問題が解消するか確認しています…"

/**
 * 開いた時点の職員 ID（[staffId]・[exceptId]、0 以下や null＝ID なし）を今の並びの番号に引き直す（統合仕様 5.1）。
 * 並べ替えのあとは新しい番号、削除されていれば null（別の人を探さない）。ID が無ければ番号のまま。
 */
internal fun resolveFixFocus(f: FixFocus, staffId: Int?, exceptId: Int?, staffIds: List<Int>): FixFocus? {
    fun at(index: Int?, id: Int?): Int? = if (index == null || id == null || id <= 0) index else staffIds.indexOf(id)
    val s = at(f.staff, staffId); val x = at(f.exceptStaff, exceptId)
    if (s == -1 || x == -1) return null
    return if (s == f.staff && x == f.exceptStaff) f else f.copy(staff = s, exceptStaff = x)
}

internal const val FIX_STAFF_GONE_NOTE = "この職員は一覧から削除されたため、変更案を探せません。画面を閉じて開き直してください。"

internal const val SAVE_RETRY_BUTTON = "もう一度保存する"

internal const val REVIEW_CHECKING_TEXT = "変更後の影響を確認しています…"

internal const val PICK_PROBLEM_FIRST_NOTE = "問題を選ぶと、その問題がこのセルで解消する変更案を探します。"

internal fun problemChoiceHeading(n: Int): String? = if (n >= 2) "この勤務に関係する問題が $n 件あります。どの問題の直し方を確認しますか？" else null

/** 選んだ問題（族）を減らす案か。[family] が null＝選んでいない（すべての案）。 */
internal fun fixAddresses(s: com.magi.app.v6.FixSuggestion, family: String?): Boolean =
    family == null || s.diff.any { (k, d) -> k == family && d < 0 }

/** 選んだ問題を減らす案が無いとき（ほかの問題の案はある）。 */
internal fun noFixForProblemLine(label: String): String = "選んだ問題（$label）を減らす案は見つかりませんでした。ほかの問題を選ぶと、その直し方を確認できます。"

/**
 * 画面で見ている 1 つの問題（2026-10-14 利用者レビュー「今回の問題がどう変わるかを最上段に」）。
 * 回数＝[staff]×[shift]（[day] は null）、人員＝[shift]×[day]（[staff] は null）。[target] は下限・目標・上限・必要人数・適正人数、
 * [basis] はその名、[under]＝足りない側。
 */
data class FixTarget(val staff: Int?, val shift: Int, val day: Int?, val target: Int, val under: Boolean, val basis: String,
                     /** 複数の日をまとめて見る（同じ勤務の不足の日々と、それぞれの必要人数）。空＝1 つの枠。 */
                     val days: List<Int> = emptyList(), val needs: List<Int> = emptyList()) {
    val unit: String get() = if (staff != null) "回" else "人"
    val multi: Boolean get() = days.isNotEmpty()
    fun countOf(schedule: List<List<Int>>): Int = when {
        staff != null -> schedule.getOrNull(staff).orEmpty().count { it == shift }
        multi -> days.sumOf { j -> schedule.count { it.getOrNull(j) == shift } }
        else -> schedule.count { it.getOrNull(day ?: -1) == shift }
    }
    /** 日ごとの差の合計（まとめて見るとき）。日をまたいで足し引きしない＝ある日の過剰で別の日の不足を相殺しない。 */
    fun gapOf(schedule: List<List<Int>>): Int = days.indices.sumOf { n ->
        val c = schedule.count { it.getOrNull(days[n]) == shift }
        (if (under) needs[n] - c else c - needs[n]).coerceAtLeast(0)
    }
    /** 盤面に案のセル代入を当てて数え直す＝評価器と同じ数え方。 */
    fun effect(schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>): CountEffect {
        val after = schedule.map { it.toMutableList() }
        for (op in ops) after.getOrNull(op.staff)?.let { r -> if (op.day in r.indices) r[op.day] = op.toShift }
        return CountEffect(countOf(schedule), countOf(after), target, under, unit, if (multi) gapOf(schedule) to gapOf(after) else null)
    }
    val gapWord: String get() = if (under) "不足" else "超過"
}

/** 対象の数の変化。[gapBefore]/[gapAfter] は基準までの差（0 未満は 0）。 */
data class CountEffect(val before: Int, val after: Int, val target: Int, val under: Boolean, val unit: String = "回",
                       /** まとめて見るときの日ごとの差の合計（前, 後）。null＝1 つの枠。 */
                       val gaps: Pair<Int, Int>? = null) {
    private fun gap(c: Int) = (if (under) target - c else c - target).coerceAtLeast(0)
    val gapBefore: Int get() = gaps?.first ?: gap(before)
    val gapAfter: Int get() = gaps?.second ?: gap(after)
    val fixes: Boolean get() = gapAfter < gapBefore
    /** 「A4を2回増やす」。対象が変わらない案は「A4は変わりません」。 */
    fun title(label: String): String = when {
        after > before -> "${label}を${after - before}${unit}増やす"
        after < before -> "${label}を${before - after}${unit}減らす"
        else -> "${label}は変わりません"
    }
    fun countLine(): String = "$before$unit → $after$unit"
    fun gapLine(): String = "${if (under) "不足" else "超過"} $gapBefore$unit → $gapAfter$unit"
    fun afterNote(basis: String): String = when {
        gaps != null && gapAfter == 0 -> "対象の日はすべて${basis}を${if (under) "満たします" else "超えなくなります"}"
        gaps != null -> "適用後も、合計 ${gapAfter}${unit}${if (under) "不足" else "超過"}"
        gapAfter == 0 && under && unit == "人" -> "${basis.removeSuffix("人数")} ${target}人を満たします"
        gapAfter == 0 && unit == "人" -> "${basis.removeSuffix("人数")} ${target}人以内になります"
        gapAfter == 0 && under -> "適用後は${basis}に届きます"
        gapAfter == 0 -> "適用後は${basis}以内になります"
        under -> "適用後も、${basis}まで${gapAfter}${unit}不足"
        else -> "適用後も、${basis}を${gapAfter}${unit}超えます"
    }
}

/** 回数の問題の目標（職員×シフト）。null＝回数の過不足ではない、または基準が無い。 */
internal fun countTargetOf(vio: String?, limits: Triple<Int?, Int?, Int?>): Pair<Int, Boolean>? {
    val (lo, hi, apt) = limits
    return when (vio) {
        "vio-low" -> lo?.let { it to true }
        "vio-aptLow" -> apt?.let { it to true }
        "vio-high" -> hi?.let { it to false }
        "vio-aptHigh" -> apt?.let { it to false }
        else -> null
    }
}

private fun basisOfCount(vio: String?): String = when (vio) { "vio-low" -> "下限"; "vio-high" -> "上限"; else -> "目標" }

/** 職員×シフトの回数の問題（集計の職員別セル・職員の勤務状況から）。 */
internal fun countFixTarget(countViolations: Map<String, String>, staff: Int, shift: Int, limits: Triple<Int?, Int?, Int?>): FixTarget? {
    val vio = countViolations["$staff,$shift"]
    val (t, under) = countTargetOf(vio, limits) ?: return null
    return FixTarget(staff, shift, null, t, under, basisOfCount(vio))
}

/** シフト×日の人員の問題（日ヘッダ・集計の日別セルから）。[need] は (必要, 適正)。 */
internal fun needFixTarget(needFamilies: Map<String, List<String>>, shift: Int, day: Int, need: Pair<Int, Int>?): FixTarget? {
    val classes = needFamilies["$shift,$day"].orEmpty()
    need ?: return null
    return when {
        "vio-covU" in classes -> FixTarget(null, shift, day, need.first, true, "必要人数")
        "vio-covO" in classes -> FixTarget(null, shift, day, need.second, false, "適正人数")
        else -> null
    }
}

/** 対象の名（回数＝記号、人員＝「11/3のAア」、まとめて＝「Dテ（2日）」）。 */
internal fun fixTargetLabel(t: FixTarget, sym: String, startDate: String): String =
    if (t.multi) "$sym（${t.days.size}日）" else t.day?.let { "${DayText.short(startDate, it)}の$sym" } ?: sym

/** 同じ勤務の人員不足の日々をまとめて見る対象（2 日以上のときだけ）。[need] は (勤務, 日) → (必要, 適正)。 */
internal fun multiNeedTarget(needFamilies: Map<String, List<String>>, shift: Int, need: ((Int, Int) -> Pair<Int, Int>?)?): FixTarget? {
    need ?: return null
    val days = needFamilies.filter { (key, fams) -> "vio-covU" in fams && VioKey.first(key) == shift }.keys.mapNotNull { VioKey.second(it) }.sorted()
        .filter { need(shift, it) != null }
    if (days.size < 2) return null
    return FixTarget(null, shift, null, 0, true, "必要人数", days, days.map { need(shift, it)!!.first })
}

/** まとめて調整するボタンの名（2 日なら「両日」）。 */
internal fun multiFixButton(n: Int): String = (if (n == 2) "両日" else "${n}日") + "の不足をまとめて調整する案を探す"

/** 対象の見出し 2 行: 「下限まで、あと8回です」「現在 7回 ／ 下限 15回」。人員は「必要人数より1人少ない状態です」「配置 0人 ／ 必要 1人」。 */
internal fun fixTargetHead(t: FixTarget, count: Int, schedule: List<List<Int>> = emptyList(), startDate: String = ""): List<String> {
    if (t.multi) return listOf("${t.days.size}日で合計${t.gapOf(schedule)}人不足しています（日ごとの不足の合計）",
        "対象の日：" + t.days.joinToString("・") { DayText.short(startDate, it) })
    val gap = (if (t.under) t.target - count else count - t.target).coerceAtLeast(0)
    return if (t.staff != null) listOf(
        if (t.under) "${t.basis}まで、あと${gap}回です" else "${t.basis}を${gap}回超えています",
        "現在 ${count}回 ／ ${t.basis} ${t.target}回")
    else listOf(
        if (t.under) "${t.basis}より${gap}人少ない状態です" else "${t.basis}より${gap}人多い状態です",
        "配置 ${count}人 ／ ${t.basis.removeSuffix("人数")} ${t.target}人")
}

/** 適用前の確認の「今回の目的」。 */
internal fun fixTargetPurpose(t: FixTarget, staffName: String?, label: String): String =
    if (t.multi) "${label}の${t.gapWord}をまとめて解消する"
    else if (t.staff != null) "${staffName?.let { "${it}さんの" } ?: ""}${label}${t.gapWord}を減らす"
    else "${label}${t.gapWord}を解消する"

/** 対象の問題を変える案を先に（集計・日の印から探した結果の並べ替え。同順位は元の効果順）。 */
internal fun rankForTarget(list: List<com.magi.app.v6.FixSuggestion>, schedule: List<List<Int>>, t: FixTarget?): List<com.magi.app.v6.FixSuggestion> =
    if (t == null) list else list.sortedBy { if (t.effect(schedule, it.ops).fixes) 0 else 1 }

/** 「ほかの問題への影響」の行（対象の族は上に出すので除く）。必須違反の増減を先頭に。カードの先頭に [hardChangeLine] があれば必須は繰り返さない（[hardShown]）。 */
internal fun otherEffectLines(s: com.magi.app.v6.FixSuggestion, skipFamilies: Set<String>, hardShown: Boolean = false): List<String> {
    val hard = "必須違反：" + (unitDeltaText(s.diff, MirrorKeys.hard) ?: if (s.deltaHard == 0) "変わらない" else if (s.deltaHard < 0) "${-s.deltaHard}件減る" else "${s.deltaHard}件増える")
    return (if (hardShown) emptyList() else listOf(hard)) + s.diff.filter { (k, d) -> d != 0 && k !in skipFamilies && !(hardShown && k in MirrorKeys.hard) }.map { (k, d) ->
        "${breakdownLabels[k] ?: k}：${kotlin.math.abs(d)}${unitOf(k)}${if (d < 0) "減る" else "増える"}"
    }
}

/** 対象の族（「ほかの問題への影響」から除く）。 */
internal fun fixTargetFamilies(t: FixTarget): Set<String> = if (t.staff != null) setOf("low", "high", "apt") else setOf("covU", "covO")

/** 対象の問題を変えない案の見出し（開くまで畳む）。 */
internal fun otherFixHeading(n: Int): String = "別の問題を改善する案（${n}件）"
internal fun otherFixNote(label: String, t: FixTarget): String =
    "${label}の${if (t.staff != null) "回数" else "人数"}は${if (t.under) "増えません" else "減りません"}"

/** 対象の問題を変える案の見出し。 */
internal fun thisFixHeading(label: String, t: FixTarget): String = "${label}を${if (t.under) "増やす" else "減らす"}案"

/** 対象を減らす案が無いとき（人員の問題）。 */
internal fun noNeedFixLine(label: String, t: FixTarget, timedOut: Boolean = false): String =
    // 時間切れは「案が無い」と分けて言う（統合仕様 INV-08＝探索不足と案なしを混同しない）。
    if (timedOut) "${label}の${t.gapWord}を減らす案は、制限時間内には見つかりませんでした。もう一度探すと続きを探せます。"
    else "${label}の${t.gapWord}を減らす案は、今回の検索では見つかりませんでした。" +
        if (t.under) "担当できる職員と、その日の希望・固定勤務をご確認ください。" else "その日に入っている職員の希望・固定勤務をご確認ください。"

/** 回数の過不足の要点（集計セルの詳細）。「▼4」だけでは今の回数か不足数か分からない＝現在・基準・差を言葉で。 */
internal fun countGapLine(vio: String, count: Int, limits: Triple<Int?, Int?, Int?>): String? {
    val (lo, hi, apt) = limits
    return when {
        vio == "vio-low" && lo != null -> "現在 ${count}回／下限 ${lo}回 ・ 不足 ${(lo - count).coerceAtLeast(0)}回"
        vio == "vio-high" && hi != null -> "現在 ${count}回／上限 ${hi}回 ・ 超過 ${(count - hi).coerceAtLeast(0)}回"
        vio == "vio-aptLow" && apt != null -> "現在 ${count}回／目標 ${apt}回 ・ 不足 ${(apt - count).coerceAtLeast(0)}回"
        vio == "vio-aptHigh" && apt != null -> "現在 ${count}回／目標 ${apt}回 ・ 超過 ${(count - apt).coerceAtLeast(0)}回"
        else -> null
    }
}

/** 選んだ問題（族）が 1 案でどう変わるか。案の先頭に出す。 */
internal fun familyEffectLine(s: com.magi.app.v6.FixSuggestion, family: String, label: String): String? =
    s.diff.firstOrNull { it.first == family }?.second?.let { d -> if (d < 0) "$label：${-d}件減る" else if (d > 0) "$label：${d}件増える" else null }

/** 適用ボタンの下に添える注記（ボタンの文言は短く＝折り返さない）。 */
internal const val APPLY_UNDO_NOTE = "適用したあとでも元に戻せます。"

/** 案のカードのボタン（見るだけ。適用は確認の画面で）。 */
internal const val REVIEW_CHANGE_BUTTON = "変更内容を確認"

/** 確認の画面の適用ボタン（件数を言う）。 */
internal fun applyCountButton(n: Int): String = if (n >= 2) "この${n}件の変更を適用" else APPLY_CHANGE_BUTTON

/** 確認の画面を閉じるボタン（案の一覧から来たときは一覧へ戻る）。 */
internal const val BACK_TO_FIXES_BUTTON = "別案を見る"

/** 必須 0 の結果の札。「配布可」とは言い切らず、配布前の確認へ案内する（押すと配布前の確認が開く）。 */
internal fun readyBadgeLabel(soft: Long, breakdown: Map<String, Int> = emptyMap()): String =
    if (soft > 0) "要調整 ${unitTotals(breakdown, MirrorKeys.soft) ?: "${soft}件"}・配布前の確認" else "必須違反 0・配布前の確認"

/** 集計期間（見出しの 2 行目）。 */
internal fun periodLine(startDate: String, days: Int): String? =
    if (days <= 0 || startDate.isBlank()) null else "対象期間：" + DayText.range(startDate, 0, days - 1)

/** 盤面に案のセル代入を当てた後の盤面。 */
internal fun boardAfter(schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>): List<List<Int>> {
    val after = schedule.map { it.toMutableList() }
    for (op in ops) after.getOrNull(op.staff)?.let { r -> if (op.day in r.indices) r[op.day] = op.toShift }
    return after
}

/**
 * 案で人数が変わる枠（日×勤務）の行（2026-10-14 利用者レビュー「どの日の不足を直す案か」と「別の日への影響」を分ける）。
 * [first]＝見ている日のほかの勤務、[second]＝別の日。[skip]＝見ている枠（勤務, 日）は上に出すので除く。
 * 必要・適正の判定は設定の値で言い、値が無い枠は人数だけ（「安全に変更できます」とは言わない）。
 */
internal fun headcountImpact(
    schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>, shiftSymbols: List<String>, startDate: String,
    needLimits: ((Int, Int) -> Pair<Int, Int>?)?, skip: Pair<Int, Int>? = null,
): Pair<List<String>, List<String>> {
    val after = boardAfter(schedule, ops)
    val same = ArrayList<String>(); val other = ArrayList<String>()
    for (j in ops.map { it.day }.distinct().sorted()) {
        val shifts = (ops.filter { it.day == j }.flatMap { listOfNotNull(schedule.getOrNull(it.staff)?.getOrNull(j), it.toShift) }).distinct().sorted()
        for (k in shifts) {
            if (skip == k to j) continue
            val b = schedule.count { it.getOrNull(j) == k }; val a = after.count { it.getOrNull(j) == k }
            if (a == b) continue
            val lim = needLimits?.invoke(k, j)
            val note = when {
                lim == null -> ""
                a < lim.first -> "（必要 ${lim.first}人を下回ります）"
                a > lim.second -> "（適正 ${lim.second}人を超えます）"
                else -> "（必要 ${lim.first}人を満たしたまま）"
            }
            val line = "${DayText.short(startDate, j)}の${shiftSymbols.getOrNull(k) ?: "$k"}：${b}人 → ${a}人$note"
            if (skip != null && j == skip.second) same += line else other += line
        }
    }
    return same to other
}

/** 案で回数が変わる（または変わらない）勤務を職員ごとに言う。2 か所以上を変える理由の事実（例「休の回数は変わりません（12回のまま）」）。 */
internal fun staffCountNotes(schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>, staffNames: List<String>, shiftSymbols: List<String>): List<String> {
    if (ops.size < 2) return emptyList()
    val after = boardAfter(schedule, ops)
    return ops.map { it.staff }.distinct().flatMap { i ->
        val row = schedule.getOrNull(i).orEmpty(); val rowA = after.getOrNull(i).orEmpty()
        val name = staffNames.getOrNull(i) ?: "#$i"
        ops.filter { it.staff == i }.flatMap { listOfNotNull(row.getOrNull(it.day), it.toShift) }.distinct().sorted().map { k ->
            val b = row.count { it == k }; val a = rowA.count { it == k }
            val sym = shiftSymbols.getOrNull(k) ?: "$k"
            if (a == b) "${name}さんの「$sym」の回数は変わりません（${b}回のまま）" else "${name}さんの「$sym」：${b}回 → ${a}回"
        }
    }
}

/** 同じ勤務のほかの日で、案のあとも残る人員の過不足（「11/25のDテ不足は残ります」）。[board] は見る盤面（案の前なら案を当てた後）。 */
internal fun remainingSameShift(
    needFamilies: Map<String, List<String>>, board: List<List<Int>>, t: FixTarget, needLimits: ((Int, Int) -> Pair<Int, Int>?)?,
    shiftSymbols: List<String>, startDate: String,
): List<String> {
    val day = t.day ?: return emptyList()
    val cls = if (t.under) "vio-covU" else "vio-covO"
    val sym = shiftSymbols.getOrNull(t.shift) ?: "${t.shift}"
    return needFamilies.filter { (key, fams) -> cls in fams && VioKey.first(key) == t.shift }.keys.mapNotNull { VioKey.second(it) }
        .filter { it != day }.sorted().mapNotNull { j ->
            val lim = needLimits?.invoke(t.shift, j) ?: return@mapNotNull null
            val n = board.count { it.getOrNull(j) == t.shift }
            val gap = if (t.under) lim.first - n else n - lim.second
            if (gap > 0) "${DayText.short(startDate, j)}の${sym}は、${if (t.under) "${gap}人不足" else "${gap}人多い状態"}のままです" else null
        }
}

/** 同じ勤務の期間全体の過不足（日ごとに基準までの差を 0 で打ち切って足す。設定の値が無い日は数えない）。値が無ければ null。 */
internal fun shiftPeriodGap(board: List<List<Int>>, shift: Int, under: Boolean, needLimits: ((Int, Int) -> Pair<Int, Int>?)?): Int? {
    val f = needLimits ?: return null
    var g = 0
    for (j in 0 until (board.firstOrNull()?.size ?: 0)) {
        val lim = f(shift, j) ?: continue
        val n = board.count { it.getOrNull(j) == shift }
        g += if (under) maxOf(0, lim.first - n) else maxOf(0, n - lim.second)
    }
    return g
}

/** 案のカードの「期間全体」の行（日の人員の対象）。見ている日が直っても期間全体で減らなければ、移るだけと言う（2026-10-15 利用者「思考誘導が混乱する」）。 */
internal fun periodGapLine(sym: String, under: Boolean, before: Int, after: Int): String =
    "${sym}の${if (under) "不足" else "過剰"}（期間全体）：${before}人 → ${after}人" + when {
        after > before -> "＝期間全体では増えます"
        after == before -> "＝別の日へ移るだけです"
        else -> ""
    }

/** 案のカードの先頭: 必須違反の変化（族ごと）。変わらなければ null（2026-10-15: 禁止の並びを崩す効き目がカードの最後に埋もれていた）。 */
internal fun hardChangeLine(diff: List<Pair<String, Int>>): String? =
    familyDeltaList(diff.filter { it.first in MirrorKeys.hard }).takeIf { it.isNotEmpty() }?.let { "必須違反の変化：$it" }

/** パネルの上に 1 回だけ出す、同じ勤務のほかの日の過不足（カードごとに繰り返さない）。無ければ null。 */
internal fun otherDaysGapLine(
    needFamilies: Map<String, List<String>>, board: List<List<Int>>, t: FixTarget, needLimits: ((Int, Int) -> Pair<Int, Int>?)?,
    shiftSymbols: List<String>, startDate: String,
): String? {
    val day = t.day ?: return null
    val f = needLimits ?: return null
    val cls = if (t.under) "vio-covU" else "vio-covO"
    val days = needFamilies.filter { (key, fams) -> cls in fams && VioKey.first(key) == t.shift }.keys.mapNotNull { VioKey.second(it) }
        .filter { it != day }.sorted().mapNotNull { j ->
            val lim = f(t.shift, j) ?: return@mapNotNull null
            val n = board.count { it.getOrNull(j) == t.shift }
            val gap = if (t.under) lim.first - n else n - lim.second
            if (gap > 0) "${DayText.short(startDate, j)}（${gap}人）" else null
        }
    if (days.isEmpty()) return null
    val sym = shiftSymbols.getOrNull(t.shift) ?: "${t.shift}"
    return "ほかに${sym}が${if (t.under) "足りない" else "多い"}日：${days.joinToString("・")}"
}

/** 自動保存の状態の 1 行（適用後の案内）。 */
internal fun saveStateLine(s: SaveState): String = "保存：" + when (s) {
    SaveState.Saved -> "完了"; SaveState.Saving -> "保存中"; SaveState.Failed -> "失敗（「$SAVE_RETRY_BUTTON」で保存し直してください）"; SaveState.Dirty -> "未保存（まもなく保存します）"
}

/** 勤務の人員のシート（集計の「計（期間）」）で、過不足のある日が 2 日以上のときの問い。 */
internal fun shiftDayPickHeading(): String = "どの日を調整しますか？"

/** 選ぶ行（日）: 「11/24(火)　配置 0人／必要 1人　1人不足」。 */
internal fun shiftDayPickRow(ui: UiState, k: Int, j: Int, under: Boolean, need: Pair<Int, Int>?): String =
    DayText.full(ui.startDate, j) + "　" + dayPickRow(ui, j, CoverageMark(k, under), need).substringAfter("　")

/** 休みの人数の過剰（休の勤務の人員のシート）: 先に設定の意味を確かめてもらう。[allZero]＝どの日も適正 0 人。 */
internal fun restOverIntro(allZero: Boolean): List<String> = listOfNotNull(
    if (allZero) "各日の適正人数が0人と表示されています。意図した設定か、確認できます。" else "休みの人数が、設定上の適正人数を超えている日があります。",
    "休みの人数を減らす前に、個人の休日数・希望・連続勤務への影響もご確認ください。",
)

/** 休みの人数の行: 「11/13(金)　休み 2人／適正 0人」（値が無ければ「未設定」）。 */
internal fun restOverRow(ui: UiState, k: Int, j: Int, need: Pair<Int, Int>?): String =
    "${DayText.full(ui.startDate, j)}　休み ${ui.schedule.count { it.getOrNull(j) == k }}人／適正 " + (need?.let { "${it.second}人" } ?: "未設定")

/** 日の人員のシートで、過不足のある勤務が 2 つ以上のときの問い。 */
internal fun dayPickHeading(marks: List<CoverageMark>): String = when {
    marks.all { it.under } -> "どの勤務の不足を確認しますか？"
    marks.none { it.under } -> "どの勤務の過剰を確認しますか？"
    else -> "どの勤務の過不足を確認しますか？"
}

/** 選ぶ行: 「Aア　配置 0人／必要 1人　1人不足」。 */
internal fun dayPickRow(ui: UiState, j: Int, m: CoverageMark, need: Pair<Int, Int>?): String {
    val sym = ui.shiftSymbols.getOrNull(m.shift) ?: "${m.shift}"
    val now = ui.schedule.count { it.getOrNull(j) == m.shift }
    return if (m.under) "$sym　配置 ${now}人／必要 ${need?.first ?: "?"}人" + (need?.let { "　${(it.first - now).coerceAtLeast(0)}人不足" } ?: "")
    else "$sym　配置 ${now}人／適正 ${need?.second ?: "?"}人" + (need?.let { "　${(now - it.second).coerceAtLeast(0)}人多い" } ?: "")
}

/** 見ている問題（集計の職員別・日別セル、日ヘッダの印から）。セルのシート（日あり・職員あり）や「他の人で補う」は null。 */
internal fun panelTarget(ui: UiState, cv: ConditionsView?, f: FixFocus): FixTarget? {
    cv ?: return null
    val k = f.shift ?: return null
    return when {
        f.allDays && f.staff == null -> multiNeedTarget(ui.needFamilies, k, cv::needCellLimits)
        f.staff != null && f.day == null -> countFixTarget(ui.countViolations, f.staff, k, cv.staffCellLimits(f.staff, k))
        f.day != null && f.exceptStaff == null -> needFixTarget(ui.needFamilies, k, f.day, cv.needCellLimits(k, f.day))
        else -> null
    }
}

/** 「変更を反映しました」の行: 見ている問題（前 → 今）、ほかに人数が変わった枠（今の値）、保存、残る問題、探し直したこと。 */
internal fun appliedLines(ui: UiState, a: AppliedFix, target: FixTarget?, label: String, needLimits: ((Int, Int) -> Pair<Int, Int>?)?): List<String> {
    val out = ArrayList<String>()
    target?.let { t ->
        val e = t.effect(a.before, a.ops)
        out += "今回の問題：${appliedStatusWord(e)}（$label：${e.countLine()}、${if (e.gapAfter == 0) e.afterNote(t.basis) else "${t.gapWord} ${e.gapAfter}${t.unit}"}）"
    }
    val (same, other) = headcountImpact(a.before, a.ops, ui.shiftSymbols, ui.startDate, needLimits, target?.day?.let { target.shift to it })
    out += same + other
    // 内訳があれば単位ごとに（件・人・回・pt を 1 つの数に足さない＝統合仕様 INV-07）。
    fun t(b: Map<String, Int>?, fams: List<String>, n: Int) = b?.let { unitTotals(it, fams) ?: "なし" } ?: "${n}件"
    if (a.hardBefore >= 0) out += "必須違反（勤務表全体）：${t(a.breakdownBefore, MirrorKeys.hard, a.hardBefore)} → ${t(a.breakdownAfter, MirrorKeys.hard, a.hardAfter)}"
    if (a.softBefore >= 0) out += "要調整（勤務表全体）：${t(a.breakdownBefore, MirrorKeys.soft, a.softBefore)} → ${t(a.breakdownAfter, MirrorKeys.soft, a.softAfter)}"
    out += saveStateLine(ui.saveState)
    val next = when {
        target?.day != null -> remainingSameShift(ui.needFamilies, ui.schedule, target, needLimits, ui.shiftSymbols, ui.startDate)
        target != null -> target.effect(a.before, a.ops).takeIf { it.gapAfter > 0 }?.let { listOf(remainingGapLine(label, target, it.gapAfter)) }.orEmpty()
        else -> emptyList()
    }
    if (next.isNotEmpty()) out += "次に確認すること：" + next.joinToString("。") + "。"
    out += "勤務表が変わったため、変更案を探し直しました（下の案は今の勤務表のものです）。"
    return out
}

/** 当てたあとに残る差（回数＝「A4は、下限まであと2回です」、まとめて見る日々＝「Dテ（2日）は、合計 3人不足です」）。 */
internal fun remainingGapLine(label: String, t: FixTarget, gap: Int): String =
    if (t.multi) "${label}は、合計 $gap${t.unit}${t.gapWord}です" else "${label}は、${t.basis}まであと$gap${t.unit}です"

/**
 * セルを 1 つ変える前の確認（2026-10-14 利用者レビュー「問題を確認 → 変更方法を選ぶ → 影響を確認して適用」）。
 * 前後の報告を正式な検査（[UnifiedViolationChecker]）で比べ、違反の同一性（[com.magi.app.v6.ViolationIdentity]）で
 * このセルの問題が解消するか、ほかで解消する問題、新しく生じる問題を言う。値はすべて評価した結果（仮の値を書かない）。
 */
internal data class CellChangeReview(
    val change: String, val neighbors: String, val thisCell: List<String>, val othersResolved: List<String>,
    val appeared: List<String>, val hardLine: String, val headcount: List<String>, val counts: List<String>, val wishPin: List<String>,
)

internal fun cellChangeReview(
    state: MagiState, s: Array<IntArray>, i: Int, j: Int, k: Int, staffNames: List<String>, shiftSymbols: List<String>,
    limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)?, needLimits: ((Int, Int) -> Pair<Int, Int>?)?, pinned: Boolean,
    targets: List<FixTarget> = emptyList(),
): CellChangeReview {
    fun sym(x: Int?) = x?.let { shiftSymbols.getOrNull(it) } ?: "—"
    val name = staffNames.getOrNull(i) ?: "#$i"
    val cur = s[i][j]
    val trial = Array(s.size) { s[it].copyOf() }.also { it[i][j] = k }
    val before = UnifiedViolationChecker.check(state, s)
    val after = UnifiedViolationChecker.check(state, trial)
    val diff = com.magi.app.v6.ViolationIdentity.diff(before, after)
    fun label(key: com.magi.app.v6.ViolationKey) = violationKeyLabel(key, staffNames, shiftSymbols, state.startDate)
    val here = com.magi.app.v6.ViolationIdentity.keys(before).filter { it.space == com.magi.app.v6.ViolationKey.Space.CELL && it.a == i && it.b == j }
    val ops = listOf(com.magi.app.v6.FixCell(i, j, k))
    val sl = s.map { it.toList() }
    // 日の人数・回数の問題から開いたときは、その数の前後も言う（このセルに違反が無くても見ている問題は変わる）。
    val targetLines = targets.map { t -> "${fixTargetReviewName(t, staffNames, shiftSymbols, state.startDate)}：${t.effect(sl, ops).countLine()}" }
    val thisCell = targetLines + here.map { key -> "${breakdownLabels[key.family] ?: key.family}：" + if (key in diff.resolved) "解消します" else "残ります" }
    val nb = listOfNotNull(
        (j - 1).takeIf { it >= 0 }?.let { "${DayText.short(state.startDate, it)}：${sym(s[i][it])}" },
        "${DayText.short(state.startDate, j)}：${sym(k)}",
        (j + 1).takeIf { it < s[i].size }?.let { "${DayText.short(state.startDate, it)}：${sym(s[i][it])}" },
    ).joinToString(" → ")
    val (_, other) = headcountImpact(s.map { it.toList() }, listOf(com.magi.app.v6.FixCell(i, j, k)), shiftSymbols, state.startDate, needLimits)
    val counts = listOf(cur, k).filter { it >= 0 }.distinct().map { x ->
        val b = s[i].count { it == x }; val a = trial[i].count { it == x }
        val (lo, hi, apt) = limits?.invoke(i, x) ?: Triple(null, null, null)
        val basis = when {
            lo != null && hi != null && lo == hi -> "／下限＝上限 ${lo}回"
            lo != null && hi != null -> "／範囲 $lo〜${hi}回"
            lo != null -> "／下限 ${lo}回"
            hi != null -> "／上限 ${hi}回"
            apt != null -> "／目標 ${apt}回"
            else -> ""
        }
        "本人の${sym(x)}回数：${b}回 → ${a}回$basis"
    }
    val wish = state.wishes["$i,$j"]
    val wishPin = listOfNotNull(
        wish?.let { w -> if (w == k) "希望（${sym(w)}）どおりになります" else if (w == cur) "希望（${sym(w)}）と異なる勤務になります" else "希望（${sym(w)}）とは、変更の前後とも異なります" },
        if (pinned) "このセルは手動固定されています（自動作成・調整ではこの勤務を変えません）" else null,
    ).ifEmpty { listOf("このセルに希望・手動固定はありません") }
    return CellChangeReview(
        change = "${name}さん　${DayText.full(state.startDate, j)} ${sym(cur)} → ${sym(k)}",
        neighbors = nb,
        thisCell = thisCell.ifEmpty { listOf(NO_CELL_VIOLATION) },
        othersResolved = diff.resolved.filter { it !in here }.map(::label),
        appeared = diff.appeared.map(::label),
        hardLine = "必須違反（勤務表全体）：${hardText(before.breakdown, before.hard.toLong())} → ${hardText(after.breakdown, after.hard.toLong())}",
        headcount = other,
        counts = counts,
        wishPin = wishPin,
    )
}

internal const val NO_CELL_VIOLATION = "このセル自体の違反はありません"

/** 確認の画面で見ている問題の名（「人員過剰（12/16のDﾃ）」「上限（福澤さんのB4）」）。 */
internal fun fixTargetReviewName(t: FixTarget, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): String {
    val sym = shiftSymbols.getOrNull(t.shift) ?: "${t.shift}"
    return if (t.staff != null) "${t.basis}（${staffNames.getOrNull(t.staff) ?: "#${t.staff}"}さんの$sym）"
    else "${breakdownLabels[if (t.under) "covU" else "covO"]}（${fixTargetLabel(t, sym, startDate)}）"
}

/** 違反 1 件の名と場所（「必須・禁止の並び（福澤さん 11/29）」「人員不足（11/3のAア）」「下限割れ（福澤さんのB4）」）。 */
internal fun violationKeyLabel(key: com.magi.app.v6.ViolationKey, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): String {
    fun sym(x: Int) = shiftSymbols.getOrNull(x) ?: "$x"
    val place = when (key.space) {
        com.magi.app.v6.ViolationKey.Space.CELL -> "${staffNames.getOrNull(key.a) ?: "#${key.a}"}さん ${DayText.short(startDate, key.b)}"
        com.magi.app.v6.ViolationKey.Space.NEED -> "${DayText.short(startDate, key.b)}の${sym(key.a)}"
        com.magi.app.v6.ViolationKey.Space.COUNT -> "${staffNames.getOrNull(key.a) ?: "#${key.a}"}さんの${sym(key.b)}"
    }
    return (if (key.hard) "必須・" else "") + (breakdownLabels[key.family] ?: key.family) + "（$place）"
}

/** 案で別の場所に新しく生まれる必須違反（確認の画面の「新たな必須違反」。正式な検査の前後で数える）。 */
internal fun newHardLabels(state: MagiState, s: Array<IntArray>, ops: List<com.magi.app.v6.FixCell>, staffNames: List<String>, shiftSymbols: List<String>): List<String> {
    val trial = Array(s.size) { s[it].copyOf() }
    for (op in ops) if (op.staff in trial.indices && op.day in trial[op.staff].indices) trial[op.staff][op.day] = op.toShift
    return com.magi.app.v6.ViolationIdentity.newHard(UnifiedViolationChecker.check(state, s), UnifiedViolationChecker.check(state, trial))
        .map { violationKeyLabel(it, staffNames, shiftSymbols, state.startDate) }
}

/** 確認の画面の「希望への影響」と「必要な相談」（行の希望の注記から。希望と異なる勤務になる人がいれば相談の対象）。 */
internal fun previewWishLines(rows: List<ChangeRow>): Pair<String, String> {
    val off = rows.filter { it.wish.startsWith("希望（") }
    val on = rows.count { it.wish == "希望どおり" }
    val wish = when {
        off.isNotEmpty() -> "希望と異なる勤務になる：" + off.joinToString("、") { "${it.staff}さん ${it.date}" }
        on > 0 -> "希望どおりになる勤務が ${on}件あります。希望と異なる勤務にはなりません"
        else -> "変更する勤務に希望はありません"
    }
    val consult = if (off.isEmpty()) "必要な相談：なし" else "必要な相談：" + off.map { it.staff }.distinct().joinToString("、") { "${it}さん" } + "（希望と異なる勤務になる日）"
    return wish to consult
}

/** 確認の画面の相談事項（当てずに相談してから決める）。 */
internal fun consultCellChange(r: CellChangeReview, staff: Int, day: Int, staffName: String, date: String?) =
    ConsultItem(r.change, (r.thisCell + r.appeared.map { "新しく生じる: $it" }).joinToString("・"), staff, day, staffName = staffName, date = date)

/** セルのシートの問題の一文（族ごと）。前日・翌日との並びは並びの族の説明。 */
internal fun cellProblemSentence(fam: String): String = when (fam) {
    "c3n" -> "前後の勤務との組み合わせが禁止条件に該当しています"
    "c3mn" -> "前後の勤務との組み合わせが、避けたい並びに該当しています"
    "c3w" -> "希望の前日に禁止されている勤務です"
    "pref" -> "本人の希望と違う勤務です"
    "extWish" -> "拡張希望で除いた勤務です"
    "groupViol" -> "この職員のグループが担当しない勤務です"
    "covU" -> "この日のこの勤務の人数が必要人数に足りません"
    "covO" -> "この日のこの勤務の人数が適正人数を超えています"
    else -> "${breakdownLabels[fam] ?: fam}に該当しています"
}

/** 並びの違反の日々を「11/28(土) Dテ → 11/29(日) B4」で。[days] はこのセルと相手の日。 */
internal fun sequenceLine(startDate: String, row: List<Int>, days: List<Int>, shiftSymbols: List<String>): String? =
    days.takeIf { it.size >= 2 }?.sorted()?.joinToString(" → ") { d -> "${DayText.full(startDate, d)} ${row.getOrNull(d)?.let { shiftSymbols.getOrNull(it) } ?: "—"}" }

/** 今月の勤務回数（セルのシート）: 今の勤務と、回数に過不足のある勤務。「Cオ：現在4回／目標6回」。 */
internal fun cellCountLines(ui: UiState, i: Int, current: Int, limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)?): List<String> {
    val row = ui.schedule.getOrNull(i).orEmpty()
    val ks = (listOf(current) + ui.countViolations.keys.filter { VioKey.first(it) == i }.mapNotNull { VioKey.second(it) }).filter { it >= 0 }.distinct()
    return ks.map { k ->
        val (lo, hi, apt) = limits?.invoke(i, k) ?: Triple(null, null, null)
        val n = row.count { it == k }
        val basis = when (ui.countViolations["$i,$k"]) {
            "vio-low" -> lo?.let { "／下限${it}回" }; "vio-high" -> hi?.let { "／上限${it}回" }
            else -> (apt?.let { "／目標${it}回" } ?: lo?.let { "／下限${it}回" } ?: hi?.let { "／上限${it}回" })
        } ?: ""
        "${ui.shiftSymbols.getOrNull(k) ?: "$k"}：現在${n}回$basis"
    }
}

/** 手動固定の説明（通常の修正操作と分ける）。 */
internal const val PIN_TITLE = "この勤務を自動変更しない"
internal const val PIN_NOTE = "固定すると、自動作成・調整ではこの勤務を変更しません。現在の違反が解消されるわけではありません。"

/** 担当外の勤務の理由（自分で勤務を選ぶ画面）。 */
internal const val NOT_CANDO_NOTE = "「外」はこの職員のグループが担当しない勤務です（担当可否の設定）。選ぶと担当外シフトの必須違反になります。"

/** 上部の札の 2 行目以降: 自動作成の状態と、今の勤務表の評価（2026-10-14 利用者レビュー「未計算」を分ける）。 */
internal fun boardStatusLines(engineRan: Boolean, hasResult: Boolean, checked: Boolean, hard: Long, soft: Long, breakdown: Map<String, Int> = emptyMap(), ranBeforeEdit: Boolean = false): Pair<String, String> =
    (if (engineRan) "自動作成：済み" else if (ranBeforeEdit && hasResult) "自動作成：済み（そのあと手で変更あり）" else if (hasResult) "自動作成：未実行（手で編集）" else "自動作成：未実行") to
        (if (!checked) "評価：未評価" else if (hard > 0) "評価：必須違反 ${hardText(breakdown, hard)}"
        else if (soft > 0) "評価：要調整 ${unitTotals(breakdown, MirrorKeys.soft) ?: "${soft}件"}" else "評価：違反なし")

/**
 * 案がこのセルの問題を解消するか（2026-10-14 利用者レビュー: 族の全体の件数が減るだけでは、選んだセルが直るとは限らない）。
 * この場所の鍵＝このセル・その日のその勤務の人数・本人のその勤務の回数（[family] を選べばその族だけ）が、案の後の正式な報告から消えるか。
 */
internal fun resolvesAtCell(state: MagiState, s: Array<IntArray>, before: com.magi.app.v6.ViolationReport, ops: List<com.magi.app.v6.FixCell>, i: Int, j: Int, family: String?): Boolean {
    val cur = s.getOrNull(i)?.getOrNull(j) ?: return false
    fun here(keys: Set<com.magi.app.v6.ViolationKey>) = keys.filter { k ->
        (family == null || k.family == family) && when (k.space) {
            com.magi.app.v6.ViolationKey.Space.CELL -> k.a == i && k.b == j
            com.magi.app.v6.ViolationKey.Space.NEED -> k.a == cur && k.b == j
            com.magi.app.v6.ViolationKey.Space.COUNT -> k.a == i && k.b == cur
        }
    }
    val b = here(com.magi.app.v6.ViolationIdentity.keys(before))
    if (b.isEmpty()) return false
    val trial = Array(s.size) { s[it].copyOf() }
    for (op in ops) if (op.staff in trial.indices && op.day in trial[op.staff].indices) trial[op.staff][op.day] = op.toShift
    val after = com.magi.app.v6.ViolationIdentity.keys(UnifiedViolationChecker.check(state, trial))
    return b.any { it !in after }
}

// ===== 自動と担当者の分担（2026-10-14 利用者レビュー「どこまで自動で進み、どこで担当者が判断するか」） =====

/** 勤務表を作成する画面の要点（作成前）。[issues]＝つくる前に確かめる項目があるか。時間は設定値（探索）と固定の追加枠。 */
internal fun preRunPlanLines(startDate: String, days: Int, staff: Int, issues: Boolean, pins: Int, budgetSec: Int, reserveSec: Int): List<String> = listOfNotNull(
    periodLine(startDate, days),
    "職員：${staff}人",
    "登録条件：" + if (issues) "確認が必要な項目があります（下の一覧）" else "つくる前に直す項目はありません",
    "今回、自動で行うこと：勤務表の生成、最適化、仕上げ、評価",
    "保持するもの：登録条件と手動固定" + if (pins > 0) "（手動固定 ${pins}件）" else "",
    "探索時間：${budgetSec}秒／仕上げ用の追加枠：${reserveSec}秒",
)

/** 作成を始めたあとの約束（一手ごとに確認を求めない・条件の変更は別の判断）。 */
internal const val PRE_RUN_AUTO_NOTE = "開始後は、探索の途中で一手ごとの確認は求めません。希望・必要人数・固定条件の変更は、別の判断として扱います。"
internal const val PRE_RUN_TITLE = "勤務表を作成する"
internal const val PRE_RUN_PROCEED = "勤務表を作成"
internal const val PRE_RUN_CHECK_CONDITIONS = "条件を確認する"

/** 要調整の内訳（族ごとに件数か pt。多い順に [max] 種まで）。 */
internal fun softSummary(breakdown: Map<String, Int>, max: Int = 4): String {
    val soft = com.magi.app.v6.MirrorKeys.soft.mapNotNull { k -> breakdown[k]?.takeIf { it > 0 }?.let { k to it } }
        .sortedByDescending { (k, n) -> (com.magi.app.v6.MirrorKeys.weights[k] ?: 1.0) * n }
    if (soft.isEmpty()) return "なし"
    return soft.take(max).joinToString("・") { (k, n) -> "${breakdownLabels[k] ?: k} $n${unitOf(k)}" } + if (soft.size > max) " ほか${soft.size - max}種" else ""
}

/** 作成後の結果（作成の完了は配布できることと同じ意味ではない）。 */
internal fun creationResultLines(hard: Long, breakdown: Map<String, Int>, save: SaveState): List<String> = listOfNotNull(
    "必須違反：${unitTotals(breakdown, MirrorKeys.hard) ?: if (hard == 0L) "なし" else "${hard}件"}",
    "要調整：${softSummary(breakdown)}",
    saveStateLine(save),
    if (hard == 0L) "作成が終わったことは、配布できることと同じではありません。配布前の確認で中身を確かめてください。" else null,
)
internal const val CREATION_DONE_TITLE = "勤務表の作成が終わりました"

/** 適用後の「今回の問題」の状態（解消／減少／残存）。 */
/** 見ている問題の数が厳密に減るか（統合仕様 INV-03＝適用は対象の改善と正式な門の両方）。対象を持たない案は判定しない。 */
internal fun improvesTarget(t: FixTarget?, schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>): Boolean =
    t == null || t.effect(schedule, ops).fixes

internal const val NOT_IMPROVING_TARGET_MESSAGE = "見ている問題が減らないため、この提案は適用しませんでした。「変更案を探す」で探し直してください"

/** 確認の画面の「新たな必須違反」（null＝評価していない＝なしと言わない、統合仕様 INV-10）。 */
internal fun newHardLine(newHard: List<String>?): String =
    "新たな必須違反：" + when { newHard == null -> "未評価（適用の直前に検査します）"; newHard.isEmpty() -> "なし"; else -> newHard.joinToString("、") }

internal fun appliedStatusWord(e: CountEffect): String = when { e.gapAfter == 0 -> "解消"; e.gapAfter < e.gapBefore -> "減少"; else -> "残存" }

/** 相談事項に登録しても、連絡や了承の記録にはならない（記録だけ）。 */
internal const val CONSULT_NOT_CONTACT_NOTE = "相談事項に登録しても、本人への連絡や了承済みにはなりません。"

/** [operator_ux §5] 「なおし方を見る」用：ある不足枠(日×シフト)に1タップで入れられる候補職員。 */
data class FixCandidate(val staffIndex: Int, val name: String, val groupSymbol: String, val fromRest: Boolean)

internal fun shortageCandidates(st: MagiState, p: Problem, sched: Array<IntArray>, dayIndex: Int, shiftIndex: Int, rest: Int?): List<FixCandidate> {
    if (shiftIndex !in 0 until p.K || dayIndex !in 0 until p.T) return emptyList()
    // [3.603.0] rest は null 許容＝下の `== rest` は休が無ければ常に false（fromRest フラグが単に立たないだけ）
    val out = ArrayList<FixCandidate>()
    for (i in 0 until p.S) {
        if (i !in sched.indices || dayIndex !in sched[i].indices) continue
        // 担当できない・上限 0・拡張希望で禁止のシフトは出さない（最適化器と同じ mayPlaceAt 基準）。
        if (!p.mayPlaceAt(i, dayIndex, shiftIndex)) continue
        if (p.pinned(i, dayIndex)) continue   // 手動固定は自動でも変えない
        // 上限に届いている人は入れると上限超過になる。
        if (p.rangeHi[i][shiftIndex] != Int.MAX_VALUE && sched[i].count { it == shiftIndex } >= p.rangeHi[i][shiftIndex]) continue
        if (sched[i][dayIndex] == shiftIndex) continue   // すでにそのシフト
        // [監査A5] 実現可能な希望のみ固定扱い（#11①整合: 不可能希望のセルはエンジン同様に可動）。
        if (p.wishLocked(i, dayIndex) && p.lockTo(i, dayIndex) != shiftIndex) continue
        // [3.401.0] ここまでは「担当できる・希望で固定されていない」だけの判定で、押しても必須違反が
        //   減らない候補が混ざっていた。CoverageDiagnosis(3.156.0) が「空き番」と数えるのと同じ2条件を
        //   足して、**実際に動かせる人だけ**を出す。
        //   ① 移すと禁止連続(c3n)になる人は出さない。
        if (p.makesForbiddenRun(sched, i, dayIndex, shiftIndex)) continue
        //   ② 抜けると元のシフトに穴が空く人は「動かせる人」ではない（玉突きが要る＝この画面の手には余る）。
        val from = sched[i][dayIndex]
        if (from in 0 until p.K) {
            val cnt = (0 until p.S).count { it in sched.indices && dayIndex in sched[it].indices && sched[it][dayIndex] == from }
            if (p.covUCell(from, dayIndex, cnt - 1) > p.covUCell(from, dayIndex, cnt)) continue
        }
        val g = st.staff.getOrNull(i)?.groupIdx ?: -1
        out.add(FixCandidate(i, st.staff.getOrNull(i)?.name ?: "#$i", st.groups.getOrNull(g)?.kigou ?: "", sched[i][dayIndex] == rest))
    }
    // 休みの人（動かしやすい）を先頭に。
    out.sortBy { if (it.fromRest) 0 else 1 }
    return out
}
