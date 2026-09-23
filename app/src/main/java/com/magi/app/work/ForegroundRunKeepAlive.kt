package com.magi.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * [backlog#34] 前景実行（最適化・ソフト研磨）の間だけ WorkManager の前景サービスを保つ（通知を出して待つだけ、計算は ViewModel）。
 * 前景サービスの無いアプリは画面 OFF から約 60 秒で部分 WakeLock を無効化され、アプリ切替でも凍結されうる（実機ログの予算超過の原因）。
 * 独自 Service にしないのは、起動直後に止めると startForeground 前の停止でクラッシュする競合を WorkManager に任せるため。
 */
class ForegroundRunKeepAlive(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // プロセスが落ちた後に WorkManager が再実行したもの（計算はもう無い）は前景化せずに終わる。
        val token = inputData.getLong(KEY_TOKEN, 0L)
        if (token == 0L || token != activeToken) return Result.success()
        // 前景サービスにできなければ理由を残して終わる＝計算は従来どおり続く。
        runCatching { setForeground(getForegroundInfo()) }.onFailure {
            if (it is CancellationException) throw it
            failure = it.javaClass.simpleName
            return Result.success()
        }
        val limitMs = inputData.getLong(KEY_LIMIT_MS, 0L)
        val t0 = SystemClock.elapsedRealtime()
        while (!isStopped && activeToken == token && SystemClock.elapsedRealtime() - t0 < limitMs) delay(1_000L)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (mgr != null && mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "勤務表の最適化", NotificationManager.IMPORTANCE_LOW))
        }
        // ランチャーと同じ Intent＝既存のタスクを前面へ出すだけ（Activity を作り直さない＝計算中の画面を保つ）。
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(inputData.getString(KEY_TITLE) ?: "勤務表をつくっています")
            .setContentText("画面を消しても計算を続けます")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        return ForegroundInfo(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val UNIQUE = "magi_fg_keepalive"
        private const val CHANNEL = "magi_optimize"   // OptimizationWorker と同じチャネル
        private const val NID = 4104   // 4101/4102=OptimizationWorker、4103=BubbleSupport
        private const val KEY_TITLE = "title"
        private const val KEY_LIMIT_MS = "limitMs"
        private const val KEY_TOKEN = "token"
        @Volatile private var activeToken = 0L   // このプロセスで今走っている前景実行（0=なし）
        @Volatile private var failure: String? = null

        /** 前景実行の開始時に呼ぶ（画面が見えている間に投入する）。[limitMs] は解放漏れの保険。 */
        fun start(ctx: Context, title: String, limitMs: Long): Boolean = runCatching {
            val token = System.nanoTime().let { if (it == 0L) 1L else it }
            activeToken = token; failure = null
            val req = OneTimeWorkRequestBuilder<ForegroundRunKeepAlive>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TITLE to title, KEY_LIMIT_MS to limitMs, KEY_TOKEN to token))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }.isSuccess

        fun stop(ctx: Context) {
            activeToken = 0L
            runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE) }
        }

        /** 直近の前景実行で前景サービスにできなかった理由（無ければ null）を取り出す。 */
        fun takeFailure(): String? = failure.also { failure = null }
    }
}
