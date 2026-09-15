package com.magi.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [3.552.0] 起動診断。実機の logcat が得られない運用のため、起動段階マーカーと未捕捉例外を
 * filesDir に残し、次回起動で「前回 UI 未到達」または「例外記録あり」なら Compose を起動する前に
 * 素の View で表示する（Compose 初期化自体の失敗に備え、診断画面は Compose を使わない）。
 * MAGI-Godot の `MagiStartupGuard` からの移植（PCK 複製・レンダラー切替は不要）。
 */
object MagiStartupGuard {
    const val STAGE_ACTIVITY = "activity"
    const val STAGE_COMPOSITION = "composition"
    const val STAGE_UI = "ui"

    private const val STAGE_FILE = "magi_startup_stage.txt"
    private const val CRASH_FILE = "magi_crash.txt"
    private var installed = false

    class Previous(val lastStage: String?, val crash: String?) {
        val needsDiagnostic: Boolean get() = crash != null || (lastStage != null && lastStage != STAGE_UI)
    }

    fun readPrevious(ctx: Context): Previous {
        val stage = runCatching { File(ctx.filesDir, STAGE_FILE).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        val crash = runCatching { File(ctx.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()
        return Previous(stage?.ifBlank { null }, crash?.ifBlank { null })
    }

    fun markStage(ctx: Context, stage: String) {
        runCatching { File(ctx.filesDir, STAGE_FILE).writeText(stage) }
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, STAGE_FILE).delete() }
        runCatching { File(ctx.filesDir, CRASH_FILE).delete() }
    }

    /** 未捕捉例外を CRASH_FILE に残してから既定ハンドラへ渡す（プロセス終了の挙動は変えない）。 */
    fun installExceptionHandler(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val stage = File(app.filesDir, STAGE_FILE).takeIf { it.exists() }?.readText()?.trim().orEmpty()
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(app.filesDir, CRASH_FILE).writeText(
                    "$ts thread=${thread.name} stage=$stage\n${Log.getStackTraceString(e)}"
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun diagnosticText(ctx: Context, prev: Previous): String {
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val version = pkg?.let { "${it.versionName} (${it.longVersionCode})" } ?: "?"
        return buildString {
            appendLine("MAGI 起動診断")
            appendLine()
            appendLine("版: $version")
            appendLine("端末: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("前回の到達段階: ${prev.lastStage ?: "記録なし"}" + if (prev.lastStage != STAGE_UI) "（画面まで到達していません）" else "")
            appendLine()
            if (prev.crash != null) {
                appendLine("前回の例外:")
                appendLine(prev.crash.trim())
            } else {
                appendLine("例外の記録はありません。")
            }
            appendLine()
            appendLine("この画面を撮影して報告してください。「そのまま起動」で記録を消して通常どおり起動します。")
        }
    }
}
