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

enum class CellSeverity { HARD, SOFT, NONE }

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

internal const val ZERO_CAP_TEXT = "対象外（上限0）のシフトです。残るのは要調整です"
internal const val ZERO_CAP_WISH_TEXT = "本人の希望が対象外（上限0）のシフトに載っています。残るのは要調整です"

/** 同じ違反のもう一方のセルの日（板挟みで他の人の手が無いときの行き先）。禁止の並びは一致した並びの他の日、
 *  希望の前日の禁止は印のある前日⇄希望の翌日。チェッカーの印だけから決め、希望は触らない。 */
internal fun violationPartnerDays(p: Problem, s: Array<IntArray>, i: Int, j: Int, families: List<String>, cellFamilies: Map<String, List<String>>): List<Int> {
    val out = LinkedHashSet<Int>()
    for (fam in families) when (fam) {
        "c3n", "c3mn" -> forbiddenRunAt(p, s, i, j, if (fam == "c3n") p.cons3n else p.cons3mn)?.let { (seq, j0) -> for (d in j0 until j0 + seq.size) if (d != j) out += d }
        "c3w" -> if (j + 1 < p.T) out += j + 1
        "c3" -> { val d = p.cons3.maxOfOrNull { it.seq.size }?.coerceIn(2, 3) ?: 2; for (x in j - d + 1..j + d - 1) if (x != j) out += x }
    }
    if (j > 0 && "vio-c3w" in cellFamilies[VioKey.cell(i, j - 1)].orEmpty()) out += j - 1
    return out.filter { it in 0 until p.T }.sorted()
}

internal fun partnerCellLabel(startDate: String, day: Int, single: Boolean): String =
    if (single) "同じ違反のもう一方のセル（${DayText.full(startDate, day)}）を見る" else "同じ違反のほかのセル（${DayText.full(startDate, day)}）を見る"

/** シフトボタンの印。[recommended]＝緑の点、[hardRisk]＝置くと必須の族が増える（警告の印）。 */
internal data class ShiftMarks(val recommended: Set<Int> = emptySet(), val hardRisk: Set<Int> = emptySet())

internal const val SINGLE_CELL_NOTE = "1 マスでは直りません。前後の日の組み合わせが必要です。"

/** このセルが「足りない側」の族（当日の自分の勤務の人員不足・自分の回数の下限割れ／目標未満）。セルの問題として赤で出さない。 */
internal fun shortSideFamilies(needClasses: List<String>, countClasses: List<String>): Set<String> = buildSet {
    if ("vio-covU" in needClasses) add("covU")
    if ("vio-low" in countClasses) add("low")
    if ("vio-aptLow" in countClasses) add("apt")
}

/** 足りない側のセルの注意の 1 行。[covGapAfter]＝変えたあとの当日の不足人数（人員不足の族が無ければ null）。 */
internal fun shortSideCaution(name: String, sym: String, dayShort: String, covGapAfter: Int?, countAfter: Int, basis: String, target: Int?): String =
    "${name}さんの${sym}は必要な勤務です（" + when {
        covGapAfter != null -> "変えると $dayShort の $sym の不足が ${covGapAfter}人に増えます"
        target != null -> "変えると $sym が ${countAfter}回になり、$basis ${target}回をさらに下回ります"
        else -> "変えると $sym の回数がさらに足りなくなります"
    } + "）"

/** 足りない側のセルの主ボタン（当日のその勤務に入れる人を探す）。 */
internal fun shortSideSearchButton(dayShort: String, sym: String): String = "$dayShort の $sym に入れる人を探す"

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

internal const val ZERO_CAP_NOTE = com.magi.app.v6.ZERO_CAP_LABEL

/** セルシートの回数の行の 1 行の字数（360dp−左右 16dp＝328dp を bodySmall 12sp で割った数）。 */
internal const val COUNT_LINE_EM = 27.0

/** この職員の個人の上限0（休を除く・担当できるもの）のシフト。シフトボタンの「上限0」の添え字。 */
internal fun zeroCapShifts(p: Problem, i: Int): Set<Int> =
    if (i !in 0 until p.S) emptySet() else (0 until p.K).filter { it != p.restIdx && p.rangeHi[i][it] == 0 && p.canDo(i, it) }.toSet()

/** 希望タブ: 希望が個人の上限0のシフトのときの 1 行（希望は優先して入る。残るのは要調整）。 */
internal fun wishZeroCapLine(wishSymbol: String): String =
    "${wishSymbol}は対象外（上限0）のシフトです。希望どおり入れると要調整に数えます"

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
internal const val WISH_TAB_PINNED_NOTE = "希望を変えてもセルはそのままです（未反映）。手動固定のセルなので、再作成しても希望に合わせません。"

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

/** 巡回の 1 件＝必須違反 1 件（族・職員・関連セルの日・見出し）。1 セルが 2 件に属せば 2 回止まる（`report.hard` の数え方と同じ）。
 *  セルで辿れる族だけ（c3n＝並びの全日、c3w＝前日＋希望の翌日、pref/groupViol/extWish＝1 セル）。人員不足は日ヘッダから＝件数は別に添える。 */
data class TourItem(val family: String, val staff: Int, val days: List<Int>, val heading: String,
                    val syms: List<String> = emptyList(), val dayMd: String = "") {
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
    return out.values.map { it.copy(syms = it.days.map { d -> sym(s[it.staff][d]) }, dayMd = DayText.short(state.startDate, it.days.first())) }
        .sortedWith(compareBy({ it.days.first() }, { it.staff }))
}

/** 勤務表タブの「必須 N ▼」一覧の 1 行。`tourAt` は [hardViolationItems] の何件目か（行を押すと巡回と同じ移動でそのセルを開く）。 */
internal data class HardListRow(val tourAt: Int, val name: String, val heading: String, val wishOnly: Boolean, val line: String = heading)

/** [3.714.0] 問題一覧の 1 行の上限（デザイン例「10/10のAｱが1人不足」「福澤 10/1 希望の前日」）。 */
internal const val PROBLEM_LINE_MAX = 18

/**
 * [3.714.0] 問題 1 件を 18 字までの 1 行に（族ごとの型）。[sym]＝その日の勤務、[nextSym]＝並びの翌日の勤務、[n]＝人数・回数、
 * [syms]＝並びの全日の勤務（禁止の並びは「夜→夜→夜の並び」と全部つなぐ。null なら [sym]→[nextSym]）。
 * [3.716.0] 名前と日の前置きは残し、収まらないときは型の側を「…」で切る（前置きを落とすと行の区別がつかない＝INV-01）。前置きだけで 17 字を超えれば前置きを切る。
 */
internal fun problemLine(family: String, name: String, day: String, sym: String, nextSym: String? = null, n: Int = 1, syms: List<String>? = null): String {
    fun fit(prefix: String, body: String): String {
        if (prefix.isBlank()) return body
        if (prefix.length > PROBLEM_LINE_MAX - 1) return prefix.take(PROBLEM_LINE_MAX - 1) + "…"
        val room = PROBLEM_LINE_MAX - prefix.length - 1
        return when {
            body.length <= room -> "$prefix $body"
            room >= 2 -> "$prefix ${body.take(room - 1)}…"
            else -> "$prefix…"
        }
    }
    val run = syms?.takeIf { it.size >= 2 } ?: listOfNotNull(sym, nextSym)
    val line = when (family) {
        "covU" -> "${day}の${sym}が${n}人不足"
        "c3w" -> fit(name, "$day 希望の前日")
        "low" -> fit(name, "${sym}があと${n}回不足")
        "high" -> fit(name, "${sym}が${n}回多い")
        "c3n" -> fit(name, if (run.size >= 2) "${run.joinToString("→")}の並び" else "禁止の並びです")
        "pref" -> fit("$name $day", "希望と違う勤務です")
        "extWish" -> fit("$name $day", "拡張希望と違う勤務")
        "groupViol" -> fit("$name $day", "担当外の勤務")
        else -> fit("$name $day", breakdownLabels[family] ?: "必須違反")
    }
    return if (line.length <= PROBLEM_LINE_MAX) line else line.take(PROBLEM_LINE_MAX - 1) + "…"
}

/** 巡回の項目の 1 行（[problemLine]、並びは全日の勤務をつなぐ）。 */
internal fun tourProblemLine(it: TourItem, name: String): String =
    problemLine(it.family, name, it.dayMd, it.syms.firstOrNull() ?: "?", it.syms.getOrNull(1), syms = it.syms)

/** 必須違反の一覧（巡回の項目を 1 件 1 行）。希望どうしのぶつかり（関連セルがすべて [wishSelfKeys]）は「希望のまま」を付けて後ろへ。 */
internal fun hardViolationRows(items: List<TourItem>, staffNames: List<String>, wishSelfKeys: Set<String>): List<HardListRow> =
    items.mapIndexed { at, it ->
        val name = staffNames.getOrNull(it.staff) ?: "職員${it.staff + 1}"
        HardListRow(at, name, it.heading, it.days.all { d -> "${it.staff},$d" in wishSelfKeys }, tourProblemLine(it, name))
    }.sortedBy { it.wishOnly }

/** [3.718.0] 勤務表タブの下のパネルの状態。閉じる／セルの編集／問題一覧（2026-10-18 ユーザー指示「制約違反を見ながら勤務表を修正したい」）。 */
internal enum class PanelMode { CLOSED, CELL, LIST }

/** パネルへの操作。一覧を開く／セルをタップ／一覧の行をタップ／「一覧へ」／閉じる（✕・戻る・余白のタップ）。 */
internal enum class PanelAction { OPEN_LIST, CELL_TAP, ROW_TAP, TO_LIST, CLOSE }

/** パネルの次の状態。セルのタップは閉じずに編集へ、行のタップは一覧のまま（表をそのセルへ送るだけ）、「一覧へ」は一覧へ戻る。 */
internal fun nextPanelMode(mode: PanelMode, action: PanelAction): PanelMode = when (action) {
    PanelAction.OPEN_LIST, PanelAction.TO_LIST -> PanelMode.LIST
    PanelAction.CELL_TAP -> PanelMode.CELL
    PanelAction.ROW_TAP -> if (mode == PanelMode.CLOSED) PanelMode.CLOSED else mode
    PanelAction.CLOSE -> PanelMode.CLOSED
}

/** 画面の状態からパネルの状態を出す（セルを開いていれば編集が優先＝「一覧へ」でセルを離すと一覧へ戻る）。 */
internal fun panelModeOf(listOpen: Boolean, cellOpen: Boolean): PanelMode =
    if (cellOpen) PanelMode.CELL else if (listOpen) PanelMode.LIST else PanelMode.CLOSED

/** 勤務表の前に出す画面の高さの上限＝画面の 1/3（ちら見・全体・一覧とも。2026-10-18 ユーザー決定「1/3固定・今の閉じ方のまま」）。 */
internal fun panelMaxHeightDp(screenHeightDp: Int): Int = screenHeightDp / 3

internal const val PANEL_LIST_BUTTON = "一覧へ"

