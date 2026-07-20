package com.magi.app.v6

/**
 * C1（D日間に対象シフトをN回以上）の月内配置を、対象シフトの月間回数を変えずに
 * 時系列DPで再設計する。
 *
 * 既存の1回swap（2-opt）では、2箇所以上を同時に動かさない限り途中解が改善しない
 * 局所最適を越えられない。本DPは対象シフトか否かの二値列を月全体で最適化し、
 * 最大[maxRelocations]回の「非対象→対象」と同数の「対象→非対象」を一括生成する。
 *
 * - 全C1規則のうち同じ対象シフトの規則を同時評価する。
 * - 希望固定日は現在の対象/非対象状態を固定する。
 * - 対象シフトの月間回数を厳密保存するため、staffRange/apt/c2の対象回数は不変。
 * - このクラスは二値配置だけを解く。退避した非対象シフトtokenの再割当と全制約採否は
 *   V6HotfixPasses側が担当する。
 *
 * 月31日・最大窓15日・最大4移設の実データ規模では、状態数は通常数万以下。
 */
internal object C1TemporalDp {
    data class Rule(val days: Int, val minimum: Int)

    data class Candidate(
        /** targetDays[j] == true なら日jを対象シフトにする。 */
        val targetDays: BooleanArray,
        /** この対象シフトに関する全C1規則の違反窓総数。 */
        val fires: Int,
        /** 変更セル数。回数保存なので常に relocations * 2。 */
        val changedCells: Int,
        /** 非対象→対象へ移した日数。 */
        val relocations: Int,
    )

    private data class Record(
        val cost: Long,
        val bits: Long,
        val fires: Int,
        val changed: Int,
    )

    private const val FIRE_COST = 1_000_000L
    private const val CHANGE_COST = 1_000L
    private const val COUNT_BITS = 6
    private const val RELOC_BITS = 6
    private const val LOW_BITS = COUNT_BITS + RELOC_BITS

    /**
     * @param row 現在の1職員分シフト列。
     * @param targetShift C1対象シフトindex。
     * @param rules targetShiftに対するC1規則群。
     * @param locked trueの日は対象/非対象の現在状態を変えない。
     * @param maxRelocations 一度に移設する最大対象シフト数。
     * @param seed 同じC1違反数・変更数の別配置を得る決定的tie-break seed。
     * @param maxExactWindow ビットマスクDPで厳密に扱う最大窓長。
     */
    fun solve(
        row: IntArray,
        targetShift: Int,
        rules: List<Rule>,
        locked: BooleanArray,
        maxRelocations: Int = 4,
        seed: Long = 0L,
        maxExactWindow: Int = 20,
    ): Candidate? {
        val t = row.size
        if (t == 0 || t > 63 || locked.size != t || maxRelocations <= 0) return null
        val validRules = rules.filter { it.days in 1..t && it.minimum > 0 }
        if (validRules.isEmpty()) return null
        val maxWindow = validRules.maxOf { it.days }
        if (maxWindow > maxExactWindow || maxWindow >= 63) return null

        val originalCount = row.count { it == targetShift }
        if (originalCount == 0 || originalCount == t) return null
        val currentFires = countFires(row, targetShift, validRules)
        if (currentFires == 0) return null

        val keepBits = (maxWindow - 1).coerceAtLeast(0)
        val keepMask = when (keepBits) {
            0 -> 0L
            else -> (1L shl keepBits) - 1L
        }

        fun key(mask: Long, count: Int, relocations: Int): Long =
            (mask shl LOW_BITS) or (count.toLong() shl RELOC_BITS) or relocations.toLong()

        fun tie(day: Int, changed: Boolean): Long {
            if (!changed) return 0L
            var z = seed xor (day.toLong() * -0x61c8864680b583ebL)
            z = z xor (z ushr 33)
            z *= -0x00ae502812aa7333L
            z = z xor (z ushr 29)
            return z and 511L
        }

        var dp = HashMap<Long, Record>()
        dp[key(0L, 0, 0)] = Record(0L, 0L, 0, 0)

        for (day in 0 until t) {
            val next = HashMap<Long, Record>(maxOf(16, dp.size * 2))
            val oldBit = row[day] == targetShift
            for ((packed, rec) in dp) {
                val reloc = (packed and ((1L shl RELOC_BITS) - 1L)).toInt()
                val count = ((packed ushr RELOC_BITS) and ((1L shl COUNT_BITS) - 1L)).toInt()
                val mask = packed ushr LOW_BITS
                val choices = if (locked[day]) {
                    if (oldBit) intArrayOf(1) else intArrayOf(0)
                } else {
                    intArrayOf(0, 1)
                }
                for (bit in choices) {
                    val newCount = count + bit
                    if (newCount > originalCount) continue
                    val added = bit == 1 && !oldBit
                    val newReloc = reloc + if (added) 1 else 0
                    if (newReloc > maxRelocations) continue

                    val full = (mask shl 1) or bit.toLong()
                    var fireInc = 0
                    for (rule in validRules) {
                        if (day + 1 < rule.days) continue
                        val rm = (1L shl rule.days) - 1L
                        if (java.lang.Long.bitCount(full and rm) < rule.minimum) fireInc++
                    }
                    val changed = (bit == 1) != oldBit
                    val changedCount = rec.changed + if (changed) 1 else 0
                    val fires = rec.fires + fireInc
                    val cost = rec.cost + fireInc.toLong() * FIRE_COST +
                        (if (changed) CHANGE_COST else 0L) + tie(day, changed)
                    val newMask = full and keepMask
                    val nk = key(newMask, newCount, newReloc)
                    val bits = if (bit == 1) rec.bits or (1L shl day) else rec.bits
                    val old = next[nk]
                    if (old == null || cost < old.cost ||
                        (cost == old.cost && java.lang.Long.compareUnsigned(bits, old.bits) < 0)
                    ) {
                        next[nk] = Record(cost, bits, fires, changedCount)
                    }
                }
            }
            if (next.isEmpty()) return null
            dp = next
        }

        var best: Record? = null
        var bestRelocations = 0
        for ((packed, rec) in dp) {
            val reloc = (packed and ((1L shl RELOC_BITS) - 1L)).toInt()
            val count = ((packed ushr RELOC_BITS) and ((1L shl COUNT_BITS) - 1L)).toInt()
            if (count != originalCount || reloc <= 0 || rec.fires >= currentFires) continue
            val b = best
            if (b == null || rec.cost < b.cost ||
                (rec.cost == b.cost && java.lang.Long.compareUnsigned(rec.bits, b.bits) < 0)
            ) {
                best = rec
                bestRelocations = reloc
            }
        }
        val chosen = best ?: return null
        val targetDays = BooleanArray(t) { day -> ((chosen.bits ushr day) and 1L) != 0L }
        return Candidate(targetDays, chosen.fires, chosen.changed, bestRelocations)
    }

    fun countFires(row: IntArray, targetShift: Int, rules: List<Rule>): Int {
        var fires = 0
        for (rule in rules) {
            val d = rule.days
            if (d !in 1..row.size || rule.minimum <= 0) continue
            var count = 0
            for (j in 0 until d) if (row[j] == targetShift) count++
            if (count < rule.minimum) fires++
            var start = 1
            while (start <= row.size - d) {
                if (row[start - 1] == targetShift) count--
                if (row[start + d - 1] == targetShift) count++
                if (count < rule.minimum) fires++
                start++
            }
        }
        return fires
    }
}
