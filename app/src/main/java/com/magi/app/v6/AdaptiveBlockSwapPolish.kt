package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlin.math.abs
import kotlin.math.min

/**
 * 可変長ブロック丸ごと交換（適応ポートフォリオ）。[V6HotfixPasses] から抽出
 * （責務別の物理分割＝AIコードレビュー時のコンテキスト圧迫対策）。ロジックは一切変更していない。
 *
 * - [adaptiveBlockLengths]：候補ブロック長（11/13/17/19/23/28日の非等間隔ポートフォリオ）。
 *   [applyAdaptiveBlockSwapPolish] だけが参照する定数のため、唯一の消費者と共にここへ移した。
 * - [applyAdaptiveBlockSwapPolish]：15日固定の旧applyBlockSwapPolish（3.300.0で削除済み）を
 *   置き換えた、可変長ブロック×可変長巡回交換（2者〜N者）の演算子。
 *
 * `CyclicSwapResult` は [V6HotfixPasses] に残置される共有返り型のため、ここからは完全修飾で
 * 構築・参照する。`PolishGate`/`TuningTelemetry`/`MirrorKeys`/`C1DeltaPrefilter` はいずれも
 * 別のトップレベル宣言（`V6HotfixPasses`の外側 or 別ファイル）のため完全修飾不要。
 */
internal object AdaptiveBlockSwapPolish {
    /**
     * 長期ブロック交換の候補長。月次勤務表で「局所交換では越えにくい」谷を越えるための
     * 非等間隔ポートフォリオで、短い方から順に 11/13/17/19/23/28 日を試す。
     * 28 は 2月（28日）で「1か月まるごと」の交換を確保するための長さ（素数列ではない点は意図的）。
     * 15 日固定の旧 BlockSwapPolish は後方互換のため残すが、後処理ではこちらを使う。
     */
    private val adaptiveBlockLengths = intArrayOf(11, 13, 17, 19, 23, 28)


