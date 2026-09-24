package com.magi.app.work

import com.magi.app.model.MagiState
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.ViolationReport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * In-process bridge between the UI/ViewModel and the background [OptimizationWorker]
 * (改善仕様書 §6.3). The MagiState is far larger than WorkManager's 10KB inputData limit,
 * so the input is handed over here by reference and progress/result are published as flows.
 *
 * While the process is alive (incl. backgrounded), this carries everything. If the process is
 * killed mid-run the in-memory [request] is lost, but the Worker persists its input/best-snapshot/
 * result to files and recovers from them (see OptimizationWorker.loadInputFromFile / inputFile /
 * snapshotFile / resultFile), so the run completes and the result is reflected on relaunch.
 */
object OptimizationRepository {
    /**
     * 進捗を UI へ押し出す最短間隔（ミリ秒）。前景（`MagiViewModel.runV6FullOptimize`）と
     * 背景（`OptimizationWorker`）の**両方**がこの窓を使う。
     *
     * [3.394.0/3.393.0 の測り直し] エンジンの進捗コールバックは実測で
     * **PORTFOLIO・並列8 なら 1,174.7回/秒**（golden_state・45秒）。この値をそのまま UiState の
     * 差し替えに流すと `ui` を読む Compose の木がその頻度で作り直される＝実機報告の「ちらつき」。
     * 窓で間引くと **4.3回/秒**（同条件の実測）。
     *
     * **3.393.0 で 35.3回/秒・1059→53回と書いたのは測り方が不十分だった**：30秒予算・並列4 は
     * AUTO が V5 を選ぶ帯で、既定の長時間経路（211秒以上＝PORTFOLIO）を代表していなかった。
     */
    const val PROGRESS_PUSH_MS = 200L

    data class BgProgress(
        val phase: String,
        val hard: Int,
        val soft: Int,
        val total: Int,
        val iters: Long,
        val elapsedMs: Long,
    )

    data class BgResult(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val phase: String,
        /**
         * [3.410.0/U-01] これを計算した実行の ID（0=識別子を持たない旧経路／プロセス再起動後の復元）。
         * 旧: `BgResult` は run の識別子を持たず、ViewModel は入力の指紋(`bgStateKey`)だけで受容を
         * 決めていた。指紋は**入力が同じなら別の実行でも一致する**ので、置き換えられた古い実行が
         * 完了間際に publish した結果を「いま走らせている実行の答え」として受け取れた。
         * ファイル側の所有権（`RunFiles.owns`）はファイルを守るだけで、この**メモリ経由の公開**は
         * 素通りする＝ここに識別子を載せて塞ぐ。
         */
        val runId: Long = 0L,
        /**
         * [3.475.0/論理監査] これを計算した**入力の指紋**（`StateFingerprint.of`、0=不明）。旧: プロセス再起動後は
         * ViewModel 側の `bgStateKey`/`bgRunId` が 0 に戻り、識別子も指紋も照合されずに**いま開いている
         * 別のデータへ**結果が当たり得た（Worker の入力と現在の state の次元が違えば不整合な盤面を保存）。
         * Worker が入力から計算して載せ、ViewModel は常にこれと現在の state を照合する。
         */
        val stateKey: Long = 0L,
        /** [3.592.0] 前景の`captureAlternatives`と同じ候補群。背景実行はこれまで捨てており「他の案」が出なかった。 */
        val alternatives: List<Array<IntArray>> = emptyList(),
    )

    /**
     * [外部レビュー N6] 背景実行の計算条件＝前景（`runV6FullOptimize`）が `handleOptimize` へ渡すのと同じ4つ。
     * ViewModel が WorkManager の inputData へ載せ（kill 後の再実行でも残る）、Worker はそこから読む。
     */
    data class RunConfig(val seconds: Int, val workers: Int, val softPolish: Boolean, val algorithm: V6Algorithm) {
        fun toInput(): Map<String, Any> = mapOf(
            KEY_SECONDS to seconds, KEY_WORKERS to workers, KEY_SOFT_POLISH to softPolish, KEY_ALGORITHM to algorithm.name,
        )

