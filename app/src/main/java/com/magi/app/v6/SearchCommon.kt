package com.magi.app.v6

import com.magi.app.model.MagiState
import com.magi.app.v6.OptimizerRuntime.RunSlot
import com.magi.app.v6.OptimizerRuntime.ownsStatics
import com.magi.app.v6.OptimizerRuntime.publishLiveBest
import com.magi.app.v6.OptimizerRuntime.recordInfeasibleScoped
import com.magi.app.v6.OptimizerRuntime.runSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.PI
import java.util.Random

/** 探索器（ポートフォリオ・ALNS・RSI・探索直後の研磨）と公開入口が共有する小関数（全面改修 P10e）。依存は入口 → 探索器 → ここの一方向。 */
internal object SearchCommon {
    // [3.287.0 keep-best統一] hard→weightedScore→total（単一ソース betterReport へ委譲。MirrorCore.kt 参照）。
    internal fun better(a: ViolationReport, b: ViolationReport): Boolean = betterReport(a, b)
    internal fun nowMs(): Long = System.nanoTime() / 1_000_000L
    internal fun actualSeed(seed: Long): Long = if (seed == 0L) System.nanoTime() else seed
    /** diffBuf の先頭 n セルのうち、cand の値が拡張希望の禁止に当たるものがあるか。 */
    internal fun extBanInDiff(p: Problem, cand: Array<IntArray>, diffBuf: IntArray, n: Int): Boolean {
        for (idx in 0 until n) {
            val flat = diffBuf[idx]; val i = flat / p.T; val j = flat % p.T
            if (p.extBanned(i, j, cand[i][j])) return true
        }
        return false
    }
}