/** 問題一覧の 1 行。必須は巡回の何件目か（[tourAt]）を持ち、要調整は null。押すと [cell]（関連セルは [days]）へ表を送る。 */
internal data class ProblemRow(val family: String, val staff: Int, val days: List<Int>, val line: String, val hard: Boolean,
                               val tourAt: Int? = null, val wishOnly: Boolean = false) {
    val cell: Pair<Int, Int> get() = staff to days.first()
}

/** 要調整の見出し「要調整 N件」（0 なら出さない）。 */
internal fun softRowsHeading(n: Int): String? = if (n > 0) "要調整 ${n}件" else null

/**
 * 残っている問題の一覧＝必須（[hardViolationRows] の順）に続けて要調整のセル（セル×族ごとに 1 行、日→職員→族の順）。
 * 人員・回数の族はセルを持たないので入らない（人員不足は末尾の 1 行、[hardListCovULine]）。
 */
internal fun problemRows(hardRows: List<HardListRow>, items: List<TourItem>, cellFamilies: Map<String, List<String>>,
                         staffNames: List<String>, symbols: List<String>, schedule: List<List<Int>>, startDate: String): List<ProblemRow> {
    fun name(i: Int) = staffNames.getOrNull(i) ?: "職員${i + 1}"
    val hard = hardRows.mapNotNull { r -> items.getOrNull(r.tourAt)?.let { ProblemRow(it.family, it.staff, it.days, r.line, true, r.tourAt, r.wishOnly) } }
    val soft = ArrayList<ProblemRow>()
    for ((key, fams) in cellFamilies) {
        val i = VioKey.first(key) ?: continue
        val j = VioKey.second(key) ?: continue
        if (i !in schedule.indices || j !in (schedule[i].indices)) continue
        val sym = symbols.getOrNull(schedule[i][j]) ?: "?"
        for (fam in fams.map(::familyOfVioClass).distinct()) {
            if (fam in MirrorKeys.hard) continue
            soft += ProblemRow(fam, i, listOf(j), problemLine(fam, name(i), DayText.short(startDate, j), sym), false)
        }
    }
    return hard + soft.sortedWith(compareBy({ it.days.first() }, { it.staff }, { it.family }))
}

/**
 * 巡回の位置。[onItem]＝[at] の違反そのものを見ている。false＝見ていた違反が直って消え、[at] は「次に見る」違反（前は at−1）。
 */
internal data class TourPos(val at: Int, val onItem: Boolean)

/**
 * 一覧が作り直されたあとの巡回の位置（直した直後の「次へ」が 1 件飛ばないように）。違反は族・職員・日で同じものとみなす。
 * 見ていた違反が残っていればその位置。消えていれば、元の並びでその後ろにあって残っている最初の違反を「次に見る」にする。
 */
internal fun tourRetarget(old: List<TourItem>, pos: TourPos, new: List<TourItem>): TourPos {
    if (new.isEmpty() || old.isEmpty()) return TourPos(0, false)
    fun key(t: TourItem) = Triple(t.family, t.staff, t.days)
    val index = new.withIndex().associate { (i, t) -> key(t) to i }
    val at = pos.at.coerceIn(0, old.size - 1)
    if (pos.onItem) index[key(old[at])]?.let { return TourPos(it, true) }
    val from = if (pos.onItem) at + 1 else at
    for (d in 0 until old.size) index[key(old[(from + d) % old.size])]?.let { return TourPos(it, false) }
    return TourPos(0, false)
}

/** 巡回の見出し「必須違反 2 / 5 ・ 禁止の並び Dﾃ→A4 ・ 10/8〜10/9」。 */
internal fun tourHeading(items: List<TourItem>, at: Int): String? =
    items.getOrNull(at)?.let { "必須違反 ${at + 1} / ${items.size} ・ ${it.heading}" }

/** 巡回に入らない人員不足の件数の 1 行（0 なら null）。 */
internal fun tourCovULine(covU: Int): String? = if (covU > 0) "ほかに人員不足 ${covU}人（日の印から開けます）" else null

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
data class CellSheetRev(val checkRev: Long, val editRev: Int, val schedule: List<List<Int>>, val wishes: Map<String, Int>)

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
internal const val CANCEL_FIX_SEARCH_BUTTON = "探すのをやめる"
internal const val FIX_SEARCH_CANCELLED = "探すのを止めました。"
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
        if (r.rr > 0) "。他の必須違反 ${r.rr}件は残ります" else ""

/** 通知の「元に戻す」は、その操作が今も元に戻すの先頭にあるときだけ効く（後の別の操作を戻さない）。 */
internal fun noticeUndoApplies(topSerial: Long?, noticeSerial: Long): Boolean = topSerial != null && topSerial == noticeSerial

/** 操作の通知を出している間、検査の進み具合などの通常の文言では置き換えない（失敗・拒否だけは置き換える）。 */
internal fun messageMayReplaceNotice(noticeShowing: Boolean, isError: Boolean): Boolean = !noticeShowing || isError

/** 手動固定を付けたセルが担当外／必須違反を抱えているときの一文（付けない＝空）。固定の挙動は変えない＝知らせるだけ。 */
internal fun pinRegisterHint(canDo: Boolean, families: List<String>): String = when {
    !canDo -> PIN_NOT_CANDO_HINT
    families.any { familyOfVioClass(it) in com.magi.app.v6.ConstraintCatalog.hardCellKeys } -> PIN_HARD_HINT
    else -> ""
}
// 手動固定の通知「…を手動固定しました（自動では変更しません）」の後ろに付く＝同じことを繰り返さない。
internal const val PIN_HARD_HINT = "このセルには必須違反があります。"
internal const val PIN_NOT_CANDO_HINT = "担当外のシフトなので、必須違反が残ります。"

/** セルを 1 つ変えたときの Snackbar（「元に戻す」付き）。 */
internal fun cellChangedMessage(name: String, startDate: String, day: Int, symbol: String): String = "${name}さんの ${DayText.full(startDate, day)} を ${symbol} に変更しました"

/** ちら見に出す上位 [n] シフト: 今の割当・希望を先に、残りは担当できるものを枠の順で。 */
internal fun peekShifts(shown: List<Int>, canDo: Set<Int>, current: Int, wish: Int?, n: Int = 4): List<Int> =
    (listOfNotNull(current.takeIf { it >= 0 }, wish?.takeIf { it >= 0 }) + shown.filter { it in canDo })
        .filter { it in shown }.distinct().take(n)

/** ちら見の操作行に並べるシフトの数: 「詳しく ▸」(64dp) と同じ行に 52dp＋間 6dp で入るだけ（1〜4）。360dp 以上の端末は 4。 */
internal fun peekPickCount(contentWidthDp: Int): Int = ((contentWidthDp - 64 + 6) / 58).coerceIn(1, 4)

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
internal const val SAVE_FAILED_NOTICE = "自動保存できませんでした（空き容量不足の可能性）。「$SAVE_RETRY_BUTTON」を押してください"
internal const val HOME_SAVE_FAILED_LINE = "自動保存できていません。端末の空き容量を確かめてください"
internal const val TOP_BAR_SAVE_FAILED = "保存できていません"
/** 上部の札の幅の境界（3.657.0 の名前列と同じ）。これより狭いと自動作成の状態を札の下の行へ畳む。 */
internal const val TOP_BAR_NARROW_DP = 396

/** 上部の札の左に出す保存の語。失敗のときだけ（押すと保存の失敗のダイアログ）。 */
/** 上部の巡回の入口。省略されても数が残るよう、数を前に置く。人員不足は巡回に入らない（日の印から）が必須の件数には入るので、
 *  人数を並べる（か所と人は足さない）。旧: か所だけで、隣の「必須違反 4」と数が合わなかった（2026-10-25 実機）。 */
internal fun topBarTourLabel(n: Int, covU: Int = 0): String =
    if (covU > 0) "必須違反${n}か所・人員不足${covU}人を見る" else "必須違反${n}か所を見る"

internal fun topBarSaveWord(s: SaveState): String? = if (s == SaveState.Failed) TOP_BAR_SAVE_FAILED else null

internal fun topBarStatusWrapped(widthDp: Int): Boolean = widthDp < TOP_BAR_NARROW_DP

internal fun saveFailDialogTitle(d: SaveFailDialog): String = if (d == SaveFailDialog.AGAIN) "まだ保存できていません" else "自動保存できませんでした"
internal const val SAVE_FAIL_DIALOG_BODY = "端末に書き込めません（空き容量が足りない可能性があります）。"

/** 勤務表 CSV の書き出しに失敗したら、配布前の確認を開き直す。 */
internal fun csvExportReopensPreDist(ok: Boolean): Boolean = !ok

/**
 * 保存の結果からダイアログを決める（C# `SaveFailNotifier` と 1 対 1）。成功から失敗に変わった瞬間に FIRST を 1 回、
 * 失敗中に利用者がやり直して失敗したら AGAIN。失敗が続くだけの自動保存では出さない（1.2 秒ごとに走るため）。
 */
internal class SaveFailNotifier {
    var lastOk = true
        private set
    fun onResult(ok: Boolean, retried: Boolean): SaveFailDialog? {
        val prev = lastOk
        lastOk = ok
        return when { ok -> null; prev -> SaveFailDialog.FIRST; retried -> SaveFailDialog.AGAIN; else -> null }
    }

    /** 保存結果の反映内容を決める唯一の場所（VM の reportAutoSave はそのまま適用するだけ）。 */
    fun report(ok: Boolean, retried: Boolean): SaveOutcome {
        val wasOk = lastOk
        val dialog = onResult(ok, retried)
        val notice = when { ok == wasOk -> null; ok -> SaveNotice.RECOVERED; else -> SaveNotice.FAILED }
        return SaveOutcome(dialog, setDialog = dialog != null || ok, notice = notice, retryLater = !ok)
    }
}

internal enum class SaveNotice { RECOVERED, FAILED }

/** [retryLater]＝失敗したので次のデバウンス保存を仕掛け直す（次の編集まで未保存にしない）。 */
internal data class SaveOutcome(val dialog: SaveFailDialog?, val setDialog: Boolean, val notice: SaveNotice?, val retryLater: Boolean)

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
internal fun multiFixButton(n: Int): String = (if (n == 2) "両日" else "${n}日") + "の不足をまとめて直す案を探す"

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

/** 選んだ問題（族）が 1 案でどう変わるか。案の先頭に出す。 */
internal fun familyEffectLine(s: com.magi.app.v6.FixSuggestion, family: String, label: String): String? =
    s.diff.firstOrNull { it.first == family }?.second?.let { d -> if (d < 0) "$label：${-d}件減る" else if (d > 0) "$label：${d}件増える" else null }

/** 適用ボタンの下に添える注記（ボタンの文言は短く＝折り返さない）。 */
internal const val APPLY_UNDO_NOTE = "適用したあとでも元に戻せます。"

/** 案のカードのボタン（見るだけ。適用は確認の画面で）。 */
internal const val REVIEW_CHANGE_BUTTON = "変更内容を確認する"

