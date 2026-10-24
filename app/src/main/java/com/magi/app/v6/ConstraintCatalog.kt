package com.magi.app.v6

/** 違反の位置のキー空間（全面改修 P5）。チェッカーの記録先と同じ: セル "i,j"・回数 "i,k"・人数 "k,j"・偏り（場所の表を持たない）。 */
internal enum class KeySpace { CELL, COUNT, NEED, DIST }

/** 違反の族 1 つの定義。重みは `MirrorKeys.weights` だけが持つ（ここに数値を書かない）。 */
internal data class ConstraintSpec(val key: String, val hard: Boolean, val space: KeySpace) {
    val weight: Double get() = MirrorKeys.weights.getValue(key)
}

/**
 * 違反の族の表（全面改修 P5、`docs/overhaul_detailed_spec.md` §3.4）。画面・診断・修復は族ごとの位置の空間をここから引く。
 * 並びは `MirrorKeys.all`（言語跨ぎの添字の順）。T-11（`ConstraintCatalogTest`）が `MirrorKeys` とチェッカーの記録先との一致を守る。
 */
internal object ConstraintCatalog {
    private val NEED = setOf("covU", "covO", "c41", "c41s")
    private val COUNT = setOf("c2", "low", "high", "apt")
    private val DIST = setOf("fair", "weekly")

    val all: List<ConstraintSpec> = MirrorKeys.all.map { k ->
        ConstraintSpec(k, k in MirrorKeys.hard, when (k) { in NEED -> KeySpace.NEED; in COUNT -> KeySpace.COUNT; in DIST -> KeySpace.DIST; else -> KeySpace.CELL })
    }
    private val byKey = all.associateBy { it.key }

    /** 必須の族。 */
    val hardKeys: Set<String> = all.filter { it.hard }.map { it.key }.toSet()
    /** 必須でセルに記録される族（人員不足を除く 5 族）。 */
    val hardCellKeys: Set<String> = all.filter { it.hard && it.space == KeySpace.CELL }.map { it.key }.toSet()

    fun spec(key: String): ConstraintSpec? = byKey[key]
    fun space(key: String): KeySpace? = byKey[key]?.space
}
