package com.magi.app.v6

/**
 * 残り違反に合わせて PORTFOLIO の役割を選ぶ制御（ROGS＝Residual Opportunity-Guided Search の最小版、[EngineConfig.rogsController]）。
 * 役割を替えるワーカーに、「いまの最良盤面に残っている違反の種類（局面）」で過去に 1 秒あたり最も前進した役割を渡す。
 * 前進はワーカー自身の最良の前進（[gainOf]）で、全体最良の更新に限らない。
 * 試していない役割を先に試し、あとは UCB（平均の前進／秒＋試行の少なさの補正）で選ぶ。W0・W4 は従来どおり（呼び出し側）。
 * 表は全ワーカーで共有し、呼び出し側の lock の中で読み書きする。
 */
internal class OpportunityController(private val roles: List<HypothesisEpochRole>) {
    private class Arm { var n = 0; var sec = 0.0; var gain = 0.0 }
    private val table = HashMap<String, HashMap<HypothesisEpochRole, Arm>>()

    /** 局面: 必須が残れば重みが最大の必須の種類、無ければ重みの寄与が最大の要調整の種類。 */
    fun phaseOf(r: ViolationReport): String {
        val keys = if (r.hard > 0) MirrorKeys.hard else r.breakdown.keys.filter { it !in MirrorKeys.hard }
        val top = keys.maxByOrNull { (r.breakdown[it] ?: 0) * MirrorKeys.weightOf(it) }
        return if (top == null || (r.breakdown[top] ?: 0) == 0) (if (r.hard > 0) "hard" else "soft") else top
    }

    /** エポック 1 回の結果を記録する。[before]→[after] はワーカー自身の最良。 */
    fun record(phase: String, role: HypothesisEpochRole, seconds: Double, before: ViolationReport, after: ViolationReport) {
        val a = table.getOrPut(phase) { HashMap() }.getOrPut(role) { Arm() }
        a.n++; a.sec += seconds.coerceAtLeast(0.001)
        a.gain += gainOf(before, after)
    }

    /** 次の役割。[current] は続けて選ばない（同じ役割で停滞したから替える）。 */
    fun choose(phase: String, current: HypothesisEpochRole): HypothesisEpochRole {
        val arms = table[phase] ?: emptyMap()
        val cands = roles.filter { it != current }
        cands.firstOrNull { (arms[it]?.n ?: 0) == 0 }?.let { return it }
        val total = cands.sumOf { arms[it]!!.n }.toDouble()
        val best = cands.maxOf { arms[it]!!.gain / arms[it]!!.sec }.coerceAtLeast(1e-9)
        return cands.maxByOrNull { r ->
            val a = arms[r]!!
            (a.gain / a.sec) / best + EXPLORE * Math.sqrt(Math.log(total + 1.0) / a.n)
        }!!
    }

    /** ログ用: 局面ごとの役割の試行回数と 1 秒あたりの前進。 */
    fun summary(): String = table.entries.sortedBy { it.key }.joinToString(" ; ") { (ph, arms) ->
        ph + ": " + arms.entries.sortedByDescending { it.value.gain / it.value.sec }.joinToString(" ") { (r, a) ->
            "${r.name}=${a.n}回/${"%.0f".format(a.gain / a.sec)}毎秒"
        }
    }

    companion object {
        /** 必須 1 件を重みに直す値（必須の重みの桁＝covU の重みに合わせる）。 */
        val HARD_UNIT: Double = MirrorKeys.weightOf("covU")
        const val EXPLORE = 0.5

        /** 前進の量。重みは必須の重みを含むので、必須が減って要調整が増えた手（重みの差は負になり得る）は必須の減り×[HARD_UNIT] で数える。 */
        fun gainOf(before: ViolationReport, after: ViolationReport): Double =
            maxOf(0.0, before.weightedScore - after.weightedScore, (before.hard - after.hard) * HARD_UNIT)
    }
}