/** 確認の画面の適用ボタン（件数を言う）。 */
internal fun applyCountButton(n: Int): String = if (n >= 2) "この${n}か所の変更を適用する" else APPLY_CHANGE_BUTTON

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
            val line = "${DayText.short(startDate, j)}の${shiftSymbols.getOrNull(k) ?: UNKNOWN_LABEL}：${b}人 → ${a}人$note"
            if (skip != null && j == skip.second) same += line else other += line
        }
    }
    return same to other
}

/** 案で回数が変わる（または変わらない）勤務を職員ごとに言う。2 か所以上を変える理由の事実（例「休の回数は変わりません（12回のまま）」）。 */
internal fun staffCountNotes(
    schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>, staffNames: List<String>, shiftSymbols: List<String>,
    limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)? = null, targetStaff: Int? = null,
): List<String> {
    if (ops.size < 2) return emptyList()
    val after = boardAfter(schedule, ops)
    return ops.map { it.staff }.distinct().flatMap { i ->
        val row = schedule.getOrNull(i).orEmpty(); val rowA = after.getOrNull(i).orEmpty()
        val name = staffNames.getOrNull(i) ?: UNKNOWN_LABEL
        ops.filter { it.staff == i }.flatMap { listOfNotNull(row.getOrNull(it.day), it.toShift) }.distinct().sorted().map { k ->
            val b = row.count { it == k }; val a = rowA.count { it == k }
            val sym = shiftSymbols.getOrNull(k) ?: UNKNOWN_LABEL
            if (a == b) "${name}さんの「$sym」の回数は変わりません（${b}回のまま）"
            else "${name}さんの「$sym」：${b}回 → ${a}回" + (if (i != targetStaff) limits?.invoke(i, k)?.let { countCrossNote(b, a, it.first, it.second) }.orEmpty() else "")
        }
    }
}

/** 回数が基準をまたぐときの根拠（「（下限 8回を下回ります）」「（上限 4回を超えます）」）。またがなければ空。 */
internal fun countCrossNote(before: Int, after: Int, lo: Int?, hi: Int?): String = when {
    lo != null && after < before && after < lo -> "（下限 ${lo}回を下回ります）"
    hi != null && after > before && after > hi -> "（上限 ${hi}回を超えます）"
    else -> ""
}

/** 回数の対象の案で、見ている職員以外に新しく生まれる下限割れ・上限超えの族（対象の族でも、別の職員なら言う）。 */
internal fun newCountBreakLines(
    schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>, targetStaff: Int?, limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)?,
    staffNames: List<String>, shiftSymbols: List<String>,
): List<String> {
    val f = limits ?: return emptyList()
    val after = boardAfter(schedule, ops)
    return ops.map { it.staff }.distinct().filter { it != targetStaff }.flatMap { i ->
        val row = schedule.getOrNull(i).orEmpty(); val rowA = after.getOrNull(i).orEmpty()
        ops.filter { it.staff == i }.flatMap { listOfNotNull(row.getOrNull(it.day), it.toShift) }.distinct().sorted().mapNotNull { k ->
            val (lo, hi, _) = f(i, k)
            val b = row.count { it == k }; val a = rowA.count { it == k }
            val fam = when {
                lo != null && a < lo && b >= lo -> "low"
                hi != null && a > hi && b <= hi -> "high"
                else -> return@mapNotNull null
            }
            "新たに生じる：${breakdownLabels[fam] ?: fam}（${staffNames.getOrNull(i) ?: UNKNOWN_LABEL}さんの${shiftSymbols.getOrNull(k) ?: UNKNOWN_LABEL}）"
        }
    }
}

/** 回数の対象の族（下限→low、上限→high、目標→apt）。日の対象は null。 */
internal fun countTargetFamily(t: FixTarget): String? = if (t.staff == null) null else when (t.basis) { "下限" -> "low"; "上限" -> "high"; else -> "apt" }

/** 回数の対象で、その族の勤務表全体の量が減らない案（見ている職員の不足が別の職員へ移るだけ）。 */
internal fun countMovesOnly(s: com.magi.app.v6.FixSuggestion, t: FixTarget?): Boolean {
    val fam = t?.let { countTargetFamily(it) } ?: return false
    return (s.diff.firstOrNull { it.first == fam }?.second ?: 0) >= 0
}

internal const val MOVES_TO_OTHER_STAFF_NOTE = "＝別の職員へ移るだけです"

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

/** 「変更を反映しました」の行: 見ている問題（前 → 今）、期間全体の過不足（日の対象）、勤務表全体の必須、新たに生じた問題、ほかに人数が変わった枠、
 *  保存（保存中・失敗のときだけ）、残る問題、探し直したこと。 */
internal fun appliedLines(ui: UiState, a: AppliedFix, target: FixTarget?, label: String, needLimits: ((Int, Int) -> Pair<Int, Int>?)?): List<String> {
    val out = ArrayList<String>()
    target?.let { t ->
        val e = t.effect(a.before, a.ops)
        out += "今回の問題：${appliedStatusWord(e)}（$label：${e.countLine()}、${if (e.gapAfter == 0) e.afterNote(t.basis) else "${t.gapWord} ${e.gapAfter}${t.unit}"}）"
        if (t.day != null && !t.multi) {
            val g0 = shiftPeriodGap(a.before, t.shift, t.under, needLimits)
            val g1 = shiftPeriodGap(a.after, t.shift, t.under, needLimits)
            if (g0 != null && g1 != null) out += periodGapLine(ui.shiftSymbols.getOrNull(t.shift) ?: "${t.shift}", t.under, g0, g1)
        }
    }
    // 内訳があれば単位ごとに（件・人・回・pt を 1 つの数に足さない＝統合仕様 INV-07）。
    fun t(b: Map<String, Int>?, fams: List<String>, n: Int) = b?.let { unitTotals(it, fams) ?: "なし" } ?: "${n}件"
    if (a.hardBefore >= 0) out += "必須 ${t(a.breakdownBefore, MirrorKeys.hard, a.hardBefore)}→${t(a.breakdownAfter, MirrorKeys.hard, a.hardAfter)}"
    appearedFamiliesLine(a.breakdownBefore, a.breakdownAfter)?.let { out += it }
    val (same, other) = headcountImpact(a.before, a.ops, ui.shiftSymbols, ui.startDate, needLimits, target?.day?.let { target.shift to it })
    out += same + other
    if (ui.saveState == SaveState.Failed || ui.saveState == SaveState.Saving) out += saveStateLine(ui.saveState)
    // [3.712.0] 見ている問題の残りは 1 行目が言う＝「次」はほかの日の同じ勤務だけ（同じ文を 2 回出さない）。
    val next = if (target?.day != null) remainingSameShift(ui.needFamilies, ui.schedule, target, needLimits, ui.shiftSymbols, ui.startDate) else emptyList()
    if (next.isNotEmpty()) out += "次：" + next.joinToString("。")
    out += "勤務表が変わったため、変更案を探し直しました（下の案は今の勤務表のものです）。"
    return out
}

/** 当てたあとも残る、見ている問題と同じ勤務の過不足（日の対象＝ほかの日、回数＝基準までの差）。 */
internal fun appliedRemaining(ui: UiState, a: AppliedFix, target: FixTarget?, label: String, needLimits: ((Int, Int) -> Pair<Int, Int>?)?): List<String> = when {
    target == null -> emptyList()
    target.day != null -> (target.effect(a.before, a.ops).takeIf { it.gapAfter > 0 }?.let { listOf("${label}は、まだ${target.gapWord} ${it.gapAfter}${target.unit}です") }.orEmpty()) +
        remainingSameShift(ui.needFamilies, ui.schedule, target, needLimits, ui.shiftSymbols, ui.startDate)
    else -> target.effect(a.before, a.ops).takeIf { it.gapAfter > 0 }?.let { listOf(remainingGapLine(label, target, it.gapAfter)) }.orEmpty()
}

/** 当てたあとに増えた族（「新たに生じた問題：禁止の並び +1件」）。内訳が無いか増えた族が無ければ null。 */
internal fun appearedFamiliesLine(before: Map<String, Int>?, after: Map<String, Int>?): String? {
    if (before == null || after == null) return null
    val up = (before.keys + after.keys).filter { (after[it] ?: 0) > (before[it] ?: 0) }.sortedByDescending { MirrorKeys.weightOf(it) }
    if (up.isEmpty()) return null
    return "新たに生じた問題：" + up.joinToString("・") { "${breakdownLabels[it] ?: it} +${(after[it] ?: 0) - (before[it] ?: 0)}${unitOf(it)}" }
}

/** 反映の帯の次の一歩のボタン。同じ問題が残れば続けてその勤務の案を、無ければ残りの必須違反の巡回（「（Nか所）」＝巡回できる項目の数 [tourCount]。上部の札と同じ値で、件と人が混ざった report.hard ではない＝INV-07、3.693.0）、どちらも無ければ null。 */
internal fun appliedNextButton(sameRemains: Boolean, sym: String?, hardLeft: Int, tourCount: Int): String? = when {
    sameRemains && sym != null -> "続けて $sym の案を見る"
    hardLeft > 0 && tourCount > 0 -> guidedTourButton(tourCount)
    else -> null
}

/** 当てたあとに残る差（回数＝「A4は、下限まであと2回です」、まとめて見る日々＝「Dテ（2日）は、合計 3人不足です」）。 */
internal fun remainingGapLine(label: String, t: FixTarget, gap: Int): String =
    if (t.multi) "${label}は、合計 $gap${t.unit}${t.gapWord}です" else "${label}は、${t.basis}まであと$gap${t.unit}です"

/**
 * セルを 1 つ変える前の確認（2026-10-14 利用者レビュー「問題を確認 → 変更方法を選ぶ → 影響を確認して適用」）。
 * 前後の報告を正式な検査（[UnifiedViolationChecker]）で比べ、違反の同一性（[com.magi.app.v6.ViolationIdentity]）で
 * このセルの問題が解消するか、ほかで解消する問題、新しく生じる・悪くなる問題を言う。値はすべて評価した結果（仮の値を書かない）。
 * [verdict] は変更の行の直後の一文（必須違反が増える＝[verdictBad]、またはこのセルの問題が解消する）。言えることが無ければ null。
 */
internal data class CellChangeReview(
    val change: String, val neighbors: String, val thisCell: List<String>, val othersResolved: List<String>,
    val appeared: List<String>, val hardLine: String, val headcount: List<String>, val counts: List<String>, val wishPin: List<String>,
    val verdict: String? = null, val verdictBad: Boolean = false,
)

internal const val KEEP_UNCHANGED_BUTTON = "今は変更しない"

internal const val REMOVE_WISH_BUTTON = "希望を外す（元に戻せます）"

internal const val REVIEW_APPEARED_HEADING = "新しく生じる・悪くなる問題"