    /**
     * 指定した長さの勤務ブロックを、他職員と丸ごと交換／巡回交換する適応ポートフォリオ演算子。
     *
     * 旧 applyBlockSwapPolish（3.300.0 で削除）は「同一担当グループ × 15日 × 2者」に固定されていた。本演算子は
     * 11/13/17/19/23/28日を独立した候補プールとして持ち、各長さから有望候補を必ず残す。
     * これにより、短い窓では途中退化して届かない個人回数・apt・週偏り・連続規則の同時改善を、
     * 期間ごとのまとまりとして探索できる。
     *
     * **可変長の巡回交換（2者交換〜N者巡回, [maxCycle]）**: 2者交換だけでは「A の X を B へ渡したいが、
     * B の持ち札は A に不要」という局面で成立しない。3者以上の巡回（A←B←C←A）ならこの非対称な
     * 譲り合いが閉じる。既存 [applyBlockRotationPolish] も3者回転を持つが**窓が2〜3日固定・
     * 全日movable必須**のため、長期ブロックの巡回は本演算子だけが探索する。
     *
     * 候補生成は全列挙でなく **改善グラフ（cyclic exchange / VLSN）**:
     *  1. ブロック (start, length) ごとに、有向辺 u→v の重み＝「u が v のブロックを受け取ったときの
     *     u 個人の回数ペナルティ改善量」を [personalBalancePenalty] で見積もる。この重みは
     *     **各参加者が「自分の札を出して直前者の札を受け取る」ぶんだけで決まる**ため辺ごとに分解でき、
     *     巡回全体の見積り改善量は辺重みの単純和になる。
     *  2. 最小番号アンカー＋深さ [maxCycle] の DFS で、見積り改善量が正になる巡回を集める。
     *  3. 集めた巡回について**実際の**交換日集合（全参加者が movable かつ各辺の canDo を満たす日）を
     *     取り直し、正式な候補を作る。辺ごとの見積りは3者以上では交換日をやや広く見積もる近似だが、
     *     **順位付け専用**であり採否は必ず正式 checker が決めるため安全側。2者の場合は近似でなく厳密。
     *
     * 安全性:
     * - 同日の値を参加者間で巡回させるだけなので、日ごとのシフト多重集合＝全体の被覆量は保存する。
     * - 異なる担当グループ間でも、その日その辺の受け手が担当可能な場合だけ候補化する。
     * - 実現可能な希望で固定されたセル・担当不可の日は**据え置き**（その日は交換しない）、残りの日だけを
     *   入れ替える。ブロック全体を棄却しないため、希望が多いデータでも長い期間の候補が成立する。
     * - 厳密回数ピンを破る候補は exactPinRegression で除外する。
     * - 最終採否は [UnifiedViolationChecker] と [betterReport] の正式順
     *   （HARD → weightedScore → total）だけで決めるため、探索順にかかわらず退化しない。
     * - 正式評価（フル checker）の回数は [maxEvaluations] で据え置き。巡回を足しても**checker コストは
     *   増えない**（増えるのは安価な候補生成だけ）。候補プールは (ブロック長 × 巡回人数) ごとに分け
     *   ラウンドロビンで評価するため、長い28日案が11日案に、5者案が2者案に押し出されることもない。
     *   **[3.327.0/外部レビュー] [maxEvaluations] は pass ごとの枠**（呼び出し全体では
     *   `maxPasses × maxEvaluations`＝既定 2×48=96 まで走る）。レビューの指摘どおり旧 KDoc は
     *   これを呼び出し予算のように書いていたので**文書側を実装に合わせた**。
     *   「呼び出し1回ぶんの予算」へ変える版も作って実データで測ったが、pass 境界での候補再生成が減り
     *   real の採用手が 2→1 に落ちたので採らない（所要は実測 18〜70ms＝時間を理由に枠を絞る根拠もない）。
     *   **[maxCycleVisits] も全体予算ではない**＝DFS の分岐を (ブロック長, 開始日) ごとに抑える上限。
     *   ここを共通予算にすると後ろのブロックが一切探索されなくなるうえ、実測で DFS は 88万件/77ms＝
     *   時間のボトルネックでないため、そのままにしている（締切は各ブロックの入口で確認する）。
     */
    fun applyAdaptiveBlockSwapPolish(
        state: MagiState,
        schedule: Array<IntArray>,
        blockLens: IntArray = adaptiveBlockLengths,
        maxPasses: Int = 2,
        candidatesPerLength: Int = 8,
        maxEvaluations: Int = 48,
        maxFocusStaff: Int = 16,
        maxCycle: Int = 5,
        maxCycleVisits: Int = 50_000,
        /**
         * 禁止連続(c3n)が正味増える候補を**候補生成の段階で**捨てるか。
         * 既定は [PolishGate.filterC3nIncrease]（設定タブ→詳細設定のトグル・既定 false＝捨てない）。
         * c3n は HARD なので増える候補は最終的に `isBetter` が必ず却下する＝true/false で**採用結果は
         * 変わらない**。true にすると詰んだ候補へフル checker を呼ばなくなり評価枠を節約できる。
         */
        filterC3nIncrease: Boolean = PolishGate.filterC3nIncrease,
        shouldStop: () -> Boolean = { false },
    ): V6HotfixPasses.CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        val lengths = blockLens.asSequence()
            .filter { it in 1..p.T }
            .distinct()
            .sorted()
            .toList()
        val cycleCap = maxCycle.coerceAtLeast(2)
        if (p.S < 2 || lengths.isEmpty() || maxPasses <= 0 || candidatesPerLength <= 0 || maxEvaluations <= 0 || maxFocusStaff <= 0) {
            return V6HotfixPasses.CyclicSwapResult(work, before.total, before.total, 0,
                listOf(MirrorLog(tag = "AdaptiveBlockSwap", message = "対象長または職員ペアなし=スキップ")))
        }

