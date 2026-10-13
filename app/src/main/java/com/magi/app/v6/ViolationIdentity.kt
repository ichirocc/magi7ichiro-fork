package com.magi.app.v6

/**
 * 違反の同一性（3.663.0、違反セルの半自動修正仕様 §9・利用者決定「推奨案で進める」）。
 * セルの違反＝種類＋職員＋日付、被覆の違反＝種類＋シフト＋日付、回数の違反＝種類＋職員＋シフト。
 * 「件数が同じか減った」だけでは、A さんの違反を解消して B さんに同じ種類の違反を作る手を見分けられない
 * （[newHardFamilyViolation] は族の件数しか見ない）＝場所ごとの鍵で前後を比べる。
 */
data class ViolationKey(val family: String, val space: Space, val a: Int, val b: Int) {
    /** [CELL]＝(職員, 日)、[NEED]＝(シフト, 日)、[COUNT]＝(職員, シフト)。 */
    enum class Space { CELL, NEED, COUNT }
    val hard: Boolean get() = family in MirrorKeys.hard
}

object ViolationIdentity {
    /** 報告に載っている違反の鍵（重なった族も全部。aptLow/aptHigh は apt）。 */
    fun keys(r: ViolationReport, hardOnly: Boolean = false): Set<ViolationKey> {
        val out = LinkedHashSet<ViolationKey>()
        fun add(m: Map<String, List<String>>, space: ViolationKey.Space) {
            for ((pos, classes) in m) {
                val comma = pos.indexOf(',')
                val a = pos.substring(0, comma).toIntOrNull() ?: continue
                val b = pos.substring(comma + 1).toIntOrNull() ?: continue
                for (cls in classes) {
                    val fam = cls.removePrefix("vio-").let { if (it == "aptLow" || it == "aptHigh") "apt" else it }
                    val k = ViolationKey(fam, space, a, b)
                    if (!hardOnly || k.hard) out.add(k)
                }
            }
        }
        add(r.cellFamilies, ViolationKey.Space.CELL)
        add(r.needFamilies, ViolationKey.Space.NEED)
        add(r.countFamilies, ViolationKey.Space.COUNT)
        return out
    }

    /** 前後の差。[resolved]＝前にあって後に無い、[appeared]＝後で新たに生まれた。 */
    data class Diff(val resolved: Set<ViolationKey>, val appeared: Set<ViolationKey>)

    fun diff(before: ViolationReport, after: ViolationReport): Diff {
        val b = keys(before); val a = keys(after)
        return Diff(b - a, a - b)
    }

    /** 後で新たに生まれた必須違反の鍵（件数が減っていても、別の職員・日・枠に生まれたものは数える）。 */
    fun newHard(before: ViolationReport, after: ViolationReport): Set<ViolationKey> = keys(after, hardOnly = true) - keys(before, hardOnly = true)
}

/** 利用者に見せる 1 手（改善案）の境界の規則: 必須の族の件数が増えるか、別の場所に必須違反が新たに生まれる手は通さない。 */
fun introducesHardViolation(before: ViolationReport, after: ViolationReport): Boolean =
    newHardFamilyViolation(before, after) != null || ViolationIdentity.newHard(before, after).isNotEmpty()
