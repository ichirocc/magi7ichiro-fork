package com.magi.app.ui

/** 「今月の作成条件」の希望の行（3.643.0）: 登録の有無だけを数え、「集め終わったか」は判定しない。 */
internal data class WishEntryCounts(val entered: Int, val extOnly: Int, val noInput: Int)

/** [wishKeys]＝通常希望のセル鍵 "i,j"、[extKeys]＝拡張希望で禁止のあるセル鍵 "i,j"。職員の重複は除く。 */
internal fun wishEntryCounts(staffN: Int, wishKeys: Collection<String>, extKeys: Collection<String>): WishEntryCounts {
    fun staffOf(keys: Collection<String>) = keys.mapNotNull { it.substringBefore(",").toIntOrNull() }.filter { it in 0 until staffN }.toSet()
    val regular = staffOf(wishKeys); val ext = staffOf(extKeys)
    val entered = (regular + ext).size
    return WishEntryCounts(entered = entered, extOnly = (ext - regular).size, noInput = (staffN - entered).coerceAtLeast(0))
}

internal fun wishEntryText(c: WishEntryCounts): String =
    "入力あり ${c.entered}名" + (if (c.extOnly > 0) "（拡張希望のみ ${c.extOnly}名）" else "") + "・未入力 ${c.noInput}名"