/** 違反 1 件の量（人員＝基準までの人数、回数＝基準までの回数）。言葉・単位・量。量を言えない族は null。 */
internal fun violationKeyGap(
    key: com.magi.app.v6.ViolationKey, board: Array<IntArray>,
    limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)?, needLimits: ((Int, Int) -> Pair<Int, Int>?)?,
): Triple<String, String, Int>? = when (key.space) {
    com.magi.app.v6.ViolationKey.Space.NEED -> needLimits?.invoke(key.a, key.b)?.let { (lo, hi) ->
        val n = board.count { it.getOrNull(key.b) == key.a }
        when (key.family) { "covU" -> Triple("不足", "人", maxOf(0, lo - n)); "covO" -> Triple("過剰", "人", maxOf(0, n - hi)); else -> null }
    }
    com.magi.app.v6.ViolationKey.Space.COUNT -> limits?.invoke(key.a, key.b)?.let { (lo, hi, apt) ->
        val n = board.getOrNull(key.a)?.count { it == key.b } ?: return@let null
        when (key.family) {
            "low" -> lo?.let { Triple("不足", "回", maxOf(0, it - n)) }
            "high" -> hi?.let { Triple("超過", "回", maxOf(0, n - it)) }
            "apt" -> apt?.let { Triple("目標との差", "回", kotlin.math.abs(n - it)) }
            else -> null
        }
    }
    else -> null
}

internal fun cellChangeReview(
    state: MagiState, s: Array<IntArray>, i: Int, j: Int, k: Int, staffNames: List<String>, shiftSymbols: List<String>,
    limits: ((Int, Int) -> Triple<Int?, Int?, Int?>)?, needLimits: ((Int, Int) -> Pair<Int, Int>?)?, pinned: Boolean,
    targets: List<FixTarget> = emptyList(),
): CellChangeReview {
    fun sym(x: Int?) = x?.let { shiftSymbols.getOrNull(it) } ?: "—"
    val name = staffNames.getOrNull(i) ?: UNKNOWN_LABEL
    val cur = s[i][j]
    val trial = Array(s.size) { s[it].copyOf() }.also { it[i][j] = k }
    val before = UnifiedViolationChecker.check(state, s)
    val after = UnifiedViolationChecker.check(state, trial)
    val diff = com.magi.app.v6.ViolationIdentity.diff(before, after)
    fun label(key: com.magi.app.v6.ViolationKey) = violationKeyLabel(key, staffNames, shiftSymbols, state.startDate)
    // 前後とも残る違反のうち、量が増えるもの（「必須・人員不足（10/8のA4）：不足 1人 → 2人」）。
    val worsened = (com.magi.app.v6.ViolationIdentity.keys(after) intersect com.magi.app.v6.ViolationIdentity.keys(before)).mapNotNull { key ->
        val g0 = violationKeyGap(key, s, limits, needLimits) ?: return@mapNotNull null
        val g1 = violationKeyGap(key, trial, limits, needLimits) ?: return@mapNotNull null
        if (g1.third > g0.third) "${label(key)}：${g0.first} ${g0.third}${g0.second} → ${g1.third}${g1.second}" else null
    }
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
    )
    val hereResolved = here.filter { it in diff.resolved }.map { breakdownLabels[it.family] ?: it.family }.distinct()
    val (verdict, bad) = when {
        after.hard > before.hard -> "この変更で必須違反が増えます（${hardText(before.breakdown, before.hard.toLong())} → ${hardText(after.breakdown, after.hard.toLong())}）" to true
        hereResolved.isNotEmpty() -> "このセルの${hereResolved.joinToString("・")}は解消します" to false
        else -> null to false
    }
    return CellChangeReview(
        change = "${name}さん　${DayText.full(state.startDate, j)} ${sym(cur)} → ${sym(k)}",
        neighbors = nb,
        thisCell = thisCell.ifEmpty { listOf(NO_CELL_VIOLATION) },
        othersResolved = diff.resolved.filter { it !in here }.map(::label),
        appeared = diff.appeared.map(::label) + worsened,
        hardLine = "必須違反（勤務表全体）：${hardText(before.breakdown, before.hard.toLong())} → ${hardText(after.breakdown, after.hard.toLong())}",
        headcount = other,
        counts = counts,
        wishPin = wishPin,
        verdict = verdict, verdictBad = bad,
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
internal fun violationKeyLabel(key: com.magi.app.v6.ViolationKey, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): String =
    (if (key.hard) "必須・" else "") + (breakdownLabels[key.family] ?: key.family) + "（${violationKeyPlace(key, staffNames, shiftSymbols, startDate)}）"

/** 違反 1 件の場所（「福澤さん 11/29」「11/3のAア」「福澤さんのB4」）。 */
internal fun violationKeyPlace(key: com.magi.app.v6.ViolationKey, staffNames: List<String>, shiftSymbols: List<String>, startDate: String): String {
    fun sym(x: Int) = shiftSymbols.getOrNull(x) ?: "$x"
    return when (key.space) {
        com.magi.app.v6.ViolationKey.Space.CELL -> "${staffNames.getOrNull(key.a) ?: "#${key.a}"}さん ${DayText.short(startDate, key.b)}"
        com.magi.app.v6.ViolationKey.Space.NEED -> "${DayText.short(startDate, key.b)}の${sym(key.a)}"
        com.magi.app.v6.ViolationKey.Space.COUNT -> "${staffNames.getOrNull(key.a) ?: "#${key.a}"}さんの${sym(key.b)}"
    }
}

/** 案で解消する必須違反を「族：場所」で（正式な検査の前後の違反の同一性で決める）。 */
internal fun resolvedHardLabels(state: MagiState, s: Array<IntArray>, ops: List<com.magi.app.v6.FixCell>, staffNames: List<String>, shiftSymbols: List<String>): List<String> {
    val trial = Array(s.size) { s[it].copyOf() }
    for (op in ops) if (op.staff in trial.indices && op.day in trial[op.staff].indices) trial[op.staff][op.day] = op.toShift
    return com.magi.app.v6.ViolationIdentity.diff(UnifiedViolationChecker.check(state, s), UnifiedViolationChecker.check(state, trial))
        .resolved.filter { it.hard }.map { "${breakdownLabels[it.family] ?: it.family}：${violationKeyPlace(it, staffNames, shiftSymbols, state.startDate)}" }
}

/** 手 [ops] で解消する必須違反の鍵（[before]＝今の盤面の正式な報告。案ごとに検査し直さない）。 */
internal fun resolvedHardKeys(state: MagiState, s: Array<IntArray>, before: com.magi.app.v6.ViolationReport, ops: List<com.magi.app.v6.FixCell>): Set<com.magi.app.v6.ViolationKey> {
    val trial = Array(s.size) { s[it].copyOf() }
    for (op in ops) if (op.staff in trial.indices && op.day in trial[op.staff].indices) trial[op.staff][op.day] = op.toShift
    return com.magi.app.v6.ViolationIdentity.diff(before, UnifiedViolationChecker.check(state, trial)).resolved.filterTo(LinkedHashSet()) { it.hard }
}

/** ホームの「直す手」の見出し。解消する必須違反の名と場所が分かれば 2 件まで名指しする（3 件目からは件数）。 */
internal fun homeFixHeadline(hard: String, resolved: List<String>): String {
    if (resolved.isEmpty()) return "必須違反 $hard を減らす手があります"
    val names = resolved.take(2).joinToString("、") + if (resolved.size > 2) " ほか" else ""
    return "必須違反 $hard のうち ${resolved.size}件（$names）を減らす手があります"
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
/** [3.714.0] セルのシートのルールの 1 行（22 字まで）。説明の文 [cellProblemSentence] は「詳しく見る」の中。 */
internal fun cellRuleLine(fam: String): String = when (fam) {
    "c3n" -> "禁止の並びに当たります"
    "c3mn" -> "回避の並びに当たります"
    "c3w" -> "希望の前日ルールに当たります"
    "pref" -> "本人の希望と違う勤務です"
    "extWish" -> "拡張希望で除いた勤務です"
    "groupViol" -> "グループが担当しない勤務です"
    "covU" -> "この日の人数が足りません"
    "covO" -> "この日の人数が多すぎます"
    else -> "${breakdownLabels[fam] ?: fam}に当たります"
}
internal const val CELL_DETAIL_BUTTON = "詳しく"

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
        "${ui.shiftSymbols.getOrNull(k) ?: UNKNOWN_LABEL}：現在${n}回$basis"
    }
}

/** 手動固定の説明（通常の修正操作と分ける）。 */
internal const val PIN_TITLE = "この勤務を自動変更しない"
internal const val PIN_NOTE = "自動作成・調整でこの勤務を変えなくなります。違反は消えません。"

/** 担当外の勤務の理由（自分で勤務を選ぶ画面）。 */
internal const val NOT_CANDO_NOTE = "「外」はこの職員のグループが担当しない勤務です。選ぶと必須違反になります。"

/** 上部の札の 2 行目以降: 自動作成の状態と、今の勤務表の評価（2026-10-14 利用者レビュー「未計算」を分ける）。 */
internal fun boardStatusLines(engineRan: Boolean, hasResult: Boolean, checked: Boolean, hard: Long, soft: Long, breakdown: Map<String, Int> = emptyMap(), ranBeforeEdit: Boolean = false): Pair<String, String> =
    (if (engineRan) "自動作成済み" else if (ranBeforeEdit && hasResult) "手で変更あり" else if (hasResult) "手で編集" else "自動作成前") to
        (if (!checked) "未評価" else if (hard > 0) "必須違反 ${hardText(breakdown, hard)}"
        else if (soft > 0) "要調整 ${unitTotals(breakdown, MirrorKeys.soft) ?: "${soft}件"}" else "違反なし")

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

/** 組む時間の表記（3.706.0 S2: 作成前の要点と設定タブで共有）。60 の倍数は「N分」、それ以外は「N秒」。 */
internal fun budgetText(sec: Int): String = if (sec % 60 == 0) "${sec / 60}分" else "${sec}秒"

/** 設定タブの組む時間の状態行（3.706.0 S2）。 */
internal fun budgetStatusLine(sec: Int, reserveSec: Int): String = "組む時間 ${budgetText(sec)}（＋仕上げと玉突き ${reserveSec}秒）"

/** 「組む時間を選ぶ」の候補（3.706.0 S1）。10秒・1〜5分に、範囲内へ丸めた今の値を必ず含めて昇順。既定・範囲は変えない（HF77）。 */
internal fun budgetPresets(currentSec: Int): List<Int> =
    (listOf(10, 60, 120, 180, 240, com.magi.app.v6.MAX_OPTIMIZE_SEC) + currentSec.coerceIn(10, com.magi.app.v6.MAX_OPTIMIZE_SEC)).distinct().sorted()

/** 勤務表を作成する画面の要点（作成前）を 1 行に。時間は設定値（探索）と固定の仕上げ枠。 */
internal fun preRunPlanLine(startDate: String, days: Int, staff: Int, budgetSec: Int, reserveSec: Int): String {
    val period = if (days <= 0 || startDate.isBlank()) "" else DayText.range(startDate, 0, days - 1) + "・"
    return "$period${staff}人・組む時間 ${budgetText(budgetSec)}（＋仕上げ ${reserveSec}秒）。開始後は一手ごとの確認はしません"
}

