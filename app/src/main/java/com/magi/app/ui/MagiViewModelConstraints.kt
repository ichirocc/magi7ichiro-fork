package com.magi.app.ui

import com.magi.app.model.C1Row
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.C3wRow
import kotlinx.coroutines.flow.update

/**
 * [MagiViewModel] の制約CRUD（cons1/cons2/cons3系4種/cons41(s)/cons42(s) の一覧・追加・変更・削除）。
 * 本体ファイルから extension 関数として抽出（責務別の物理分割＝AIコードレビュー時のコンテキスト
 * 圧迫対策）。ロジックは一切変更していない。
 *
 * MagiViewModel は class のため v6 層（object 分割）と違い partial class 相当が無く、分割は
 * 「extension 関数を別ファイルへ置く」形をとる。触るメンバは state（読み取りのみ・internal +
 * private set）・logOp・mutateConstraints（編集ゲート＝本体に残置。undo/editRev/再検査/自動保存は
 * すべてこのゲートが担う）の3つだけで、いずれも internal 昇格＝モジュール内限定の可視化。
 *
 * 呼出側（ConstraintEditor/SkillGroupEditor）は同一パッケージのため無修正で拡張関数へ解決される。
 */
fun MagiViewModel.groupKigouList(): List<String> = state?.groups?.map { it.kigou } ?: emptyList()

internal fun MagiViewModel.constraintFamilies(): List<ConstraintFamilyView> = constraintsViewOf(state).families

internal fun MagiViewModel.skillConstraintFamilies(): List<ConstraintFamilyView> = constraintsViewOf(state).skillFamilies

