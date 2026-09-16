package com.magi.app.ui

import com.magi.app.model.MagiState

/** 族ひとつぶんの見出しと、表示用に整形した行。 */
internal data class ConstraintFamilyView(val key: String, val title: String, val rows: List<String>)

/** 1日人数の上下限を意味で圧縮して短く表す。見出しが「人数(上下限)」の文脈を担うので行は記号のみで足りる。 */
private fun boundLabel(l: String, u: String): String {
    val lo = l.ifBlank { null }; val hi = u.ifBlank { null }
    return when {
        lo != null && hi != null && lo == hi -> "ちょうど$lo"
        lo != null && hi != null -> "$lo〜$hi"
        lo != null -> "$lo 以上"
        hi != null -> "$hi 以下"
        else -> "制限なし"
    }
}

internal fun seqFamilyJp(family: String): String = when (family) {
    "cons3" -> "必須の並び"
    "cons3n" -> "禁止の並び"
    "cons3m" -> "推奨の並び"
    "cons3mn" -> "回避の並び"
    else -> family
}

/** 並びの正規化（先頭から最初の空白まで・最大5）。追加・変更・重複判定で同じ規則を使う。 */
internal fun normalizeSeq(pattern: List<String>): List<String> =
    pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5)

/**
 * 制約エディタが描くのに要るものを、`MagiState` から一度だけ組み立てたもの。
 * Composable はこれと `onEvent` だけを受け取る＝画面から ViewModel への問い合わせを無くす。
 */
internal data class ConstraintsView(
    val families: List<ConstraintFamilyView> = emptyList(),
    val skillFamilies: List<ConstraintFamilyView> = emptyList(),
    val shiftKigou: List<String> = emptyList(),
    val groupKigou: List<String> = emptyList(),
    val skillGroupKigou: List<String> = emptyList(),
    /** 族 -> 各行の生値（編集ダイアログのプリフィル用。並びは追加ダイアログの入力順）。 */
    val rows: Map<String, List<List<String>>> = emptyMap(),
) {
    fun rowValues(family: String, index: Int): List<String>? = rows[family]?.getOrNull(index)

    /**
     * 同じ並びが既にあれば、その族の日本語名を返す。族をまたいで見る＝HARD の禁止と SOFT の回避へ
     * 同じ並びを二重掛けしても評価は禁止側が支配し回避側は無意味なので、入口で知らせる。
     * `excludeIndex` は変更時に自分自身を除く。
     */
    fun duplicateOf(family: String, pattern: List<String>, excludeIndex: Int? = null): String? {
        val key = normalizeSeq(pattern).joinToString("→")
        if (key.isBlank()) return null
        for (fam in listOf("cons3", "cons3n", "cons3m", "cons3mn")) {
            rows[fam]?.forEachIndexed { idx, raw ->
                if (!(fam == family && idx == excludeIndex) &&
                    normalizeSeq(raw).joinToString("→") == key
                ) return seqFamilyJp(fam)
            }
        }
        return null
    }
}

internal fun constraintsViewOf(st: MagiState?): ConstraintsView {
    if (st == null) return ConstraintsView()
    fun seq(p: List<String>) = p.filter { it.isNotBlank() }.joinToString(" -> ").ifEmpty { "(空)" }
    return ConstraintsView(
        // 節タイトルは違反チップ(breakdownLabels)の語彙を正として一致させる
        //   （違反を見て設定を直しに来たとき同じ名前で見つかるように）。
        families = listOf(
            ConstraintFamilyView("cons1", "期間の制約（○日間で○回など）",
                st.cons1.map { "${it.shiftKigou}   ${it.day1}日で${it.day2}回以上" }),
            ConstraintFamilyView("cons2", "個人の合計（回数）",
                st.cons2.map { "${it.shiftKigou}   合計${it.count}回以上" }),
            ConstraintFamilyView("cons3", "必須の並び", st.cons3.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3n", "禁止の並び", st.cons3n.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3m", "推奨の並び", st.cons3m.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3mn", "回避の並び", st.cons3mn.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3w", "希望の前日に禁止（必ず守る）",
                st.cons3w.map { "${it.wishKigou} の希望の前日は ${it.prevKigou} 禁止" }),
            ConstraintFamilyView("cons41", "グループのレンジ（1日の人数の下限〜上限）",
                st.cons41.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
            ConstraintFamilyView("cons42", "グループペア禁止（同じ日に不可・できるだけ守る）",
                st.cons42.map { "${it.g1Kigou}の${it.s1Kigou} ✕ ${it.g2Kigou}の${it.s2Kigou}" }),
        ),
        skillFamilies = listOf(
            ConstraintFamilyView("cons41s", "スキルグループのレンジ（1日の人数の下限〜上限）",
                st.cons41s.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
            ConstraintFamilyView("cons42s", "スキルグループペア禁止（同じ日に不可・できるだけ守る）",
                st.cons42s.map { "${it.g1Kigou}の${it.s1Kigou} ✕ ${it.g2Kigou}の${it.s2Kigou}" }),
        ),
        shiftKigou = st.shifts.map { it.kigou },
        groupKigou = st.groups.map { it.kigou },
        skillGroupKigou = st.skillGroups.map { it.kigou },
        rows = mapOf(
            "cons1" to st.cons1.map { listOf(it.day1, it.shiftKigou, it.day2) },
            "cons2" to st.cons2.map { listOf(it.shiftKigou, it.count) },
            "cons3" to st.cons3.map { it.pattern },
            "cons3n" to st.cons3n.map { it.pattern },
            "cons3m" to st.cons3m.map { it.pattern },
            "cons3mn" to st.cons3mn.map { it.pattern },
            "cons3w" to st.cons3w.map { listOf(it.wishKigou, it.prevKigou) },
            "cons41" to st.cons41.map { listOf(it.groupKigou, it.shiftKigou, it.l, it.u) },
            "cons41s" to st.cons41s.map { listOf(it.groupKigou, it.shiftKigou, it.l, it.u) },
            "cons42" to st.cons42.map { listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou) },
            "cons42s" to st.cons42s.map { listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou) },
        ),
    )
}