/** 登録条件の 1 行（3.704.0 R-4: 節ごとの件数を並べる）。[overCaps]＝希望が個人の上限を超える組（作成しても残る）。 */
internal fun preRunConditionLine(floor: Int, rerun: Int, overCaps: Int, pins: Int): String {
    val parts = listOfNotNull(
        "何度つくっても残る ${floor}件".takeIf { floor > 0 },
        "再作成で外れる ${rerun}件".takeIf { rerun > 0 },
        "希望が上限を超える ${overCaps}件".takeIf { overCaps > 0 },
    )
    return "登録条件：" + (if (parts.isEmpty()) "つくる前に直す項目はありません" else parts.joinToString("・")) +
        (if (pins > 0) "／手動固定 ${pins}件は保持" else "")
}

/** 単純な確認の点（3.704.0 G-4）: 空行を除いて [CONFIRM_MAX_POINTS] 個まで。超えたら確認ではなく読む画面にする。 */
internal fun confirmDialogPoints(points: List<String>): List<String> {
    val p = points.filter { it.isNotBlank() }
    require(p.size <= CONFIRM_MAX_POINTS) { "確認の点は ${CONFIRM_MAX_POINTS} つまで（${p.size}）" }
    return p
}

internal const val CONFIRM_MAX_POINTS = 3

/** 作成前のシートの主ボタン＝開いたボタンと同じ語（3.704.0、利用者決定 Q3）。 */
internal fun preRunProceedLabel(hasResult: Boolean): String = if (hasResult) "再作成" else "勤務表をつくる"

/** 同じ探索の設定で再作成するときの一言。[top]＝前回いちばん多い必須違反（「族名（N単位）」）。 */
internal fun preRunRepeatHintText(top: String?): String =
    "前回と同じ組む時間・方式で再作成します。" + (top?.let { "前回いちばん多い必須違反は${it}です。" } ?: "") +
        "同じ設定では同じ違反が残りやすいため、編集タブでこれを 1 つ緩めると減る見込みがあります。"

/**
 * 「勤務表を作成する」を一度見たら出さない基準＝設定と希望と、再作成で外れる手置きのセル（[clears]）。
 * 盤面そのものは含めない（再作成のたびに変わる）。上限 0 のセルを手で置き直したときだけシートをもう一度出す。
 */
internal fun preRunAckFingerprint(st: com.magi.app.model.MagiState, clears: List<com.magi.app.v6.PreRunCheck.HandPlacedCell>): Long =
    clears.fold(com.magi.app.v6.StateFingerprint.of(st)) { h, c -> ((h * 31L + c.staff) * 31L + c.day) * 31L + c.shift }

internal const val PRE_RUN_TITLE = "勤務表を作成する"
internal const val PRE_RUN_CHECK_CONDITIONS = "条件を確認する"

/** 要調整の内訳（族ごとに件数か pt。多い順に [max] 種まで）。 */
internal fun softSummary(breakdown: Map<String, Int>, max: Int = 4): String {
    val soft = com.magi.app.v6.MirrorKeys.soft.mapNotNull { k -> breakdown[k]?.takeIf { it > 0 }?.let { k to it } }
        .sortedByDescending { (k, n) -> (com.magi.app.v6.MirrorKeys.weights[k] ?: 1.0) * n }
    if (soft.isEmpty()) return "なし"
    return soft.take(max).joinToString("・") { (k, n) -> "${breakdownLabels[k] ?: k} $n${unitOf(k)}" } + if (soft.size > max) " ほか${soft.size - max}種" else ""
}

internal const val CREATION_DONE_TITLE = "作成が終わりました"

/** 適用後の「今回の問題」の状態（解消／減少／残存）。 */
/** 見ている問題の数が厳密に減るか（統合仕様 INV-03＝適用は対象の改善と正式な門の両方）。対象を持たない案は判定しない。 */
internal fun improvesTarget(t: FixTarget?, schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>): Boolean =
    t == null || t.effect(schedule, ops).fixes

/** 勤務変更案の一覧に出してよいか（INV-01/INV-03）: 適用の門と同じ [improvesTarget] を通り、日から開いた一覧（[day]）ならその日のセルに触れる案だけ。 */
internal fun listableFix(t: FixTarget?, schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>, day: Int?): Boolean =
    (day == null || ops.any { it.day == day }) && improvesTarget(t, schedule, ops)

/** 一覧を日で絞る依頼の日（日・列から開いた依頼。職員を見る依頼とまとめて見る依頼は絞らない）。 */
internal fun fixListDay(f: FixFocus): Int? = f.day.takeIf { f.staff == null && !f.allDays }

/** 過剰の対象で、その勤務の 1 人を別の勤務へ移す案の題。それ以外は null（[CountEffect.title] を使う）。 */
internal fun surplusMoveTitle(t: FixTarget?, schedule: List<List<Int>>, ops: List<com.magi.app.v6.FixCell>, staffNames: List<String>, shiftSymbols: List<String>): String? {
    if (t == null || t.under || t.staff != null) return null
    val op = ops.singleOrNull() ?: return null
    val from = schedule.getOrNull(op.staff)?.getOrNull(op.day) ?: return null
    if (from != t.shift || op.toShift == from) return null
    val name = staffNames.getOrNull(op.staff) ?: return null
    return "${name}さんを ${shiftSymbols.getOrNull(from) ?: UNKNOWN_LABEL} から ${shiftSymbols.getOrNull(op.toShift) ?: UNKNOWN_LABEL} へ移して過剰を減らす"
}

internal const val PEEK_SEARCH_BUTTON = "変更案を探す"

/** ちら見の主ボタン（S-3）: 問題のあるセルの割当では常に「変更案を探す」。緩める候補は外枠に下げる（主は 1 つ＝INV-09）。null＝出さない。 */
internal fun peekSearchAction(mode: Int, severity: CellSeverity): String? =
    if (mode == 0 && severity != CellSeverity.NONE) PEEK_SEARCH_BUTTON else null

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
        out.add(FixCandidate(i, st.staff.getOrNull(i)?.name ?: UNKNOWN_LABEL, st.groups.getOrNull(g)?.kigou ?: "", sched[i][dayIndex] == rest))
    }
    // 休みの人（動かしやすい）を先頭に。
    out.sortBy { if (it.fromRest) 0 else 1 }
    return out
}

/** 「{日} の希望を見る」（日が無ければ「希望を見る」）。希望の確認への行き先の名を 1 つにする。 */
internal fun wishDayButton(dayShort: String?): String = if (dayShort.isNullOrBlank()) "希望を見る" else "$dayShort の希望を見る"

internal const val COUNT_SECTION_TITLE = "対象期間の勤務回数"
internal const val MORE_OPS_LABEL = "ほかの操作"

/** セルのシートの「対象期間の勤務回数」は、その職員に回数の族（下限・上限・適切回数など）があるときだけ出す。 */
internal fun showCountSection(countKeys: Collection<String>, i: Int, lines: List<String>): Boolean =
    lines.isNotEmpty() && countKeys.any { VioKey.first(it) == i }

internal const val COMPACT_NO_FIX = "この場所を直す案はありません"

/** セルのシートで案が無いときの 1 行。[single]＝問題が 1 つならその名（前後の日へ案内する）、無ければ先頭の理由を添える。 */
internal fun compactNoFixLine(reasons: List<String>, single: String?, prevDay: String?, nextDay: String?): String {
    if (single != null) {
        val days = listOfNotNull(prevDay, nextDay)
        return "このセルの${single}を直す案はありません" + (if (days.isEmpty()) "" else "。前後の日（${days.joinToString("・")}）も確かめてください")
    }
    return reasons.firstOrNull()?.let { "$COMPACT_NO_FIX。$it" } ?: COMPACT_NO_FIX
}

internal const val VIEW_FIXES_BUTTON = "変更案を見る"

internal const val STAFF_OTHER_FIXES_LABEL = "この職員のほかの変更案"

/** 職員の勤務状況で最初から選んでおく行（選べる行＝勤務のある行が 1 つだけのとき）。 */
internal fun staffSheetPreselect(shiftIdxs: List<Int>): Int? = shiftIdxs.filter { it >= 0 }.distinct().singleOrNull()

internal const val PICK_MARKS_LEGEND = "●＝必須違反が減る　⚠＝必須違反が増える"
internal const val TALLY_LEGEND_TEXT = "▼不足・▲超過（職員・日の欄の数字＝今の回数・人数、計（期間）の ▼2＝不足の日が 2日）"

/** 自動作成の直後に主カードの上端へ出す 2 行の材料。[next]＝次に確認する問題（主カードと同じ対象）。 */
internal data class CreationTop(val next: String?, val onOthers: () -> Unit, val onRetrySave: () -> Unit, val kept: Boolean = false)

/** 次に確認する問題の名（職員の問題は「{名}さん {見出し}」）。 */
internal fun creationNextProblem(staffName: String?, heading: String): String =
    if (staffName.isNullOrBlank() || heading.startsWith(staffName)) heading else "${staffName}さん $heading"

/** 主カード上端の 2 行: 作成の完了と、次に確認する問題（見出しと同じ対象なら完了の 1 行だけ）。 */
internal fun creationTopLines(next: String?, kept: Boolean = false, headlineTarget: String? = null): List<String> =
    listOfNotNull(if (kept) KeptResultText.KEPT_TITLE else CREATION_DONE_TITLE, next?.takeIf { it != headlineTarget }?.let { "次に確認する問題：$it" })

internal const val SOFT_POLISH_LINK = "要調整を減らせるか試す"
internal const val SOFT_POLISH_NOTE = "減らない場合もあります。"

internal const val TRIAGE_HARD_HEADING = "必須違反（配る前に直す必要があります）"
internal const val TRIAGE_SETTINGS_HEADING = "設定を変えないと消えない項目"

/** 分析の畳んだ一覧の見出し（内部の語を使わない）。 */
internal fun conditionStatusTitle(ok: Int, busy: Int): String = "条件ごとの状況（守れている $ok／残っている $busy）"

internal fun bulkClearWishesLabel(n: Int): String = "担当外の希望 ${n}件を外す"

internal const val ALTERNATIVES_ALL_WORSE = "今の勤務表より良くありません"

/**
 * 他の案の 1 行（必須違反と、計算した時点の勤務表との比べ）。比べは採用の基準（betterReport）の結果で語る＝
 * [better]＝案が今より良い、[worse]＝今のほうが良い、どちらでもなければ今と同じ。
 */
internal fun alternativeSummary(n: Int, hardTxt: String, curHard: Int?, altHard: Int, worse: Boolean, better: Boolean): String {
    val cmp = when {
        curHard == null -> ""
        altHard < curHard -> "（今より少ない）"
        altHard > curHard -> "（今より多い）"
        better -> "（今と同じ・全体は今より良い（重みつき））"
        worse -> "（今と同じ・全体は今より悪い（重みつき））"
        else -> "（今と同じ）"
    }
    return "案$n：必須違反 $hardTxt$cmp"
}

