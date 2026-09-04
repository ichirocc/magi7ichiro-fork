package com.magi.app.v6

/**
 * [3.490.0] エンジン内の締切・経過・停滞判定に使う**単調時計**（ms）。
 *
 * 旧: `EngineClock.nowMs()`（壁時計）を締切に使っていた。壁時計は NTP 補正・手動の時刻変更・
 * スリープ復帰で前後する＝戻れば予算を大幅に超えて回り続け、進めば即時に期限切れ、フェーズ別の所要時間が
 * 負になる。`System.nanoTime()` は単調なので、エンジン内で扱う「時刻(ms)」はすべてここから取る
 * （呼出側・テストが渡す `deadlineMs` も同じ時計で作ること＝壁時計の値を混ぜると比較が成り立たない）。
 * ログに出す実時刻（`MirrorLog.ts`・`startedAt`）だけは壁時計のまま。
 */
object EngineClock {
    @JvmStatic fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
