package com.magi.app.v6

/**
 * 最適化の実行基盤（全面改修 P10a）: 実行ごとの成果物入れ（[RunSlot]）、いちばん新しい実行のライブ表示（最良盤面・他の案・エリート・
 * 充足困難な族）、計測の始まり。探索の方式（ポートフォリオ・ALNS・RSI）から使う横断の状態で、探索の意味論は持たない。
 */
object OptimizerRuntime {
    // ───────── [3.335.0/外部レビュー P1] 実行ごとの成果物入れ ─────────
    //   `lastAlternatives` などの可変 static は「直近の実行の値」しか持てず、実行が重なると
    //   （WorkManager の REPLACE で旧 Worker が協調キャンセルを待つ間・kill 後の再スケジュール）
    //   入口の初期化が相手の値を消し、書き込みも読み出しも混ざり得た。実行ごとに [RunSlot] を作り、
    //   コルーチンのコンテキストで呼び出し木の隅々まで運ぶ（suspend 関数なので引数を増やさずに届く）。
    //   static は「いちばん新しい実行のライブ表示」用として残す（新しい方が勝つのが正しい面）。
    internal class RunSlot(val id: Long) {
        @Volatile var alternatives: List<Array<IntArray>> = emptyList()
        @Volatile var fusionElites: List<AdaptiveElite> = emptyList()
        private val lock = Any()
        @Volatile var infeasible: Set<String> = emptySet()
            private set
        fun addInfeasible(fams: Collection<String>) {
            if (fams.isEmpty()) return
            synchronized(lock) { infeasible = infeasible + fams }
        }
    }
    internal class RunSlotElement(val slot: RunSlot) :
        kotlin.coroutines.AbstractCoroutineContextElement(RunSlotElement) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<RunSlotElement>
    }
    private val runSeq = java.util.concurrent.atomic.AtomicLong(0)
    /** いちばん新しい `optimize()` の実行 id。static への書き込みはこれと一致するときだけ行う。 */
    @Volatile private var newestRunId = 0L
    internal suspend fun runSlot(): RunSlot? =
        kotlin.coroutines.coroutineContext[RunSlotElement]?.slot
    /** static（＝新しい実行が勝つライブ表示側）へ書いてよいか。スロット無し＝直接呼び出しは従来どおり許す。 */
    internal fun ownsStatics(slot: RunSlot?): Boolean = slot == null || slot.id == newestRunId

    /** 直近の並列探索で得た「他の案」（採用案以外の候補スケジュール、品質順・最大3件）。
     *  [3.335.0] **これは「いちばん新しい実行」の値**。呼び出し側は `V6OptimizerResult.alternatives`
     *  （実行ごとの値）を読むこと。ここは表示・互換のために残している。 */
    @Volatile var lastAlternatives: List<Array<IntArray>> = emptyList()
        private set

    // [3.288.0/ログ強化=状態軸] この optimize() 実行中に HF63 が「構造的に充足困難」と学習した族の集合
    //   （全 runRsi 呼出＝直接RSI/RSI++/適応ポートフォリオの各ワーカーからの union）。エピローグの
    //   「残存分析」行が読む。optimize() 入口でクリアする。並行ワーカーからの union 更新のため synchronized。
    @Volatile var lastInfeasibleFamilies: Set<String> = emptySet()
        private set
    private val infeasLock = Any()
    internal fun recordInfeasible(fams: Collection<String>) {
        if (fams.isEmpty()) return
        synchronized(infeasLock) { lastInfeasibleFamilies = lastInfeasibleFamilies + fams }
    }
    /** [3.335.0] この実行のスロットへ記録し、いちばん新しい実行のときだけ static も更新する。 */
    internal suspend fun recordInfeasibleScoped(fams: Collection<String>) {
        val slot = runSlot()
        slot?.addInfeasible(fams)
        if (ownsStatics(slot)) recordInfeasible(fams)
    }
    internal fun clearInfeasible() { synchronized(infeasLock) { lastInfeasibleFamilies = emptySet() } }


    /** [3.268.0/elite archive fusion] 全epochから圧縮した品質・距離・橋渡しエリート
     *  （最適化後の再結合/Fusion専用、PORTFOLIO実行時のみ非空）。 */
    @Volatile internal var lastFusionElites: List<AdaptiveElite> = emptyList()
        private set

    /** [DefragLiveView移植] 実行中の最良盤面スナップショット（計算中ライブ表示用・読取専用）。
     *  進捗の節目で更新。
     *  [敵対的レビュー修正] 旧実装は単純 last-writer-wins だった（コメント上は「並列時はどのワーカーの
     *  最良でも有効な解」としていたが、実際には各仮説/チェーンが**自分のローカル最良**を無条件に書くため、
     *  劣った仮説が後から書き込むと途中結果(kill復旧用)の品質が非単調に退行し得た）。[liveBestReport]で
     *  CAS管理する真のグローバル最良のときだけ更新する（[publishLiveBest]経由。better()と同一基準）。 */
    val liveBest: List<List<Int>>? get() = liveBestRef.get()?.board

    /**
     * [3.385.0] 評価と盤面を**1つの不変オブジェクト**にして1回の CAS で publish する。
     *
     * 旧実装は「report を CAS → 成功したら liveBest へ代入」の2段だった。CAS は report を単調にするが、
     * **盤面の代入は CAS の外**なので次の順で割り込める:
     *   A: CAS(null → 必須3) 成功 → 盤面コピー(O(職員×日))の途中でプリエンプト
     *   B: CAS(必須3 → 必須1) 成功 → 盤面B を書く
     *   A: 再開して**盤面A（劣る方）で上書き**
     * 結果 `liveBestReport` は必須1 なのに `liveBest` は必須3 の盤面＝**両者が食い違い、
     * 途中最良が退行する**（docstring が「退行を防ぐ」と謳っていた不変条件そのものが破れる）。
     * 8並列ワーカーが同時に publish し、間に O(310) のコピーが挟まるので稀ではない。
     *
     * 実害は誤った勤務表ではない（採用は必ず checker の keep-best が決める）。破れるのは
     * ①kill 復旧用スナップショット(`magi_bg_best.json`)が最良より劣る盤面になり進捗を捨てる
     * ②ライブ表示の数字と盤面が食い違う、の2点。
     */
    internal class LiveBestSnapshot(val report: ViolationReport, val board: List<List<Int>>)

    private val liveBestRef = java.util.concurrent.atomic.AtomicReference<LiveBestSnapshot?>(null)

    /** 報告（評価）と盤面を 1 回の読みで取る。壁判定はこの 1 組で行う（別々に読むと対応がずれる）。 */
    internal val liveBestSnapshot: LiveBestSnapshot? get() = liveBestRef.get()

    /** [敵対的レビュー修正] liveBest を真にグローバルな最良のときだけ更新する。呼出元のローカル
     *  best/report が既存の liveBest より劣る/同値なら何もしない＝退行を防ぐ。 */
    internal fun publishLiveBest(report: ViolationReport, schedule: Array<IntArray>) {
        // 盤面コピーは「勝ち目がある」と分かってから1回だけ。負ける呼出（多数）はコピーを払わない。
        var snap: LiveBestSnapshot? = null
        while (true) {
            val cur = liveBestRef.get()
            if (cur != null && !betterReport(report, cur.report)) return
            if (snap == null) snap = LiveBestSnapshot(report, schedule.map { it.toList() })
            if (liveBestRef.compareAndSet(cur, snap)) return
            // [3.387.0] ここへ来る＝**別スレッドが同時に publish していた**。3.385.0 で直した競合が
            //   実機で本当に起きるのかは単体テストでは分からない（合成の競合しか作れない）ので、
            //   ここだけが唯一の実測点。0 が続くなら「理論上の窓」、非ゼロなら「実際に起きている」。
            liveBestContention.incrementAndGet()
        }
    }

    /** [3.387.0] `publishLiveBest` の CAS が競合で再試行した回数（この実行ぶん・診断表示のみ）。 */
    private val liveBestContention = java.util.concurrent.atomic.AtomicInteger(0)

    internal fun liveBestContentionCount(): Int = liveBestContention.get()

    /** テスト専用（`optimize()` を回さずに publish の不変条件だけを検査するため）。 */
    internal fun resetLiveBestForTest() { liveBestRef.set(null); liveBestContention.set(0) }

    /**
     * [3.388.0/外部レビュー] **利用者の1回の「つくる」ぶん**の計測をゼロから始める。
     *
     * 旧実装は [optimize] の入口で `TuningTelemetry.reset()` と競合カウンタを落としていたが、
     * `handleOptimize` は AUTO の 31〜210秒帯で **optimize() を最大3回**呼ぶ（RSI → ALNS →
     * ExtraRefine）。よって後続の呼出が先行ぶんを上書きし、診断に出るのは**最後の pass だけ**だった
     * ＝主計算(8ワーカー)の計測が捨てられ、`設定の効き` も競合数も 0 に見える。
     * 「0 なら理論上の窓に留まっている」と読ませる行がまさに false negative になる
     * （3.102.1 の `lastAlternatives` と同じ罠）。入口で1回だけ落とす形へ移す。
     */
    fun beginTelemetry() {
        TuningTelemetry.reset()
        liveBestContention.set(0)
    }

    /** 1 回の `optimize()` の始まり: 新しい実行のスロットを作り、いちばん新しい実行として静的な表示を空にする。 */
    internal fun beginRun(): RunSlot {
        val slot = RunSlot(runSeq.incrementAndGet())
        newestRunId = slot.id
        lastAlternatives = emptyList()
        lastFusionElites = emptyList()
        clearInfeasible()   // [3.288.0/状態軸] この実行の HF63 学習をゼロから集約する
        liveBestRef.set(null)
        return slot
    }

    /** [slot] をコルーチンのコンテキストに載せて [block] を走らせる（呼び出し木の隅々まで届く）。 */
    internal suspend fun <T> inSlot(slot: RunSlot, block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = kotlinx.coroutines.withContext(RunSlotElement(slot), block)

    /** いちばん新しい実行の「他の案」とエリートを静的な表示へ書く（呼び出し側が [ownsStatics] で確かめてから）。 */
    internal fun publishRunOutputs(alternatives: List<Array<IntArray>>, elites: List<AdaptiveElite>? = null) {
        lastAlternatives = alternatives
        if (elites != null) lastFusionElites = elites
    }
}