/** すべての案が今より悪い（印の数が案の数と合うときだけ）。 */
internal fun alternativesAllWorse(worse: List<Boolean>, n: Int): Boolean = n > 0 && worse.size == n && worse.all { it }

internal const val SHORTAGE_LABEL_INFEASIBLE = "担当できる人が足りない"
internal const val SHORTAGE_LABEL_BLOCKED = "今の希望では埋まらない"
internal const val SHORTAGE_LABEL_FIXABLE = "動かせば埋まる"
internal const val SHORTAGE_LABEL_SEARCH_MISS = "試した入れ替えでは埋まらなかった"
internal const val SHORTAGE_ENOUGH_TEXT = "担当できる人の数は足りています。「直し方を見る」で 1 人ずつ入れられます"

/** [3.712.0] 診断カードの行は 1 行の事実と札だけ。理由・内訳はカードの末尾の「詳しく」に 1 回だけ（利用者仕様: 説明は画面に 1 回）。 */
internal const val DIAG_DETAILS_LABEL = "詳しく"
internal const val SHORTAGE_TAG_INFEASIBLE = "担当が足りない"
internal const val SHORTAGE_MOVABLE_LINE = "休みや余裕のある勤務から移せます"
internal const val FORBIDDEN_RETRY_NOTE = "玉突きか隣の日の調整で崩せます。もう一度つくると直る場合があります"

/** 人員不足の行の札（4 区分を 3 語へ。試した入れ替えで埋まらなかった区別は「詳しく」の理由が言う）。 */
internal fun shortageRowTag(infeasible: Boolean, blockedNow: Boolean): String = when {
    infeasible -> SHORTAGE_TAG_INFEASIBLE
    blockedNow -> SHORTAGE_LABEL_BLOCKED
    else -> SHORTAGE_LABEL_FIXABLE
}

/** 人員不足の行: 「10/10(土) Cｵ 不足1」。 */
internal fun shortageRowLine(dayLabel: String, shiftSymbol: String, miss: Int): String = "$dayLabel $shiftSymbol 不足$miss"

/** 人員不足の行の下の 1 行（動かせば埋まる枠だけ）。 */
internal fun shortageRowNote(infeasible: Boolean, blockedNow: Boolean): String? = SHORTAGE_MOVABLE_LINE.takeIf { !infeasible && !blockedNow }

/** 人員不足カードの見出しの下の 1 行: 「足りない日3 · 動かせる枠あり」。 */
internal fun shortageCardLine(days: Int, movable: Boolean): String = "足りない日$days · " + if (movable) "動かせる枠あり" else "動かせる枠なし"

/** 人員過剰の行の理由（「。内訳: …」から先は「詳しく」へ）。 */
internal fun surplusShortReason(reason: String): String = reason.substringBefore("。内訳: ")

/** 禁止の並びの行の札。 */
internal fun forbiddenRunTag(escapable: Boolean): String = if (escapable) "崩せる" else "崩せない"

/** 禁止の並びの行の下の 1 行: 「10/9 Dﾃ · 10/10 Aｱ」（[cells]＝日の表示と記号の組）。 */
internal fun forbiddenRunCellsLine(cells: List<Pair<String, String>>): String = cells.joinToString(" · ") { "${it.first} ${it.second}" }

/** [3.714.0] 設定タブの見出しと組む時間の 1 行（仕上げの秒数と注記は「詳しく」の中）。 */
internal const val SETTINGS_HEADING = "設定"
internal const val BUDGET_CHANGE_BUTTON = "組む時間を変える"
internal fun budgetShortLine(sec: Int): String = "組む時間 ${budgetText(sec)}"
/** [3.714.0] 編集タブの見出しと 1 行。 */
internal const val EDIT_TAB_HEADING = "条件"
internal const val EDIT_TAB_LINE = "希望・人数・基本"

/** 名前・記号が引けないときの共通の表示（番号を見せない）。 */
internal const val UNKNOWN_LABEL = "—"

/** 進捗の基準（initHard/initSoft）を取り直すかの指紋＝データの指紋＋盤面差し替えの回数。 */
internal fun initBaseKeyOf(stateKey: Long, boardReplaceRev: Long): Long = stateKey * 1_000_003L + boardReplaceRev

/** 実行中の進捗サマリ文字列: 残りの違反（開始の値つき）・ 残り時間。読取専用。 */
internal fun progressSummary(ui: UiState): String {
    val parts = ArrayList<String>(4)
    parts += when {
        // [3.393.0] 必須違反が残っている間は「改善◯%」の枝に入らないため、旧実装は進み具合を
        //   まったく示していなかった（⚠3 とだけ出る）。開始時の必須件数(initHard)を併記して
        //   「69件から3件まで来ている」ことを見せる。initHard は満足度の計算で既に使っており、
        //   ここが最後の未配線だった。
        ui.bestHard > 0L -> {
            // 開始は基準の盤面の内訳から同じ単位で言う（内訳が無い・減っていなければ出さない＝件と人を混ぜない）。
            val start = ui.initBreakdown.takeIf { it.isNotEmpty() && ui.initHard > ui.bestHard }?.let { "（開始${hardText(it, ui.initHard)}）" }.orEmpty()
            "必須違反 残り${hardText(ui.breakdown, ui.bestHard)}$start"
        }
        // 要調整も族の単位ごとの合計で言う（生の件数は単位が混ざる）。
        ui.initBreakdown.isNotEmpty() || ui.bestSoft > 0L -> {
            val now = unitTotals(ui.breakdown, com.magi.app.v6.MirrorKeys.soft) ?: "なし"
            val start = ui.initBreakdown.takeIf { it.isNotEmpty() }?.let { unitTotals(it, com.magi.app.v6.MirrorKeys.soft) }?.takeIf { it != now }
            "要調整 $now" + (start?.let { "（開始$it）" } ?: "")
        }
        else -> "要調整 –"
    }
    val secLeft = (((ui.budgetSec + com.magi.app.v6.POST_RESERVE_SEC + com.magi.app.v6.CHAIN_RESERVE_SEC) * 1000L - ui.elapsedMs).coerceAtLeast(0L) / 1000L)
    parts += "残り %d:%02d".format(secLeft / 60, secLeft % 60)
    // [3.396.0] 反復数（「1.2M回」「毎秒40K」）は**作り手の指標**なので操作画面から外した。
    //   初見の人は必ず「これ何の回数？」と聞く＝その時点でこの表示は失敗している。知りたいのは
    //   「あとどれくらい」と「良くなっているか」の2つで、それは上の2項目が既に答えている。
    //   反復数を見たいとき（ネイティブ加速が効いているか等）は診断ログの `TIME` 行と
    //   `AdaptivePortfolio 合計iter`（3.360.0）に出ている。3.393.0 で「死んだ配管を配線する」として
    //   毎秒表示を足したのは、この観点では逆向きだったので戻す。
    return parts.joinToString("  ・  ")
}

internal const val FIX_ORIGIN_UNKNOWN_MESSAGE = "案を作ったときの勤務表が分からないため適用しませんでした。もう一度探してください"

internal const val FIX_TARGET_UNKNOWN_MESSAGE = "見ている問題が分からないため適用しませんでした。もう一度探してください"

/** 対象（[FixTarget]）も全体の研磨の明示も無い案は断る。null＝断らない。 */
internal fun fixTargetRefusal(fixTarget: FixTarget?, globalPolish: Boolean): String? =
    if (fixTarget == null && !globalPolish) FIX_TARGET_UNKNOWN_MESSAGE else null

/** 確認の画面から当てる案は、案を作ったときの盤面の指紋が要る（0＝不明なら断る。今の全体の指紋で代わりに通さない）。null＝断らない。 */
internal fun fixOriginRefusal(originBoard: Long, fromPreview: Boolean): String? =
    if (fromPreview && originBoard == 0L) FIX_ORIGIN_UNKNOWN_MESSAGE else null

/** ホームの主カードの段（主ボタンの行き先）。文言と色は `OperatorNextActionCard` が段ごとに決める。 */
internal enum class HomePlanKind { RUNNING, MAKE, DRAFT, PRE_DIST, WISH_INFEASIBLE, DATA_INFEASIBLE, GUIDED_FIX, APPLY_MOVE, SEARCHING, RELAX, WISH_FLOOR, WISHES, LIST }

/** 主ボタン（1 つだけ）と補助ボタンの行き先。 */
internal enum class HomeAction { NONE, MAKE, SMART_INITIAL, PRE_DIST, SCHEDULE, SHOW_WISHES, LANDING, FIX, SETUP, SHOW_MOVE, APPLY_MOVE, SHOW_RELAX, SHOW_LIST }

internal data class HomePlan(val kind: HomePlanKind, val primary: HomeAction, val primaryEnabled: Boolean, val helper: HomeAction?)

/**
 * ホームの「次にやること」の段を決める（純関数）。主ボタンは常に 1 つ＝組立中と探索中だけ押せない。
 * 保存の失敗（[saveFailed]）と相談中（[consults]）は段を変えない＝完成の見出しと書き出しを止めない（D10）。
 */
/** ホームの主カードが［勤務表をつくる］を出す段（`homePlan` の MAKE・DRAFT と同じ条件）。このとき下部バーの同じボタンは隠す。 */
internal fun homeCardShowsMake(running: Boolean, fixSearching: Boolean, hasResult: Boolean, draft: Boolean, infeasible: Boolean): Boolean =
    !running && !fixSearching && (!hasResult || (draft && !infeasible))

internal fun homePlan(
    running: Boolean, hasResult: Boolean, hard0: Boolean,
    infeasible: Boolean = false, hasPinned: Boolean = false, guided: Boolean = false,
    hasMove: Boolean = false, focusBlank: Boolean = true, fixSearching: Boolean = false, fixSearched: Boolean = false,
    relax: Boolean = false, stalled: Boolean = false, wishes: Boolean = false,
    @Suppress("UNUSED_PARAMETER") saveFailed: Boolean = false, @Suppress("UNUSED_PARAMETER") consults: Int = 0,
    draft: Boolean = false,
): HomePlan = when {
    running -> HomePlan(HomePlanKind.RUNNING, HomeAction.NONE, false, null)
    !hasResult -> HomePlan(HomePlanKind.MAKE, HomeAction.MAKE, true, HomeAction.SMART_INITIAL)
    // 下書きのまま（手を入れていない）＝次は仕上げの作成。直す一覧へは進めない。
    draft && !infeasible -> HomePlan(HomePlanKind.DRAFT, HomeAction.MAKE, true, HomeAction.SCHEDULE)
    hard0 -> HomePlan(HomePlanKind.PRE_DIST, HomeAction.PRE_DIST, true, HomeAction.SCHEDULE)
    infeasible && hasPinned -> HomePlan(HomePlanKind.WISH_INFEASIBLE, HomeAction.SHOW_WISHES, true, HomeAction.LANDING)
    infeasible -> HomePlan(HomePlanKind.DATA_INFEASIBLE, HomeAction.LANDING, true, HomeAction.PRE_DIST)
    guided -> HomePlan(HomePlanKind.GUIDED_FIX, HomeAction.FIX, true, null)
    hasMove && focusBlank -> HomePlan(HomePlanKind.APPLY_MOVE, HomeAction.SHOW_MOVE, true, HomeAction.APPLY_MOVE)
    fixSearching -> HomePlan(HomePlanKind.SEARCHING, HomeAction.NONE, false, null)
    fixSearched && !hasMove && relax -> HomePlan(HomePlanKind.RELAX, HomeAction.SHOW_RELAX, true, if (wishes) HomeAction.SHOW_WISHES else null)
    fixSearched && !hasMove && stalled && wishes -> HomePlan(HomePlanKind.WISH_FLOOR, HomeAction.SHOW_WISHES, true, HomeAction.PRE_DIST)
    wishes -> HomePlan(HomePlanKind.WISHES, HomeAction.SHOW_WISHES, true, null)
    else -> HomePlan(HomePlanKind.LIST, HomeAction.SHOW_LIST, true, null)
}