        companion object {
            const val KEY_SECONDS = "seconds"
            const val KEY_WORKERS = "workers"
            const val KEY_SOFT_POLISH = "softPolish"
            const val KEY_ALGORITHM = "algorithm"

            /** 鍵の無い入力（この版より前に投入された Work）は従来の条件で読む＝予算・並列は Repository、仕上げ OFF・AUTO。 */
            fun fromInput(input: Map<String, Any?>): RunConfig = RunConfig(
                seconds = (input[KEY_SECONDS] as? Int)?.takeIf { it > 0 } ?: OptimizationRepository.seconds,
                workers = (input[KEY_WORKERS] as? Int)?.takeIf { it > 0 } ?: OptimizationRepository.workers,
                softPolish = input[KEY_SOFT_POLISH] as? Boolean ?: false,
                algorithm = V6Algorithm.entries.firstOrNull { it.name == input[KEY_ALGORITHM] } ?: V6Algorithm.AUTO,
            )
        }
    }

    /** Input handed to the next worker run. */
    @Volatile var request: Pair<MagiState, Array<IntArray>>? = null
    @Volatile var seconds: Int = 60
    @Volatile var workers: Int = 4

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _progress = MutableStateFlow<BgProgress?>(null)
    val progress: StateFlow<BgProgress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<BgResult?>(null)
    val result: StateFlow<BgResult?> = _result.asStateFlow()

    /**
     * [3.385.0/外部レビュー High3] Worker が **黙って落とした耐久保証の失敗**を、書き出せる操作ログへ届ける
     * ための唯一の経路。
     *
     * `OptimizationWorker` は入力・結果・途中最良の書き込みを全て `runCatching { }` で包んでおり、
     * 失敗しても痕跡が一切残らなかった。これは kill 耐性そのものを担う書き込みなので、失敗すると
     * 「5分回した実行がプロセス終了で丸ごと消えたのに、書き出したログには理由が1行も無い」状態になる
     * （3.381.0/3.382.0 で潰した「サイレント死」と同じクラス）。Worker は Android の Log しか持たず
     * このアプリの診断は全て書き出しログに集まるので、Repository を経由して `logOp` へ流す。
     *
     * `replay` を持たせるのは、Worker がプロセス再起動直後（ViewModel が購読する前）に走り得るため。
     */
    private val _notes = MutableSharedFlow<Pair<String, String>>(
        replay = 8,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notes: SharedFlow<Pair<String, String>> = _notes.asSharedFlow()

    /**
     * 失敗の記録は本処理を止めてはいけないので、ブロックしない `tryEmit` を使う。
     *
     * [3.388.0/外部レビュー] `level` を持たせる。3.387.0 で Worker のライフサイクル全体を流すように
     * したのに消費側が `logOp("W", it)` 固定のままで、**正常に完了した背景実行まで警告として記録**して
     * いた。このリポジトリの診断は「まず [W] を拾う」読み方が定着している（SanityCheck・CoverageDiag・
     * 設定ミス・NativeBridge がすべて W）ので、正常系を混ぜるとその読み方が壊れる。
     */
    fun publishNote(level: String, msg: String) { _notes.tryEmit(level to msg) }

    fun setRunning(v: Boolean) { _running.value = v }
    fun publishProgress(p: BgProgress) { _progress.value = p }
    fun publishResult(r: BgResult?) { _result.value = r }
    fun clear() { _progress.value = null; _result.value = null }

    /** 受け取らずに捨てた結果を下ろす。そのあとに新しい結果が公開されていたら触らない。 */
    fun dropResult(r: BgResult) { _result.compareAndSet(r, null) }

    /**
     * [外部レビュー R6] 結果の購読を [ready]（起動時の復元が state を立て終えた）の後に始める。
     * StateFlow は購読の開始時に最新値を渡すが、渡した値を再送しない＝復元前に受けて捨てた結果は二度と来ない。
     */
    suspend fun collectResultsAfter(ready: StateFlow<Boolean>, onResult: suspend (BgResult) -> Unit) {
        ready.first { it }
        result.collect { r -> if (r != null) onResult(r) }
    }
}
