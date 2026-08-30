package com.magi.app.v6

import kotlin.math.max

/**
 * destroy-repair候補選択のmarginal cost計算群。[V6NativeOptimizer] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * 全メンバが**共有可変状態を一切参照しない純粋な計算/判定関数**（[V6NativeOptimizer] 本体は
 * @Volatile フィールド・Atomic系・RunSlotのコルーチンコンテキスト連携で並行実行状態を密結合する
 * 「統括状態機械」の性格が強く、機械的な抽出は危険＝本ファイルはその中の安全な塊だけを切り出す）。
 *
 * - [staffCountPenaltyAt]：個人回数(low/high/apt)のsoft cost。
 * - [weeklyMarginalAt]：7日周期シフト平準化(weekly)のsoft cost。
 * - [fairMarginalAt]：グループ内公平化(fair)のsoft cost。
 * - [destroyRepairStaffReps]：destroyRepairStaffの反復回数（destroyRepairDayと攪乱量を揃える）。
 *
 * 同じ理由で抽出しなかったもの（[V6NativeOptimizer] に残置）: `destroyRepairDay(At)`/
 * `destroyRepairStaff(At)`/`destroyRepairViolations`/`randomAllowedCell`/`perturb`
 * （`cachedProblem`等の統括状態機械側の関数を呼ぶ・盤面を直接変更する副作用を持つ）。
 *
 * 呼び出し側は全て`V6NativeOptimizer.<name>`の完全修飾で参照していたため、抽出時に
 * `DestroyRepairMarginalCost.<name>`へ一括置換した（本体内部からの無修飾自己呼出は元々無い）。
 */
internal object DestroyRepairMarginalCost {
    /** [soft-aware repair] 割当 i→shift k の per-staff soft(low/high/apt, checker と同一式)を count n で評価。 */
    internal fun staffCountPenaltyAt(p: Problem, i: Int, k: Int, n: Int): Long {
        var pen = 0L
        val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
        // [3.319.0] low は**担当できるシフトだけ**。`Evaluator.fullEvalParts` も `MirrorCore` の checker も
        //   元から `p.canDo(i, k)` ガードを持つのに、destroy-repair の marginal cost であるこの関数だけ
        //   欠けていた。担当外シフトに個人下限が設定されたデータ（UI で下限を入れたあと群の担当を外す等で
        //   起こりうる）では、実際には存在しない違反を重み90 で数え、候補選択を無駄な方向へ引っ張る。
        //   最終採否は checker が守るので誤った勤務表は出ないが、有効な候補を取りこぼす。
        //   実データ3件（golden/real/user）では該当セル0＝潜在バグ。high は n>hi の形で担当外なら n=0 に
        //   なり発火せず、かつ Evaluator 側もガードを持たない＝既に一致しているので触らない。
        //   apt は `Problem` 構築時に bucket=canDo でガード済み。
        if (lo != Int.MIN_VALUE && lo != 0 && n < lo && p.canDo(i, k)) pen += (lo - n).toLong() * 90L
        if (hi != Int.MAX_VALUE && n > hi) pen += (n - hi).toLong() * 45L
        val t = p.apt[i][k]
        if (t >= 0) pen += kotlin.math.abs(n - t).toLong()
        return pen
    }


    /** [3.267.0/weekly+fair統合、旧3.170.0「focus露出のみ・cost未対応」の解消] weekly(7日周期のシフト
     *  平準化)の marginal cost。wd は staff のシフト別曜日カウント([K][7]、呼出元が維持)。
     *  [3.345.0] 休を通常のシフト種として扱うため、勤務/休の二値でなく **oldK→newK のシフト移動** を受ける。
     *  動くのは oldK と newK の2バケットだけ（oldK==newK は 0）。weeklyDevOfBucket(checkerと同一式)の
     *  変化のみを計算し、wd 自体は変更しない(コミットは呼出元)。範囲外の値は該当側を寄与ゼロとして扱う。 */
    internal fun weeklyMarginalAt(wd: Array<IntArray>, bucket: Int, oldK: Int, newK: Int): Long {
        if (oldK == newK) return 0L
        var acc = 0L
        if (oldK in wd.indices) {
            val b = wd[oldK]
            val before = weeklyDevOfBucket(b)
            b[bucket]--
            acc += (weeklyDevOfBucket(b) - before).toLong()
            b[bucket]++
        }
        if (newK in wd.indices) {
            val b = wd[newK]
            val before = weeklyDevOfBucket(b)
            b[bucket]++
            acc += (weeklyDevOfBucket(b) - before).toLong()
            b[bucket]--
        }
        return acc
    }


    /** fair(グループ内公平化)の marginal cost。staff i の shift k 保有回数が delta 変化した際の、群
     *  g=p.sgrp[i] のシフト k における L1偏差(checkerと同一式)の変化。m<2(公平化対象外)・k が群の
     *  担当外なら 0（対象外セルは無害にゼロ扱い）。counts/grpTotal は呼出元が維持する S×K・G×K 集計。 */
    internal fun fairMarginalAt(
        p: Problem, i: Int, k: Int, delta: Int, counts: Array<IntArray>, grpTotal: Array<IntArray>,
    ): Long {
        if (delta == 0 || k !in 0 until p.K) return 0L
        val g = p.sgrp[i]
        val mem = p.groupMembers[g]
        val m = mem.size
        if (m < 2 || k !in p.bucket[g]) return 0L
        fun dev(sum: Int): Int {
            val tgt = Math.round(sum.toDouble() / m).toInt()
            var d = 0
            for (x in mem) d += kotlin.math.abs(counts[x][k] - tgt)
            return d
        }
        val before = dev(grpTotal[g][k])
        counts[i][k] += delta
        val after = dev(grpTotal[g][k] + delta)
        counts[i][k] -= delta
        return (after - before).toLong()
    }


    /** [3.240.0] destroyRepairStaff(1回で最大T(日数)セル変化)を、destroyRepairDay(1回で最大S(職員数)
     *  セル変化・covU focusでrepeat(6))と同程度の総攪乱セル数(6*S)に揃えるための反復回数。S>=Tなら
     *  従来のrepeat(8)相当以上(reps>=6の切り上げ計算)を維持し既存の攪乱強度を落とさない。 */
    internal fun destroyRepairStaffReps(s: Int, t: Int): Int = max(1, (6 * s + t - 1) / max(1, t))


}