/** ホームの主カードの区画。並びの順は `homeCardSections` が決め、`OperatorNextActionCard` はその順に描くだけ（C# `HomeCardSectionsOf` と 1 対 1）。 */
internal enum class HomeSection { CREATION, HEADLINE, BADGES, STATUS, PROGRESS, PRIMARY, HELPER, OTHERS, DETAIL, SATISFACTION, INFO, DRAFT_LINK }

/** 主ボタンが分析タブへ行く段（「ほかの問題を見る」と行き先が同じ）。 */
private val homeActionsToAnalysis = setOf(HomeAction.SHOW_MOVE, HomeAction.SHOW_LIST)

/**
 * ホームの主カードの並び（純関数）: 作成の 2 行 → 見出し → 札 → 状態（2 行まで）→ 主ボタン 1 つ → 補助 →「ほかの問題を見る」→
 * 詳細 → でき具合 → ⓘ → 下書きのリンク。実行中は進捗の行だけ。でき具合は結果があるときだけ（作っていないのに出さない）。札は検査済み（checkRev>0）のときだけ。
 */
internal fun homeCardSections(plan: HomePlan, ui: UiState): List<HomeSection> = buildList {
    if (ui.running) { add(HomeSection.PROGRESS); return@buildList }
    val creation = ui.hasResult && ui.engineRan
    if (creation) add(HomeSection.CREATION)
    add(HomeSection.HEADLINE)
    if (ui.hasResult && ui.checkRev > 0) add(HomeSection.BADGES)   // [3.716.0] 札は検査済みのときだけ（検査前に「満たしています」と言わない）
    add(HomeSection.STATUS)
    if (plan.primaryEnabled) add(HomeSection.PRIMARY)
    if (plan.helper != null || ui.hasResult) add(HomeSection.HELPER)
    if (creation && plan.primary !in homeActionsToAnalysis) add(HomeSection.OTHERS)
    add(HomeSection.DETAIL)
    if (ui.hasResult) { add(HomeSection.SATISFACTION); add(HomeSection.INFO); add(HomeSection.DRAFT_LINK) }
}

/** 下書きの段の状態の 1 文（次は仕上げの作成）。 */
internal fun homeDraftStatus(hard: String, bestHard: Long): String =
    "下書きができました" + (if (bestHard > 0L) "（必須 $hard）" else "") + "。「勤務表をつくる」で仕上げます"

internal const val HOME_MAKE_HEADLINE = "まだ作っていません"
internal const val HOME_HARD0_HEADLINE = "必須は満たしています"
internal const val HOME_MONTH_HEADLINE = "今月の判断"
internal const val HOME_SCHEDULE_HELPER = "表で全体を見る"
internal const val HOME_LIST_BUTTON = "必須を見る"
internal const val HOME_PRE_DIST_BUTTON = "配布前の確認"
internal const val PRE_DIST_TITLE = "配布前の確認"
internal const val HOME_SOFT_NOTE = "要調整は分析で確認"
internal const val HOME_OTHERS_BUTTON = "ほかの問題を見る"
internal const val INTERRUPTED_TITLE = "前回の勤務表づくりは途中で止まりました"
internal const val EMPTY_STATE_TITLE = "データを開く"

/** 未読込の補足。読込中は 1 文だけ。 */
internal fun emptyStateSubtitle(loading: Boolean): String = if (loading) "読み込んでいます…" else "保存データ・サンプル・新規から選べます"

/** 段ごとの見出し: 未作成は 1 文、結果があれば「今月の判断」（必須の有無は札が言う。3.713.0 デザイン例）。 */
internal fun homeHeadline(kind: HomePlanKind): String = when (kind) {
    HomePlanKind.MAKE -> HOME_MAKE_HEADLINE
    else -> HOME_MONTH_HEADLINE
}

/** 人員不足の 1 文（ホームの「いま決めること」と人員不足の手伝いの題）と、札・状態の行。 */
internal data class ShortageHeadline(val headline: String, val status: String, val badge: String)

internal fun shortageHeadline(dayLabel: String, shiftSymbol: String, need: Int, got: Int, miss: Int): ShortageHeadline =
    ShortageHeadline("${dayLabel.substringBefore('(')}の${shiftSymbol}が${miss}人不足", "配置 $got / 必要 $need", SHORTAGE_TAG)

internal const val SHORTAGE_TAG = "担当・希望の確認"

internal fun shortageHeadline(s: com.magi.app.v6.CoverageShortfall): ShortageHeadline = shortageHeadline(s.dayLabel, s.shiftSymbol, s.need, s.got, s.miss)

/** 必須の札は 1 つ＝残りの件数だけ（単位ごと＝INV-07）。必須 0 は「満たしています」。 */
internal fun homeHardPill(bestHard: Long, hardLabel: String): String = if (bestHard > 0L) "必須 残り$hardLabel" else HOME_HARD0_HEADLINE

/** 状態の行（2 行まで）。保存の失敗は段に関係なく先頭（SF-1）。必須あり＝問題の 1 文と相談中の行（D10）。必須 0＝相談中の行と、CSV が今の内容と違う行（保存済みの行は主ボタンの下）。 */
internal fun homeStatusLines(kind: HomePlanKind, sentence: String?, consultLine: String?, savedLine: String?, saveFailed: Boolean = false): List<String> =
    (listOfNotNull(HOME_SAVE_FAILED_LINE.takeIf { saveFailed }) +
        if (kind == HomePlanKind.PRE_DIST) listOfNotNull(consultLine, savedLine?.takeIf { it.contains("今の内容と違います") })
        else listOfNotNull(sentence?.takeIf { it.isNotBlank() }, consultLine)).take(2)

/**
 * 段の対象（「次に確認する問題」と主ボタンの括弧の中の両方に使う＝INV-01）。null＝対象なし。
 * 今すぐ直せる不足＝不足の枠、1手あり＝手の先頭のセル、希望の段＝関わる希望の先頭、緩める＝その組、残りの一覧と探索中＝巡回の先頭。
 */
internal fun creationNextTarget(kind: HomePlanKind, shortage: String?, move: String?, wish: String?, relax: String?, tour: String?): String? = when (kind) {
    HomePlanKind.GUIDED_FIX -> shortage
    HomePlanKind.APPLY_MOVE -> move
    HomePlanKind.WISH_INFEASIBLE, HomePlanKind.WISH_FLOOR, HomePlanKind.WISHES -> wish
    HomePlanKind.RELAX -> relax
    HomePlanKind.LIST, HomePlanKind.SEARCHING -> tour
    else -> null
}

/** 争点カード（D03）の 1 案: 案の名、理由（推奨A だけ。案から数え直した対象の変化）、選んだときに確認へ渡す案。 */
internal data class ContestOption(val label: String, val reason: String?, val suggestion: com.magi.app.v6.FixSuggestion)

/** 争点カード（D03）: 見ている必須違反の題と、既存の主一覧の上位 2 件（推奨A／案B）。 */
internal data class ContestCard(val title: String, val a: ContestOption, val b: ContestOption?)

internal const val CONTEST_A_BUTTON = "推奨Aで進む"
internal const val CONTEST_B_BUTTON = "案Bにする"

/**
 * 争点カードを組む。[main] は画面の主一覧と同じ順の案（新しく探さない・並べ替えない）。対象が必須違反（日の人員の不足）でない、
 * または案が無いときは null（案が無いときは従来の「案が見つからない」へ）。A と B は同じ [target] を直す案だけ＝INV-01。
 */
internal fun contestCard(target: FixTarget, label: String, main: List<com.magi.app.v6.FixSuggestion>, schedule: List<List<Int>>,
                         staffNames: List<String>, shiftSymbols: List<String>): ContestCard? {
    if (target.day == null || target.multi || !target.under) return null
    val picks = main.filter { target.effect(schedule, it.ops).fixes }.take(2)
    if (picks.isEmpty()) return null
    fun name(s: com.magi.app.v6.FixSuggestion) =
        surplusMoveTitle(target, schedule, s.ops, staffNames, shiftSymbols) ?: fixDisplayLabel(s.label)
    val e = target.effect(schedule, picks[0].ops)
    val a = ContestOption("推奨A: ${name(picks[0])}", "理由: ${e.afterNote(target.basis)}（${e.gapLine()}）", picks[0])
    val b = picks.getOrNull(1)?.let { ContestOption("案B: ${name(it)}", null, it) }
    return ContestCard("${label}の${target.gapWord}", a, b)
}

/** 勤務表タブの状態の 1 行の上限（D4 の幅で 1 行に収まる字数）。超えたら末尾を「…」にする。 */
internal const val SCHEDULE_STATE_MAX = 23

/** 勤務表タブの状態の 1 行。[tourFirst]＝押すと巡回の先頭を開く（必須違反があり、セルで辿れるときだけ）。 */
internal data class ScheduleStateLine(val text: String, val hard: Boolean, val tourFirst: Boolean)

/**
 * 状態の 1 行（G2-1）。未作成・組んでいます・必須あり（巡回の先頭を添える）・必須は人員不足だけ・必須 0 の 5 通り。
 * [next] は巡回の先頭の「M/D 名」（無ければ人員不足だけ）。必須の数は単位ごと（INV-07）の [hardText] をそのまま使う。
 */
internal fun scheduleStateLine(hasResult: Boolean, running: Boolean, bestHard: Long, hardText: String, softText: String, next: String?): ScheduleStateLine {
    val (text, hard, tour) = when {
        running -> Triple("組んでいます", false, false)
        !hasResult -> Triple("まだ作っていません", false, false)
        bestHard > 0L && next != null -> Triple("必須違反 $hardText（次：${next}さん）", true, true)
        bestHard > 0L -> Triple("必須違反 $hardText（日の印から開けます）", true, false)
        else -> Triple("必須違反はありません · 要調整 $softText", false, false)
    }
    return ScheduleStateLine(capStateLine(text), hard, tour)
}