fun MagiViewModel.skillGroupKigouList(): List<String> = state?.skillGroups?.map { it.kigou } ?: emptyList()
fun MagiViewModel.addCons41s(groupKigou: String, shiftKigou: String, l: String, u: String) {
    val st = state ?: return
    logOp("I", "制約追加(スキルグループ回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41s = st.cons41s + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
}
fun MagiViewModel.addCons42s(g1: String, g2: String, s1: String, s2: String) {
    val st = state ?: return
    logOp("I", "制約追加(スキルグループ組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42s = st.cons42s + C42Row(g1, g2, s1, s2)))
}

fun MagiViewModel.addCons1(day1: String, shiftKigou: String, day2: String) {
    val st = state ?: return
    logOp("I", "制約追加(連勤/休): ${day1.trim()}日に${shiftKigou}${day2.trim()}回以上"); mutateConstraints(st.copy(cons1 = st.cons1 + C1Row(day1.trim(), shiftKigou, day2.trim())))
}

fun MagiViewModel.addCons2(shiftKigou: String, count: String) {
    val st = state ?: return
    logOp("I", "制約追加(cons2): $shiftKigou ${count.trim()}"); mutateConstraints(st.copy(cons2 = st.cons2 + C2Row(shiftKigou, count.trim())))
}

fun MagiViewModel.addCons41(groupKigou: String, shiftKigou: String, l: String, u: String) {
    val st = state ?: return
    logOp("I", "制約追加(グループ回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41 = st.cons41 + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
}

fun MagiViewModel.addCons42(g1: String, g2: String, s1: String, s2: String) {
    val st = state ?: return
    logOp("I", "制約追加(グループ組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42 = st.cons42 + C42Row(g1, g2, s1, s2)))
}

fun MagiViewModel.addCons3w(wishKigou: String, prevKigou: String) {
    val st = state ?: return
    if (st.cons3w.any { it.wishKigou == wishKigou && it.prevKigou == prevKigou }) {
        _ui.update { it.copy(messageIsError = true, message = "「${wishKigou} の希望の前日は ${prevKigou} 禁止」は登録済みです") }
        return
    }
    logOp("I", "制約追加(希望前日禁止): ${wishKigou} の希望の前日は ${prevKigou} 禁止"); mutateConstraints(st.copy(cons3w = st.cons3w + C3wRow(wishKigou, prevKigou)))
}

/**
 * [3.482.0 入口ガード] 同じ並びが既に登録されていれば、その族の日本語名を返す（無ければ null）。
 * 旧: 追加/変更のどちらにも重複検出が無く、見本データの `Dﾃ→A4` が禁止の並びに2行入ったまま
 * 事後診断（V6SanityPort の DELETE_DUP_SEQ）が「重複を1つ削除」を提案するだけだった＝入口で止める。
 * 族をまたぐ同一の並び（例: 禁止と回避に同じ `Cｵ→Aｱ`）も返す。HARD の禁止と SOFT の回避を同じ並びへ
 * 二重掛けしても評価は禁止側が支配し回避側は無意味＝登録の意図と違う可能性が高いので、事後診断
 * （族内しか見ない `collectDuplicateSeq`）より一段厳しく入口で知らせる。
 * 正規化は addCons3 と同じ（先頭から最初の空白まで・最大5）。`excludeIndex` は変更時に自分自身を除く。
 */
fun MagiViewModel.seqDuplicateOf(family: String, pattern: List<String>, excludeIndex: Int? = null): String? =
    constraintsViewOf(state).duplicateOf(family, pattern, excludeIndex)

fun MagiViewModel.addCons3(family: String, pattern: List<String>) {
    val st = state ?: return
    // Level Zero loads cons3 by reading day columns until the first blank (truncate at
    // first blank, max 5 days), not by removing all blanks. Match that here.
    val pat = pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5)
    if (pat.isEmpty()) return
    // [3.482.0 入口ガード] 画面（ConstraintDialog）でも同じ判定で OK を無効化しているが、VM 側でも止める
    //   （CSV 取込など画面を通らない経路は従来どおり事後診断に任せる＝ここは画面経由の追加だけ）。
    seqDuplicateOf(family, pat)?.let { dupFam ->
        logOp("W", "制約追加を無視($family): ${pat.joinToString("→")} は「$dupFam」に登録済み")
        _ui.update { it.copy(messageIsError = true, message = "同じ並び「${pat.joinToString("→")}」は「$dupFam」に登録済みです") }
        return
    }
    logOp("I", "制約追加($family): ${pat.joinToString("→")}")
    mutateConstraints(
        when (family) {
            "cons3" -> st.copy(cons3 = st.cons3 + C3Row(pat))
            "cons3n" -> st.copy(cons3n = st.cons3n + C3Row(pat))
            "cons3m" -> st.copy(cons3m = st.cons3m + C3Row(pat))
            "cons3mn" -> st.copy(cons3mn = st.cons3mn + C3Row(pat))
            else -> return
        }
    )
}

fun MagiViewModel.removeConstraint(family: String, index: Int) {
    val st = state ?: return
    // [3.271.0, 実機ログ起因] index を先に検証する。旧: 検証なしで先にログ→mutate のため、
    //   リスト縮小後の古い index（連続タップ等）でも「制約削除: cons3mn[7]」の幻ログ＋無駄な
    //   undo/保存/再検査が走り、実機ログで「2回削除されたのか1回なのか」が判別不能だった
    //   （without() 自体は no-op なのでデータは壊れない＝ログと副作用だけが嘘をつく状態）。
    val size = when (family) {
        "cons1" -> st.cons1.size
        "cons2" -> st.cons2.size
        "cons3" -> st.cons3.size
        "cons3n" -> st.cons3n.size
        "cons3m" -> st.cons3m.size
        "cons3mn" -> st.cons3mn.size
        "cons41" -> st.cons41.size
        "cons42" -> st.cons42.size
        "cons41s" -> st.cons41s.size
        "cons42s" -> st.cons42s.size
        "cons3w" -> st.cons3w.size
        else -> return
    }
    if (index !in 0 until size) {
        logOp("W", "制約削除を無視: $family[$index] は存在しません（削除済みの行への連続タップ等）")
        return
    }
    logOp("I", "制約削除: $family[$index]")
    fun <T> List<T>.without(i: Int) = filterIndexed { idx, _ -> idx != i }
    mutateConstraints(
        when (family) {
            "cons1" -> st.copy(cons1 = st.cons1.without(index))
            "cons2" -> st.copy(cons2 = st.cons2.without(index))
            "cons3" -> st.copy(cons3 = st.cons3.without(index))
            "cons3n" -> st.copy(cons3n = st.cons3n.without(index))
            "cons3m" -> st.copy(cons3m = st.cons3m.without(index))
            "cons3mn" -> st.copy(cons3mn = st.cons3mn.without(index))
            "cons41" -> st.copy(cons41 = st.cons41.without(index))
            "cons42" -> st.copy(cons42 = st.cons42.without(index))
            "cons41s" -> st.copy(cons41s = st.cons41s.without(index))
            "cons42s" -> st.copy(cons42s = st.cons42s.without(index))
            "cons3w" -> st.copy(cons3w = st.cons3w.without(index))
            else -> return
        }
    )
}

/** [制約編集/実機指摘「登録した制約の変更ができない」] 行の生値（編集ダイアログのプリフィル用）。
 *  値の並びは追加ダイアログの入力順と同じ:
 *  cons1=[日数,シフト,回数] / cons2=[シフト,回数] / cons3系=並び(最大5) /
 *  cons41(s)=[群,シフト,下限,上限] / cons42(s)=[群1,シフト1,群2,シフト2] / cons3w=[希望シフト,前日禁止シフト]。 */
/** [制約編集] 行を同じ位置で置き換える。values の並びは constraintRowValues と同一。
 *  cons3系は追加(addCons3)と同じ正規化（先頭から最初の空白まで・最大5）。 */
fun MagiViewModel.updateConstraint(family: String, index: Int, values: List<String>) {
    val st = state ?: return
    fun <T> List<T>.replaced(i: Int, v: T) = mapIndexed { idx, e -> if (idx == i) v else e }
    val v = values.map { it.trim() }
    fun g(i: Int) = v.getOrElse(i) { "" }
    val next = when (family) {
        "cons1" -> { if (index !in st.cons1.indices) return; st.copy(cons1 = st.cons1.replaced(index, C1Row(g(0), g(1), g(2)))) }
        "cons2" -> { if (index !in st.cons2.indices) return; st.copy(cons2 = st.cons2.replaced(index, C2Row(g(0), g(1)))) }
        "cons41" -> { if (index !in st.cons41.indices) return; st.copy(cons41 = st.cons41.replaced(index, C41Row(g(0), g(1), g(2), g(3)))) }
        "cons41s" -> { if (index !in st.cons41s.indices) return; st.copy(cons41s = st.cons41s.replaced(index, C41Row(g(0), g(1), g(2), g(3)))) }
        "cons42" -> { if (index !in st.cons42.indices) return; st.copy(cons42 = st.cons42.replaced(index, C42Row(g(0), g(2), g(1), g(3)))) }
        "cons42s" -> { if (index !in st.cons42s.indices) return; st.copy(cons42s = st.cons42s.replaced(index, C42Row(g(0), g(2), g(1), g(3)))) }
        "cons3w" -> { if (index !in st.cons3w.indices) return; st.copy(cons3w = st.cons3w.replaced(index, C3wRow(g(0), g(1)))) }
        "cons3", "cons3n", "cons3m", "cons3mn" -> {
            val pat = v.takeWhile { it.isNotEmpty() }.take(5)
            if (pat.isEmpty()) return
            // [3.482.0 入口ガード] 変更後の並びが他の行（自分自身は除く）と同じなら止める（addCons3 と同じ）。
            seqDuplicateOf(family, pat, excludeIndex = index)?.let { dupFam ->
                logOp("W", "制約変更を無視($family[$index]): ${pat.joinToString("→")} は「$dupFam」に登録済み")
                _ui.update { it.copy(messageIsError = true, message = "同じ並び「${pat.joinToString("→")}」は「$dupFam」に登録済みです") }
                return
            }
            when (family) {
                "cons3" -> { if (index !in st.cons3.indices) return; st.copy(cons3 = st.cons3.replaced(index, C3Row(pat))) }
                "cons3n" -> { if (index !in st.cons3n.indices) return; st.copy(cons3n = st.cons3n.replaced(index, C3Row(pat))) }
                "cons3m" -> { if (index !in st.cons3m.indices) return; st.copy(cons3m = st.cons3m.replaced(index, C3Row(pat))) }
                else -> { if (index !in st.cons3mn.indices) return; st.copy(cons3mn = st.cons3mn.replaced(index, C3Row(pat))) }
            }
        }
        else -> return
    }
    logOp("I", "制約変更: $family[$index] → ${v.filter { it.isNotBlank() }.joinToString(" ")}")
    mutateConstraints(next)
}