        /**
         * 巡回交換の1候補。[cycle] は巡回順で、`cycle[t]` は `cycle[(t+1) % n]` のシフトを受け取る
         * （n=2 なら通常の2者交換と同一）。[days] は据え置き分を除いた実際の交換日。
         */
        data class Candidate(
            val cycle: IntArray,
            val start: Int,
            val length: Int,
            val priority: Long,
            val differences: Int,
            val days: IntArray,
        )

        fun name(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        /**
         * 巡回を1つ回す。[forward] が真なら `cycle[t] <- cycle[t+1]`、偽なら逆回し。
         * 逆回しは順回しの厳密な逆変換なので、評価のための「適用→検査→巻き戻し」に使える
         * （2者交換は順逆が同一＝従来の swap と一致）。
         */
        fun rotate(candidate: Candidate, forward: Boolean) {
            val cycle = candidate.cycle
            val n = cycle.size
            val vals = IntArray(n)
            for (j in candidate.days) {
                for (t in 0 until n) vals[t] = work[cycle[t]][j]
                for (t in 0 until n) {
                    val src = if (forward) (t + 1) % n else (t + n - 1) % n
                    work[cycle[t]][j] = vals[src]
                }
            }
        }

        // range/apt は長い交換で動かしたい主対象。ここは候補の順位付け専用であり、採否は必ず正式checkerに委譲する。
        fun personalBalancePenalty(staff: Int, shift: Int, count: Int): Long {
            var out = 0L
            val lo = p.rangeLo[staff][shift]
            val hi = p.rangeHi[staff][shift]
            if (lo != Int.MIN_VALUE && count < lo) out += (lo - count).toLong() * 90L
            if (hi != Int.MAX_VALUE && count > hi) out += (count - hi).toLong() * 45L
            val apt = p.apt[staff][shift]
            if (apt >= 0) out += abs(count - apt).toLong()
            return out
        }

        fun staffPressure(report: ViolationReport): LongArray {
            val out = LongArray(p.S)
            fun add(key: String, cls: String) {
                val i = key.substringBefore(',').toIntOrNull() ?: return
                if (i !in 0 until p.S) return
                val family = cls.removePrefix("vio-")
                out[i] += MirrorKeys.weightOf(family).toLong().coerceAtLeast(1L)
            }
            report.violations.forEach { (key, cls) -> add(key, cls) }
            report.countViolations.forEach { (key, cls) -> add(key, cls) }
            report.distLocations.forEach { (family, rows) ->
                val weight = MirrorKeys.weightOf(family).toLong().coerceAtLeast(1L)
                for (row in rows) {
                    val i = row.firstOrNull() ?: continue
                    if (i in 0 until p.S) out[i] += weight
                }
            }
            return out
        }

        /**
         * [ピン保存交換] 交換日 [swapDays] を「厳密ピン(lo==hi)のシフト回数が1つも動かない」部分集合へ絞る。
         * 絞れた場合だけ true（[swapDays] を破壊的に更新）。
         *
         * 対象は**いま満たされている**厳密ピンだけ（`counts == lo == hi`）。すでに外れているピンは
         * 動かして直せる余地があるため拘束しない（悪化は従来どおり `exactPinRegression` が弾く）。
         *
         * 各日 j について、参加者 t のピン付きシフト k の増減
         * `d = [直前者が k] - [自分が k]`（∈ {-1,0,+1}）を並べた**符号ベクトル**を作る。
         * 交換日集合の総和がゼロベクトルならピンは1つも動かない。ゼロベクトルの日は常に採り、
         * 非ゼロの日は**符号が正反対の日と対にして**採る（打ち消し合う）。3日以上での相殺は拾わないが
         * 安価で安全側（採れなかった日を落とすだけ＝退化しない）。
         */
        fun balancePinnedDays(cycle: IntArray, swapDays: ArrayList<Int>, counts: Array<IntArray>): Boolean {
            val n = cycle.size
            // いま満たされている厳密ピン (参加者位置, シフト) を集める。
            var slots = 0
            val slotStaff = IntArray(32)
            val slotShift = IntArray(32)
            for (t in 0 until n) {
                val i = cycle[t]
                for (k in 0 until p.K) {
                    val lo = p.rangeLo[i][k]
                    if (lo == Int.MIN_VALUE || lo != p.rangeHi[i][k] || counts[i][k] != lo) continue
                    if (slots >= 31) return true   // 対象が多すぎる＝この安価な相殺では扱えない（従来どおり）
                    slotStaff[slots] = t; slotShift[slots] = k; slots++
                }
            }
            if (slots == 0) return true   // ピン無し＝制約なし（コストゼロで従来と同一）

            fun signatureOf(j: Int): Long {
                var sig = 0L
                for (s in 0 until slots) {
                    val t = slotStaff[s]
                    val k = slotShift[s]
                    val mine = if (work[cycle[t]][j] == k) 1 else 0
                    val incoming = if (work[cycle[(t + 1) % n]][j] == k) 1 else 0
                    val d = incoming - mine
                    if (d > 0) sig = sig or (1L shl (2 * s))
                    else if (d < 0) sig = sig or (2L shl (2 * s))
                }
                return sig
            }
            // 符号の反転（+1↔-1 のビットを入れ替える）。
            fun negate(sig: Long): Long = ((sig and 0x5555_5555_5555_5555L) shl 1) or ((sig ushr 1) and 0x5555_5555_5555_5555L)

            val bySig = LinkedHashMap<Long, ArrayList<Int>>()
            for (j in swapDays) bySig.getOrPut(signatureOf(j)) { ArrayList() }.add(j)

            val kept = ArrayList<Int>(swapDays.size)
            bySig[0L]?.let { kept.addAll(it) }
            val done = HashSet<Long>()
            done.add(0L)
            for ((sig, days) in bySig) {
                if (sig in done) continue
                done.add(sig)
                val opposite = negate(sig)
                done.add(opposite)
                val other = bySig[opposite] ?: continue
                val pairs = min(days.size, other.size)
                for (t in 0 until pairs) { kept.add(days[t]); kept.add(other[t]) }
            }
            if (kept.size < 2) return false
            kept.sort()
            swapDays.clear()
            swapDays.addAll(kept)
            return true
        }

        /**
         * 巡回 [cycle] をブロック (start, length) に適用する正式な候補を作る。
         * 交換日は「全参加者が movable」「その日の受け渡しが全辺 canDo」「実際に値が動く」を満たす日だけ。
         */
        fun candidateFor(
            cycle: IntArray,
            start: Int,
            length: Int,
            counts: Array<IntArray>,
            pressure: LongArray,
        ): Candidate? {
            val n = cycle.size
            val vals = IntArray(n)
            val swapDays = ArrayList<Int>(length)
            for (j in start until start + length) {
                var ok = true
                for (t in 0 until n) {
                    val i = cycle[t]
                    // [3.291.0 候補生成の緩和] 希望固定・担当不可の日はブロックごと棄却せず据え置く。
                    if (!movable(i, j)) { ok = false; break }
                    val v = work[i][j]
                    if (v !in 0 until p.K) { ok = false; break }
                    vals[t] = v
                }
                if (!ok) continue
                var changes = false
                for (t in 0 until n) {
                    val incoming = vals[(t + 1) % n]
                    if (incoming != vals[t]) changes = true
                    if (!p.canDo(cycle[t], incoming)) { ok = false; break }
                }
                if (!ok || !changes) continue
                swapDays.add(j)
            }
            if (swapDays.size < 2) return null
            // [3.294.0 ピン保存交換] 交換日の集合を「厳密ピン(lo==hi)の回数が変わらない」ように選び直す。
            //   3.293.0 の不採用内訳で、採用0の55〜80%が exactPinRegression のピン破りと判明した
            //   （実データは10名中9名の「休」が厳密ピン＝長いブロックを丸ごと交換すると必ず回数が動く）。
            if (!balancePinnedDays(cycle, swapDays, counts)) return null

            // [3.295.0 境界c3nの事前フィルタ / 3.296.0 で既定OFF] 3.294.0 でピン破りを消した結果、
            //   残る不採用は**全て**必須増＝c3n（禁止連続）になった（user 48/48・golden 39・real 34）。
            //   この巡回交換では covU/covO は同日置換で不変・groupViol は canDo・pref は movable で
            //   不変なので、**変化しうる HARD は c3n だけ**。c3n は職員行ローカルなので、参加者の行に
            //   交換を当てた fire 数を数えれば**近似でなく厳密**に判定できる。
            //
            //   **既定は OFF**（ユーザー指示 3.296.0「巡回交換の c3n フィルタを外す」）。フィルタは
            //   `firesAfter > firesBefore` の候補だけを落とす＝**減る・同数の候補は元から通している**ため、
            //   外しても採用は増えない（c3n は HARD なので増える候補は `isBetter` が第1キーで必ず却下）。
            //   ON にすると構造的に詰んだ候補へ checker を呼ばなくなり、評価枠を soft 判定まで進める
            //   候補へ回せる（実測: 正式評価 48→14〜38 件・不採用が全て soft のトレードオフになる）。
            if (filterC3nIncrease && p.cons3n.isNotEmpty()) {
                var firesBefore = 0
                var firesAfter = 0
                for (t in 0 until n) {
                    val self = cycle[t]
                    val giver = cycle[(t + 1) % n]
                    val row = work[self].copyOf()
                    firesBefore += C1DeltaPrefilter.staffC3nFires(p, row)
                    for (j in swapDays) row[j] = work[giver][j]
                    firesAfter += C1DeltaPrefilter.staffC3nFires(p, row)
                }
                if (firesAfter > firesBefore) { TuningTelemetry.c3nFilterSkipped.incrementAndGet(); return null }
            }

            val differences = swapDays.size
            // 1日だけの交換は既存の同日交換/同日3者回転(CyclicSwap)と同一＝「期間をまとめて入れ替える」手にならないので除外。
            if (differences < 2) return null

            var beforeBalance = 0L
            var afterBalance = 0L
            var pressureSum = 0L
            val delta = IntArray(p.K)
            for (t in 0 until n) {
                val self = cycle[t]
                val giver = cycle[(t + 1) % n]
                java.util.Arrays.fill(delta, 0)
                for (j in swapDays) { delta[work[self][j]]--; delta[work[giver][j]]++ }
                for (k in 0 until p.K) {
                    if (delta[k] == 0) continue
                    beforeBalance += personalBalancePenalty(self, k, counts[self][k])
                    afterBalance += personalBalancePenalty(self, k, counts[self][k] + delta[k])
                }
                pressureSum += pressure[self]
            }
            // 大きい推定改善を優先しつつ、違反に関与する職員と実際に変わるセル数をタイブレークに使う。
            val priority = (beforeBalance - afterBalance) * 1_000_000L + pressureSum * 16L + differences.toLong()
            return Candidate(cycle.copyOf(), start, length, priority, differences, swapDays.toIntArray())
        }

        var bestRep = before
        var applied = 0
        var generated = 0            // DFS が列挙した巡回の数（見積りキーだけで選別する安価な段）
        var builtCandidates = 0      // 実候補まで組み立てた数（プール上位のみ）
        val builtByCycleSize = sortedMapOf<Int, Int>()   // 巡回人数別の実候補数（多者交換が実際に出ているかの診断）
        val rejectReasons = LinkedHashMap<String, Int>() // 不採用の理由別件数（採用0のとき何に負けたか）
        val rejectCulprits = LinkedHashMap<String, Int>()// 悪化の主因になった族（重み付きで最も増えた族）
        var evaluated = 0
        var cycleHits = 0            // 3者以上の巡回として採用した手数（2者交換と区別してログに出す）
        val selectedLabels = ArrayList<String>()
        var pass = 0
        while (pass < maxPasses && !shouldStop()) {
            val counts = countMatrix(p, work)
            val pressure = staffPressure(bestRep)
            // 参加者の母集団は違反関与度の高い順に絞る（DFS の分岐を p.S でなく maxFocusStaff で抑える）。
            val focus = (0 until p.S)
                .sortedWith(compareByDescending<Int> { pressure[it] }.thenBy { it })
                .take(min(maxFocusStaff, p.S))
                .sorted()
            if (focus.size < 2) break
            val nf = focus.size

            // 候補は (ブロック長 × 巡回人数) ごとの固定サイズプールへ。どちらの軸でも押し出されない。
            // **2段階生成**: ①DFS は巡回を「見積りキーだけ」で選別する（実候補を作らない＝O(1)/巡回で
            //   数十万件を捌ける） ②各プールに残った上位だけ [candidateFor] で実候補にする。
            //   見積りキー = 個人回数の改善見積り×1e6 ＋ 参加者の違反関与度×16（実 priority と同順序）。
            //   **見積り0の巡回も捨てない**: ブロック交換の本命は c1/連続規則/曜日偏りの同時改善で、
            //   個人回数が動かない（見積り0の）手が実際に採用されることがあるため（3.291.0 実測）。
            //   段①のプール幅は段②より広く取る（[stageOneWidth]）。見積りキーの上位が実候補化で
            //   落ちる（交換成立日が1日以下）ことは巡回人数が増えるほど起きやすく、幅が狭いと
            //   その下にある成立候補まで一緒に失うため。実候補化は高々 バケット数×幅 回で安価。
            val bucketW = (cycleCap - 1).coerceAtLeast(1)
            val bucketCount = lengths.size * bucketW
            val stageOneWidth = candidatesPerLength * 8
            val poolKeys = LongArray(bucketCount * stageOneWidth)
            val poolNodes = LongArray(bucketCount * stageOneWidth)
            val poolStart = IntArray(bucketCount * stageOneWidth)
            val poolSize = IntArray(bucketCount)
            val poolMinKey = LongArray(bucketCount)
            fun record(bucket: Int, key: Long, nodes: Long, start: Int) {
                generated++
                val base = bucket * stageOneWidth
                val size = poolSize[bucket]
                if (size < stageOneWidth) {
                    poolKeys[base + size] = key; poolNodes[base + size] = nodes; poolStart[base + size] = start
                    poolSize[bucket] = size + 1
                    if (size == 0 || key < poolMinKey[bucket]) poolMinKey[bucket] = key
                    return
                }
                // 満杯後の却下は O(1)（最小キーを保持）。採用時のみ O(幅) で最小を取り直す。
                if (key <= poolMinKey[bucket]) return
                var worst = 0
                for (t in 1 until stageOneWidth) if (poolKeys[base + t] < poolKeys[base + worst]) worst = t
                poolKeys[base + worst] = key; poolNodes[base + worst] = nodes; poolStart[base + worst] = start
                var mn = poolKeys[base]
                for (t in 1 until stageOneWidth) if (poolKeys[base + t] < mn) mn = poolKeys[base + t]
                poolMinKey[bucket] = mn
            }

            val edge = Array(nf) { LongArray(nf) }        // 改善グラフ: focus 添字 u→v の見積り改善量
            val edgeOk = Array(nf) { BooleanArray(nf) }   // その辺で実際に動く日が1日でもあるか
            val delta = IntArray(p.K)
            val used = BooleanArray(nf)
            // 巡回は focus 添字を8bitずつ詰めた Long で持ち回す（最大5者=40bit）。
            val packable = nf <= 255

            for ((li, length) in lengths.withIndex()) {
                if (shouldStop() || !packable) break
                for (start in 0..(p.T - length)) {
                    if (shouldStop()) break
                    // 1) 辺重み（u が v のブロックを受け取ったときの u 個人の改善見積り）を作る。
                    for (ui in 0 until nf) {
                        val u = focus[ui]
                        for (vi in 0 until nf) {
                            edge[ui][vi] = 0L; edgeOk[ui][vi] = false
                            if (ui == vi) continue
                            val v = focus[vi]
                            java.util.Arrays.fill(delta, 0)
                            var any = false
                            for (j in start until start + length) {
                                if (!movable(u, j) || !movable(v, j)) continue
                                val a = work[u][j]
                                val b = work[v][j]
                                if (a == b || a !in 0 until p.K || b !in 0 until p.K) continue
                                if (!p.canDo(u, b)) continue
                                delta[a]--; delta[b]++; any = true
                            }
                            if (!any) continue
                            var gain = 0L
                            for (k in 0 until p.K) {
                                if (delta[k] == 0) continue
                                gain += personalBalancePenalty(u, k, counts[u][k]) -
                                    personalBalancePenalty(u, k, counts[u][k] + delta[k])
                            }
                            edge[ui][vi] = gain; edgeOk[ui][vi] = true
                        }
                    }

                    // 2) 最小番号アンカーの DFS で巡回を列挙し、見積りキーで各プールへ入れる。
                    var visits = 0
                    fun dfs(anchor: Int, depth: Int, last: Int, sum: Long, pres: Long, nodes: Long) {
                        if (depth >= 2 && edgeOk[last][anchor]) {
                            val key = (sum + edge[last][anchor]) * 1_000_000L + pres * 16L
                            record(li * bucketW + (depth - 2), key, nodes, start)
                        }
                        if (depth >= cycleCap) return
                        for (ni in anchor + 1 until nf) {
                            if (used[ni] || !edgeOk[last][ni]) continue
                            if (++visits > maxCycleVisits) return
                            used[ni] = true
                            dfs(anchor, depth + 1, ni, sum + edge[last][ni], pres + pressure[focus[ni]],
                                nodes or (ni.toLong() shl (8 * depth)))
                            used[ni] = false
                            if (visits > maxCycleVisits) return
                        }
                    }
                    for (ai in 0 until nf) {
                        if (visits > maxCycleVisits) break
                        used[ai] = true
                        dfs(ai, 1, ai, 0L, pressure[focus[ai]], ai.toLong())
                        used[ai] = false
                    }
                }
            }

            // 3) 各プールの上位だけを実候補にし、(ブロック長 × 巡回人数) からラウンドロビンで取り出す。
            val ordered = ArrayList<List<Candidate>>(bucketCount)
            for (b in 0 until bucketCount) {
                val size = poolSize[b]
                if (size == 0) continue
                val n = (b % bucketW) + 2
                val length = lengths[b / bucketW]
                val built = ArrayList<Candidate>(size)
                for (t in 0 until size) {
                    val packed = poolNodes[b * stageOneWidth + t]
                    val cyc = IntArray(n) { focus[((packed ushr (8 * it)) and 0xFFL).toInt()] }
                    candidateFor(cyc, poolStart[b * stageOneWidth + t], length, counts, pressure)?.let { built.add(it) }
                }
                if (built.isEmpty()) continue
                builtCandidates += built.size
                builtByCycleSize[n] = (builtByCycleSize[n] ?: 0) + built.size
                ordered.add(built.sortedWith(
                    compareByDescending<Candidate> { it.priority }
                        .thenByDescending { it.differences }
                        .thenBy { it.start }
                        .thenBy { it.cycle[0] },
                ).take(candidatesPerLength))
            }
            if (ordered.isEmpty()) break
            val ranked = ArrayList<Candidate>()
            var rank = 0
            while (ordered.any { rank < it.size }) {
                for (pool in ordered) pool.getOrNull(rank)?.let { ranked.add(it) }
                rank++
            }
            if (ranked.isEmpty()) break

            val base = work.copy2D()
            var chosen: Candidate? = null
            var chosenRep: ViolationReport? = null
            var checkedThisPass = 0
            for (candidate in ranked) {
                if (shouldStop() || checkedThisPass >= maxEvaluations) break
                rotate(candidate, forward = true)
                val report = UnifiedViolationChecker.check(state, work)
                val pinRegression = exactPinRegression(p, base, work)
                // [3.326.0] ピンだけが止めた候補を対象別に記録する。**盤面を戻す前に**呼ぶ
                //   （record は after 盤面を読むため、rotate で復元したあとでは間に合わない）。
                if (pinRegression && betterReport(report, bestRep)) pinBlocks.record(p, base, work)
                rotate(candidate, forward = false)
                checkedThisPass++
                evaluated++
                if (!pinRegression && betterReport(report, bestRep) && (chosenRep == null || betterReport(report, chosenRep!!))) {
                    chosen = candidate
                    chosenRep = report
                } else {
                    // [不採用理由の分類] 採用0のとき「何に負けたか」がログから読めるようにする
                    //   （RangePolish 3.222.0・C1Polish 3.236.0 の頭打ち理由と同じ趣旨）。
                    //   分類は isBetter の判定順（HARD → weightedScore → total）と厳密に一致させる。
                    val why = when {
                        pinRegression -> "ピン破り"
                        report.hard > bestRep.hard -> "必須増"
                        report.hard < bestRep.hard -> "採用手に劣後"   // bestRep には勝つが同パスの別候補に負けた
                        report.weightedScore > bestRep.weightedScore -> "重み悪化"
                        report.weightedScore < bestRep.weightedScore -> "採用手に劣後"
                        report.total < bestRep.total -> "採用手に劣後"
                        report.total > bestRep.total -> "件数悪化"
                        else -> "同値"
                    }
                    rejectReasons[why] = (rejectReasons[why] ?: 0) + 1
                    if (why == "重み悪化" || why == "必須増") {
                        // 重み付きで最も増えた族＝この手が壊した本体（共通ヘルパー worstWorsenedFamily）。
                        worstWorsenedFamily(report, bestRep)?.let { rejectCulprits[it] = (rejectCulprits[it] ?: 0) + 1 }
                    }
                }
            }
            val accepted = chosen ?: break
            rotate(accepted, forward = true)
            bestRep = chosenRep ?: break
            applied++
            if (accepted.cycle.size >= 3) cycleHits++
            val who = accepted.cycle.joinToString("←") { name(it) } + "←" + name(accepted.cycle[0])
            selectedLabels.add("${accepted.length}日${accepted.cycle.size}者:$who ${accepted.start + 1}〜${accepted.start + accepted.length}日(${accepted.differences}セル)")
            pass++
        }

        val lensLabel = lengths.joinToString("/")
        val logs = listOf(MirrorLog(tag = "AdaptiveBlockSwap",
            message = "可変長ブロック巡回交換[${lensLabel}日・最大${cycleCap}者]: total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard}" +
                " score ${before.weightedScore.toLong()}->${bestRep.weightedScore.toLong()} 採用${applied}回(うち3者以上${cycleHits}回)" +
                " 巡回${generated}件(実候補${builtCandidates}件" +
                (if (builtByCycleSize.isEmpty()) "" else " 内訳 " + builtByCycleSize.entries.joinToString(" ") { "${it.key}者:${it.value}" }) +
                ")/正式評価${evaluated}件" +
                (if (applied == 0) " [頭打ち=改善手なし]" else "") +
                (if (rejectReasons.isEmpty()) "" else " 不採用内訳: " +
                    rejectReasons.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}${it.value}" }) +
                (if (rejectCulprits.isEmpty()) "" else " (悪化の主因 " +
                    rejectCulprits.entries.sortedByDescending { it.value }.take(4).joinToString(" ") { "${it.key}:${it.value}" } + ")") +
                (if (selectedLabels.isNotEmpty()) " 対象: ${selectedLabels.joinToString(", ")}" else "")))
        return V6HotfixPasses.CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


}