internal fun capStateLine(text: String, max: Int = SCHEDULE_STATE_MAX): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

/** 必須違反の一覧の見出し（単位ごとの件数を添える）と、先頭の主ボタンの語（G2-3）。 */
internal fun hardListTitle(hardText: String): String = "残っている問題 $hardText"
internal const val HARD_LIST_START_BUTTON = "直し方を見る"

/** セルの確認シートの題（S-1）。 */
internal const val CELL_SHEET_TITLE = "このマスの確認"

/** 対象の行「{名}さん · {M/D(曜)} · {記号}」（S-2）。今の勤務はここで言う（空のセルは記号を省く）。 */
internal fun cellSheetTarget(name: String, dayLabel: String, symbol: String?): String =
    "${name}さん · $dayLabel" + (symbol?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

/** ちら見の 2 行目の札。必須は巡回中なら「必須 2/5」。問題が無ければ null（札を出さない）。 */
internal fun cellSheetBadge(severity: CellSeverity, tourAt: Int? = null, tourTotal: Int = 0): String? = when (severity) {
    CellSeverity.HARD -> if (tourAt != null && tourTotal > 0) "必須 ${tourAt + 1}/$tourTotal" else "必須"
    CellSeverity.SOFT -> "要調整"
    CellSeverity.NONE -> null
}

/** 足りない側の人探しのボタンを塗りにするか（S-4）。セルに人員不足以外の問題もあれば外枠（主は「変更案を探す」＝INV-09）。 */
internal fun shortSideFilled(families: List<String>): Boolean = families.all { it == "covU" }

/** [3.707.0 E-2] 編集タブの見出しカードの主ボタン（塗りは 1 つ＝INV-09）。RERUN は下部バーの「再作成」、PANEL は開いている入力パネルの適用ボタン。 */
internal enum class EditHeaderPrimary { EDIT_NEED, OPEN_MASTER, ADD_STAFF, RERUN, PANEL }
internal enum class EditHeaderSecondary { WISH_CONFLICTS }

/** 見出しカードに要る数（画面の状態から集める）。[month] は「2026年11月」、無ければ空。 */
internal data class EditHeaderCounts(
    val month: String = "", val staff: Int = 0, val shifts: Int = 0,
    val wish: WishEntryCounts = WishEntryCounts(0, 0, 0), val needStdOk: Boolean = false, val needExceptions: Int = 0,
    val issues: Int = 0, val wishConflicts: Boolean = false,
    val rules: Int = 0, val ranges: Int = 0, val shortShifts: Int = 0,
)

internal data class EditHeaderPlan(
    val title: String, val subtitle: String, val points: List<String>,
    val primary: EditHeaderPrimary?, val secondary: EditHeaderSecondary?,
    val notice: String? = null,
)

internal const val EDIT_SCOPE_MONTHLY_NOTE = "翌月だけの条件：希望・必要人数・例外（毎月ここから）"
internal const val EDIT_SCOPE_STAFF_NOTE = "入退職・所属・資格スキル（随時変更）。職員の一覧はここだけ"
internal const val EDIT_SCOPE_MASTER_NOTE = "制度が変わったときだけ"
internal const val EDIT_MASTER_EMPTY_NOTE = "まず①でシフトを追加しましょう"
internal const val OPEN_MASTER_BUTTON = "基本設定を開く"
internal const val ADD_STAFF_BUTTON = "職員を追加する"
internal const val EDIT_NEED_BUTTON = "必要人数を編集する"
internal const val WISH_CONFLICTS_BUTTON = "ぶつかっている希望を見る"

/**
 * [3.707.0 E-2] 編集タブ（[scope] 0=月次条件／1=職員管理／2=年間マスター）の見出し。点は 3 つまで、1 点に単位は 1 つ（INV-07）。
 * 主ボタン（Q10）: 実行中は無し、入力パネルを開いている間は PANEL、結果のあとに条件を変えた（[ranBeforeEdit]）なら RERUN、
 * 職員かシフトが無ければ OPEN_MASTER（シフトはあって職員だけ無ければ ADD_STAFF）、それ以外は区分ごとの主（月次＝EDIT_NEED）。
 */
internal fun editHeaderPlan(scope: Int, counts: EditHeaderCounts, panelOpen: Boolean, ranBeforeEdit: Boolean, running: Boolean): EditHeaderPlan {
    val title = when (scope) {
        0 -> if (counts.month.isBlank()) "月次条件" else "月次条件（${counts.month}）"
        1 -> "職員管理"
        else -> "年間マスター"
    }
    val subtitle = when (scope) { 0 -> EDIT_SCOPE_MONTHLY_NOTE; 1 -> EDIT_SCOPE_STAFF_NOTE; else -> EDIT_SCOPE_MASTER_NOTE }
    val points = when (scope) {
        0 -> listOf(
            "希望・休暇 ${wishEntryText(counts.wish)}",
            "必要人数 " + (if (counts.needStdOk) "標準あり" else "標準が未設定") + "・例外 ${counts.needExceptions}件",
            "入力診断 " + (if (counts.issues == 0) "問題なし" else "見直し ${counts.issues}件"),
        )
        1 -> listOf("職員 ${counts.staff}人")
        else -> listOf("ルール ${counts.rules}件", "ひとりの回数の上限・下限 ${counts.ranges}件", "担当が足りないシフト ${counts.shortShifts}種類")
    }
    val primary = when {
        running -> null
        panelOpen -> EditHeaderPrimary.PANEL
        ranBeforeEdit -> EditHeaderPrimary.RERUN
        counts.shifts == 0 -> EditHeaderPrimary.OPEN_MASTER
        counts.staff == 0 -> EditHeaderPrimary.ADD_STAFF
        scope == 0 -> EditHeaderPrimary.EDIT_NEED
        scope == 1 -> EditHeaderPrimary.ADD_STAFF
        else -> EditHeaderPrimary.OPEN_MASTER
    }
    val secondary = if (scope == 0 && counts.wishConflicts) EditHeaderSecondary.WISH_CONFLICTS else null
    val notice = if (scope == 2 && (counts.staff == 0 || counts.shifts == 0)) EDIT_MASTER_EMPTY_NOTE else null
    return EditHeaderPlan(title, subtitle, points, primary, secondary, notice)
}

/** [3.720.0 3c-2] 体制の 1 行（人の単位だけ＝INV-07）。[slack]＝担当できる人数 − 1 日に要る最大の人数。 */
internal fun staffingRowText(canDo: Int, maxNeed: Int): String {
    val slack = canDo - maxNeed
    return "担当${canDo}人・1日最大${maxNeed}人・" + when {
        slack < 0 -> "${-slack}人足りない"
        slack == 0 -> "欠勤の余裕なし"
        else -> "余裕${slack}人"
    }
}

/**
 * [3.720.0] 問題一覧で選んだ行（同じマスに行が複数あっても押した行だけ＝INV-01）。
 * [3.721.1] 一覧が作り直されて行番号がずれても同じ族の行に留まる: 行番号のマス・族が一致 → 同じマス・族の先頭 → そのマスの先頭。
 */
internal fun problemPickedIndex(rows: List<ProblemRow>, pickedIdx: Int?, cell: Pair<Int, Int>?, pickedFamily: String? = null): Int? {
    if (cell == null) return null
    fun same(r: ProblemRow) = r.cell == cell && (pickedFamily == null || r.family == pickedFamily)
    if (pickedIdx != null && rows.getOrNull(pickedIdx)?.let(::same) == true) return pickedIdx
    rows.indexOfFirst(::same).takeIf { it >= 0 }?.let { return it }
    return rows.indexOfFirst { it.cell == cell }.takeIf { it >= 0 }
}

/** [3.707.0 3a-3] 下部バーの作成ボタンを外枠にするか（編集タブでは見出しの主が RERUN のときだけ塗り）。 */
internal fun editBarSecondary(plan: EditHeaderPlan): Boolean = plan.primary != null && plan.primary != EditHeaderPrimary.RERUN

/** [3.707.0 3b-1] 希望カードの職員の行。 */
internal fun wishStaffSummary(name: String, wishes: Int, extWishes: Int): String = "${name}さん：希望 ${wishes}件・拡張希望 ${extWishes}件"

/** [3.707.0 3b-2] その職員のぶつかっている希望への文字ボタン（件数は出さない）。 */
internal fun wishConflictLinkText(dayLabel: String): String = "${dayLabel}のぶつかる希望を見る"

/** [3.707.0 3b-4] 希望の入力パネルのボタン。 */
internal fun wishApplyButton(days: Int, symbol: String): String = "${days}日を${symbol}の希望にする"
internal fun extWishApplyButton(days: Int): String = "${days}日を選んだシフト以外にする"
internal const val CLEAR_SELECTION_BUTTON = "選択を外す"
internal fun needApplyButton(days: Int): String = "${days}日に適用する"

/** [3.707.0 3b-5] 担当外のシフトを選んだときの注記。 */
internal fun wishOutOfScopeNote(symbol: String, name: String): String =
    "⚠「$symbol」は${name}さんの担当外です。希望は登録できますが、配置すると必須違反になります。"

/** [3.707.0 3b-5] 拡張希望の登録に失敗したときの文（できなかったこと・原因・次の行動）。 */
internal fun extWishFailedText(reason: String): String =
    "拡張希望を登録できませんでした。${reason.trimEnd('。')}。日かシフトを選び直して、もう一度登録してください。"

/** [3.712.0] ぶつかっている希望の行の 1 行目: 「福澤俊陽 · 10/1」（理由は次の行。題の下の件数の要約は 3.712.0 で撤去）。 */
internal fun wishRowHead(name: String, day: String): String = "$name · $day"

/** [3.707.0 3a-6] 着地先が必要人数の日なら、その日の人員不足の 1 文（3.698.0 の shortageHeadline）。無ければ null。 */
internal fun editReturnProblem(shortfalls: List<com.magi.app.v6.CoverageShortfall>, shift: Int?, day: Int?): String? {
    if (shift == null || day == null) return null
    return shortfalls.firstOrNull { it.shiftIndex == shift && it.dayIndex == day }?.let { shortageHeadline(it).headline }
}

/** [3.720.0 3c-6] 回数のシートで違反を 1 タップで解く外枠ボタン（「回」を付ける）。 */
internal fun raiseCapButton(n: Int): String = "上限を${n}回に上げて適用"
internal fun lowerFloorButton(n: Int): String = "下限を${n}回に下げて適用"

/** ホームの「原因の診断」に畳むカードのうち、いま中身があるものの数（各カードの表示条件と同じ）。 */
internal fun homeDiagnosisCount(ui: UiState): Int = listOf(
    ui.copilotHint != null || (ui.polishExhausted && !ui.running),
    ui.coverageDiag?.let { it.hasShortage || it.hasSurplus } == true,
    ui.forbiddenDiag?.hasRuns == true,
    ui.c1Plateau?.let { it.causeUnknown || it.hasEntries } == true,
    ui.observedPinBlockedAttempts > 0,
).count { it }
