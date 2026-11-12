package com.magi.app.v6

/** [#50、docs/weightless_objective.md §7.2] 採点の方式。WEIGHTS＝今の重み（既定）、PRIORITY＝段の並びを近似する重み（試験）。 */
enum class ObjectiveMethod { WEIGHTS, PRIORITY }

/**
 * 採点に使う重み（評価器・差分評価・チェッカーの weightedScore・修復の見積り・C++）。方式で切り替わる。
 * 違反の色・重なりの優先（表示）は方式に関わらず [MirrorKeys.weights] のまま（2026-11-06 ユーザー決定「色は今のまま」）。
 *
 * PRIORITY の重み（2026-11-06 ユーザー確定、HF77）: 必須（T0）は今のまま、T1（low・c1・high・c3mn）＝200、
 * T2（c3・c3m・c41・c42・c41s・c42s）＝20、T3（apt・c2・covO・weekly）＝1、fair＝20（3.727.75）。根拠は §7.5 の 2 腕比較。
 */
object ObjectiveWeights {
    @Volatile var method: ObjectiveMethod = ObjectiveMethod.WEIGHTS

    private val priority: Map<String, Double> = LinkedHashMap(MirrorKeys.weights).also { m ->
        for (f in TierObjective.tiers[1]) m[f] = 200.0
        for (f in TierObjective.tiers[2]) m[f] = 20.0
        for (f in TierObjective.tiers[3]) m[f] = 1.0
        // fair（グループ内の公平化）は 20（2026-11-12 ユーザー明示指示「20」、HF77）。段の鍵の段（T3）は変えない＝重みだけ。
        m["fair"] = 20.0
    }

    fun table(m: ObjectiveMethod = method): Map<String, Double> = if (m == ObjectiveMethod.PRIORITY) priority else MirrorKeys.weights

    /** 公平化／適切回数の 6% 許容（[PolishGate.aptFairSoftTolerance]）を使える方式か。PRIORITY は T3 の apt/fair のために上の段を
     *  悪くする手を採らない＝許容と両立しないので使わない（2026-11-09 ユーザー指示「優先順位のときは自動で OFF」）。 */
    fun allowsAptFairTolerance(m: ObjectiveMethod = method): Boolean = m != ObjectiveMethod.PRIORITY

    /** 族 1 つの採点の重み（[MirrorKeys.weightOf] の採点版。aptLow/aptHigh は apt）。研磨・修復の見積りが読む。 */
    fun of(family: String): Double = when (family) {
        "aptLow", "aptHigh" -> table()["apt"] ?: 0.0
        else -> table()[family] ?: 0.0
    }

    /** SOFT の族ごとの重み（Long）。評価器と差分評価がホットパスで読む。 */
    class Soft(private val t: Map<String, Double>) {
        private fun w(f: String): Long = t.getValue(f).also { check(it == Math.floor(it)) { "$f の重み $it は整数でない" } }.toLong()
        @JvmField val c1 = w("c1"); @JvmField val c2 = w("c2"); @JvmField val c3 = w("c3"); @JvmField val c3m = w("c3m"); @JvmField val c3mn = w("c3mn")
        @JvmField val c41 = w("c41"); @JvmField val c42 = w("c42"); @JvmField val c41s = w("c41s"); @JvmField val c42s = w("c42s")
        @JvmField val covO = w("covO"); @JvmField val low = w("low"); @JvmField val high = w("high")
        @JvmField val apt = w("apt"); @JvmField val fair = w("fair"); @JvmField val weekly = w("weekly")
        @JvmField val covU = w("covU")
        /** C++ へ渡す並び（`magi_native.cpp` の `SoftW` と同じ順）。 */
        fun toLongArray(): LongArray = longArrayOf(c1, c2, c3, c3m, c3mn, c41, c42, c41s, c42s, covO, low, high, apt, fair, weekly, covU)
    }

    private val softWeights = Soft(MirrorKeys.weights)
    private val softPriority = Soft(priority)

    fun soft(m: ObjectiveMethod = method): Soft = if (m == ObjectiveMethod.PRIORITY) softPriority else softWeights
}
