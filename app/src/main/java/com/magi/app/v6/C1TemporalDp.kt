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
        /**
         * targetDays[j] == true なら日jを対象シフトにする。
         * [2026-09-02, 外部レビュー#85] 旧実装は `BooleanArray` で、data class の自動生成
         * equals/hashCode は配列を参照同一性で比較する（構造的に同じ内容でも別インスタンスなら
         * 不一致・別ハッシュになる）ため、`Candidate` を `==` で比較したり Set/Map のキーに
         * 使うようになった場合に静かに壊れる潜在的な地雷だった（現時点の呼出し元は全て
         * インデックスアクセスのみで equals/hashCode は未使用と確認済み）。`List<Boolean>` は
         * 標準で構造的等価性を持つため、将来の用途を安全にする予防的な型変更（挙動は不変）。
         */
        val targetDays: List<Boolean>,
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
    /**
     * [3.310.0] 1日ぶんの DP が保持してよい状態数の上限。超えたら解を返さず諦める（`return null`）。
     *
     * DP は密配列でなく `HashMap` の疎表現なので「窓長20なら必ず 2^19 面を確保する」わけではない。
     * ただし到達可能な状態数は「窓内の対象日の 2^n × 追加(<=maxRelocations) の組合せ × count × reloc」で
     * 決まり、**窓長ガードだけでは縛れない**（出現回数の多いシフト × 長い窓で数百万に達しうる）。
     * ここで状態数そのものに上限を置けば、メモリと時間の両方が同時に有界になる。
     * `null` はこの関数の正規の出口（t>63 / 有効ルール無し / 既に違反0 など）で、呼出側の
     * `C1TemporalFlowPolish` は提案が無いものとして扱うだけ＝keep-best は不変・退化しない。
     * 3.305.0 で `C1JointLnsPolish` の密DPへ入れた `MAX_EXACT_LOWER_BOUND_CELLS` と同じ考え方。
     */
    private const val MAX_DP_STATES = 200_000

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
        maxDpStates: Int = MAX_DP_STATES,
    ): Candidate? {
        val t = row.size
        // [3.213.0のSCORE_HARD_UNIT検証と同型] RELOC_BITS(6bit)を超えるmaxRelocationsはkey()のビット
        //   詰め込みでcountフィールドへ溢れ、異なる(relocations,count)状態がDP重複排除で誤って同一視され
        //   状態が黙って潰れうる（現在の全4呼出元は4/6で範囲内=到達不能だが、silent corruption を
        //   明示的な no-op（null返却=このパスがない場合の安全側フォールバック）に変える）。
        if (t == 0 || t > 63 || locked.size != t || maxRelocations <= 0 ||
            maxRelocations > (1 shl RELOC_BITS) - 1) return null
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

        // 状態の表は原始型の開番地法（[StateTable]）。採る状態は (費用, ビット列) の全順序で決まり、同じビット列なら違反数・変更数も同じ
        //   ＝表の走査順に依らない（旧 HashMap と同じ結果）。
        val fireTable = if (maxWindow <= FIRE_TABLE_MAX_WINDOW) fireTableFor(validRules, maxWindow) else null
        var dp = StateTable(16)
        dp.offer(key(0L, 0, 0), 0L, 0L, 0, 0)

        for (day in 0 until t) {
            val next = StateTable(dp.size)
            val oldBit = row[day] == targetShift
            val tieChanged = tie(day, true)
            val bitLo = if (locked[day]) (if (oldBit) 1 else 0) else 0
            val bitHi = if (locked[day]) bitLo else 1
            val steady = fireTable != null && day + 1 >= maxWindow   // 全規則の窓がそろった日＝窓のビット模様だけで違反の増分が決まる
            for (slot in 0 until dp.capacity) {
                if (!dp.used(slot)) continue
                val packed = dp.keyAt(slot)
                val recCost = dp.costAt(slot); val recBits = dp.bitsAt(slot); val recFires = dp.firesAt(slot); val recChanged = dp.changedAt(slot)
                val reloc = (packed and ((1L shl RELOC_BITS) - 1L)).toInt()
                val count = ((packed ushr RELOC_BITS) and ((1L shl COUNT_BITS) - 1L)).toInt()
                val mask = packed ushr LOW_BITS
                for (bit in bitLo..bitHi) {
                    val newCount = count + bit
                    if (newCount > originalCount) continue
                    val added = bit == 1 && !oldBit
                    val newReloc = reloc + if (added) 1 else 0
                    if (newReloc > maxRelocations) continue

                    val full = (mask shl 1) or bit.toLong()
                    var fireInc = 0
                    if (steady) fireInc = fireTable!![full.toInt()].toInt()
                    else for (rule in validRules) {
                        if (day + 1 < rule.days) continue
                        val rm = (1L shl rule.days) - 1L
                        fireInc += maxOf(0, rule.minimum - java.lang.Long.bitCount(full and rm))
                    }
                    val changed = (bit == 1) != oldBit
                    val changedCount = recChanged + if (changed) 1 else 0
                    val fires = recFires + fireInc
                    val cost = recCost + fireInc.toLong() * FIRE_COST +
                        (if (changed) CHANGE_COST + tieChanged else 0L)
                    val newMask = full and keepMask
                    val nk = key(newMask, newCount, newReloc)
                    val bits = if (bit == 1) recBits or (1L shl day) else recBits
                    next.offer(nk, cost, bits, fires, changedCount)
                }
            }
            if (next.size == 0) return null
            // [3.310.0] 状態爆発の安全弁。密DPでない＝窓長だけでは状態数を縛れないため、
            //   実際の到達状態数で打ち切る。諦めても後段は keep-best のまま（退化しない）。
            if (next.size > maxDpStates) return null
            dp = next
        }

        var best: Record? = null
        var bestRelocations = 0
        for (slot in 0 until dp.capacity) {
            if (!dp.used(slot)) continue
            val packed = dp.keyAt(slot)
            val rec = Record(dp.costAt(slot), dp.bitsAt(slot), dp.firesAt(slot), dp.changedAt(slot))
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
        val targetDays = List(t) { day -> ((chosen.bits ushr day) and 1L) != 0L }
        return Candidate(targetDays, chosen.fires, chosen.changed, bestRelocations)
    }

    /**
     * DP の 1 日ぶんの状態表（鍵＝詰めた状態、値＝費用・ビット列・違反数・変更数）。[offer] は同じ鍵なら (費用, ビット列) が小さいほうを残す
     * （旧 `HashMap<Long, Record>` の更新と同じ規則）。鍵の箱詰めと Record の生成を避ける。
     */
    private class StateTable(expected: Int) {
        var capacity = Integer.highestOneBit(maxOf(16, expected) * 2 - 1).coerceAtLeast(16)
            private set
        var size = 0
            private set
        private var keys = LongArray(capacity).also { it.fill(EMPTY) }   // 鍵は 0 以上＝-1 を空きの印にする（別配列を持たない）
        private var cost = LongArray(capacity)
        private var bits = LongArray(capacity)
        private var fires = IntArray(capacity)
        private var changed = IntArray(capacity)

        fun used(slot: Int) = keys[slot] != EMPTY
        fun keyAt(slot: Int) = keys[slot]
        fun costAt(slot: Int) = cost[slot]
        fun bitsAt(slot: Int) = bits[slot]
        fun firesAt(slot: Int) = fires[slot]
        fun changedAt(slot: Int) = changed[slot]

        fun offer(k: Long, c: Long, b: Long, f: Int, ch: Int) {
            if ((size + 1) * 4 > capacity * 3) grow()   // 詰め率 3/4 まで
            var slot = mix(k) and (capacity - 1)
            while (keys[slot] != EMPTY) {
                if (keys[slot] == k) {
                    if (c < cost[slot] || (c == cost[slot] && java.lang.Long.compareUnsigned(b, bits[slot]) < 0)) {
                        cost[slot] = c; bits[slot] = b; fires[slot] = f; changed[slot] = ch
                    }
                    return
                }
                slot = (slot + 1) and (capacity - 1)
            }
            keys[slot] = k; cost[slot] = c; bits[slot] = b; fires[slot] = f; changed[slot] = ch; size++
        }

        private fun grow() {
            val oKeys = keys; val oCost = cost; val oBits = bits; val oFires = fires; val oChanged = changed
            capacity *= 2; size = 0
            keys = LongArray(capacity).also { it.fill(EMPTY) }; cost = LongArray(capacity); bits = LongArray(capacity)
            fires = IntArray(capacity); changed = IntArray(capacity)
            for (x in oKeys.indices) if (oKeys[x] != EMPTY) offer(oKeys[x], oCost[x], oBits[x], oFires[x], oChanged[x])
        }

        private companion object { const val EMPTY = -1L }

        private fun mix(k: Long): Int {
            var z = k * -0x61c8864680b583ebL
            z = z xor (z ushr 29)
            return z.toInt() xor (z ushr 32).toInt()
        }
    }

    /** 窓がこの長さ以下なら、窓のビット模様 → 違反の増分の表を引く（2^16＝64KB まで）。 */
    private const val FIRE_TABLE_MAX_WINDOW = 16
    private val fireTables = java.util.concurrent.ConcurrentHashMap<List<Rule>, ByteArray>()

    /** 全規則の窓がそろった日の違反の増分（`rules` ごとに 1 回作って使い回す。規則の組は職員・シフトをまたいで同じことが多い）。 */
    private fun fireTableFor(rules: List<Rule>, window: Int): ByteArray = fireTables.getOrPut(rules) {
        ByteArray(1 shl window) { m ->
            var inc = 0
            for (rule in rules) inc += maxOf(0, rule.minimum - Integer.bitCount(m and ((1 shl rule.days) - 1)))
            inc.toByte()
        }
    }

    fun countFires(row: IntArray, targetShift: Int, rules: List<Rule>): Int {
        var fires = 0
        for (rule in rules) {
            val d = rule.days
            if (d !in 1..row.size || rule.minimum <= 0) continue
            var count = 0
            for (j in 0 until d) if (row[j] == targetShift) count++
            if (count < rule.minimum) fires += rule.minimum - count
            var start = 1
            while (start <= row.size - d) {
                if (row[start - 1] == targetShift) count--
                if (row[start + d - 1] == targetShift) count++
                if (count < rule.minimum) fires += rule.minimum - count
                start++
            }
        }
        return fires
    }
}
