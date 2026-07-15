package com.magi.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.magi.app.R

/**
 * Android 17 の会話バブル対応。バックグラウンド最適化（[OptimizationWorker]）の進捗と完了を、
 * 他アプリの上に浮かぶ会話バブルとして提示する。
 *
 * バブルは「長寿命の会話ショートカット ＋ MessagingStyle 通知 ＋ BubbleMetadata ＋ 埋め込み可能な
 * 専用Activity（[BubbleActivity]）」の4点で成立する（Bubbles API は Android 11/API30+。本アプリの
 * minSdk 36 では常時利用可）。前景サービス通知（[OptimizationWorker] の NID_PROGRESS）は FGS 要件の
 * ため別に維持し、バブルは会話チャンネルの別通知（[NID_BUBBLE]）として扱う。
 *
 * 表示専用・スコアリング不変（最適化器/チェッカーには一切触れない）。通知許可が無い等で notify が
 * 失敗しても runCatching で握り、最適化本体は継続する。
 */
object BubbleSupport {
    const val CHANNEL_BUBBLE = "magi_optimize_bubble"
    const val SHORTCUT_ID = "magi_optimize_conversation"
    const val NID_BUBBLE = 4103

    /** バブルの会話相手（＝アプリ側の話者）。ショートカット／通知で同一の key を共有する必要がある。 */
    private fun person(ctx: Context): Person =
        Person.Builder()
            .setName("勤務表の最適化")
            .setKey(SHORTCUT_ID)
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setBot(true)
            .build()

    /** 会話チャンネル（バブル許可）。既存の進捗チャンネルとは別に用意する。 */
    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_BUBBLE) == null) {
            val ch = NotificationChannel(
                CHANNEL_BUBBLE, "最適化バブル", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "最適化の進捗・完了を浮遊バブルで表示"
                setAllowBubbles(true) // API 30+（minSdk 36 で常時可）。無いと会話でもバブル化されない。
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /**
     * バブルの前提となる長寿命の会話ショートカットを publish する。これが無いと通知はバブルにならない
     * （Android 11+ ではバブルに紐づくショートカットが必須）。冪等なので毎回呼んで良い。
     */
    fun pushShortcut(ctx: Context) {
        val open = Intent(ctx, BubbleActivity::class.java).setAction(Intent.ACTION_VIEW)
        val info = ShortcutInfoCompat.Builder(ctx, SHORTCUT_ID)
            .setLongLived(true)
            .setShortLabel("最適化")
            .setLongLabel("勤務表の最適化")
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setPerson(person(ctx))
            .setCategories(setOf("com.magi.app.category.OPTIMIZE"))
            .setIntent(open)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, info) }
    }

    private fun bubbleIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, BubbleActivity::class.java).setAction(Intent.ACTION_VIEW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun bubbleMetadata(ctx: Context, autoExpand: Boolean): NotificationCompat.BubbleMetadata =
        NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent(ctx),
            IconCompat.createWithResource(ctx, R.mipmap.ic_launcher),
        )
            .setDesiredHeight(560)
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(false)
            .build()

    private fun post(
        ctx: Context,
        message: String,
        ongoing: Boolean,
        autoExpand: Boolean,
        smallIcon: Int,
    ) {
        ensureChannel(ctx)
        pushShortcut(ctx)
        val me = Person.Builder().setName("あなた").setKey("magi_user").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("勤務表の最適化")
            .addMessage(message, System.currentTimeMillis(), person(ctx))
        val n = NotificationCompat.Builder(ctx, CHANNEL_BUBBLE)
            .setSmallIcon(smallIcon)
            .setStyle(style)
            .setShortcutId(SHORTCUT_ID)
            .addPerson(person(ctx))
            .setContentIntent(bubbleIntent(ctx))
            .setBubbleMetadata(bubbleMetadata(ctx, autoExpand))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true) // 進捗の連続更新で毎回鳴らさない
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NID_BUBBLE, n) }
    }

    /** 進行中の進捗をバブルに反映（更新は onlyAlertOnce で静音・ongoing でスワイプ不可）。 */
    fun postProgress(ctx: Context, message: String) =
        post(ctx, message, ongoing = true, autoExpand = false, smallIcon = android.R.drawable.stat_notify_sync)

    /** 完了サマリをバブルに反映（ongoing 解除・タップで消える）。 */
    fun postDone(ctx: Context, message: String) =
        post(ctx, message, ongoing = false, autoExpand = false, smallIcon = android.R.drawable.stat_sys_download_done)

    fun clear(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NID_BUBBLE) }
    }
}
