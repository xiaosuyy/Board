package com.board.ime.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器。
 *
 * 只在**未捕获异常**时被调用，把堆栈写到 `filesDir/crash.log`，
 * 这样即使没有连电脑，也能在「设置 → 关于 → 日志详情」里看到真实堆栈。
 *
 * 不替换系统默认处理（仍然照常崩、照常上报），只是额外留一份现场。
 */
object CrashRecorder {

    private const val TAG = "BoardCrash"
    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_LINES = 80

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun persist(context: Context, thread: Thread, throwable: Throwable) {
        val text = buildString {
            append(timeFormat.format(Date()))
            append("  thread=").append(thread.name)
            append('\n')
            append(Log.getStackTraceString(throwable))
            append('\n')
        }
        Log.e(TAG, text)
        val file = file(context)
        runCatching {
            // 只留最近一段，避免无限增长
            val existing = if (file.exists()) file.readText() else ""
            val merged = (text + existing).take(MAX_BYTES)
            file.writeText(merged)
        }
    }

    /** 最近几条（最新的在前） */
    fun recent(context: Context): List<String> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readText().lineSequence().take(MAX_LINES).toList()
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun hasCrash(context: Context): Boolean = runCatching { file(context).exists() }.getOrDefault(false)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
