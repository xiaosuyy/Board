package com.board.ime.sync

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 同步事件类型 —— 固定的一组标记，方便过滤日志 */
enum class SyncEvent(val tag: String) {
    UPLOAD("SYNC_UPLOAD"),
    DOWNLOAD("SYNC_DOWNLOAD"),
    SKIP_SAME_HASH("SYNC_SKIP_SAME_HASH"),
    CONFLICT("SYNC_CONFLICT"),
    AUTH_ERROR("SYNC_AUTH_ERROR"),
    NETWORK_ERROR("SYNC_NETWORK_ERROR"),
    SERVER_ERROR("SYNC_SERVER_ERROR"),
    NOT_CONFIGURED("SYNC_NOT_CONFIGURED"),
    IGNORED_TYPE("SYNC_IGNORED_TYPE"),
    SUPPRESS_LOOP("SYNC_SUPPRESS_LOOP"),
    RETRY("SYNC_RETRY"),
    DONE("SYNC_DONE"),
}

/**
 * 同步日志。
 *
 * 只记录**元信息**：类型、哈希前 8 位、字节数、HTTP 码、错误分类。
 * **绝不记录剪贴板正文**（正文可能包含验证码 / 私聊内容）。
 */
object SyncLog {

    private const val TAG = "BoardSync"
    private const val MAX_LINES = 40

    private val buffer = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun event(event: SyncEvent, detail: String) {
        val line = "${formatter.format(Date())} ${event.tag} $detail"
        Log.i(TAG, line)
        synchronized(buffer) {
            buffer.addFirst(line)
            while (buffer.size > MAX_LINES) buffer.removeLast()
        }
    }

    fun warn(event: SyncEvent, detail: String) {
        val line = "${formatter.format(Date())} ${event.tag} $detail"
        Log.w(TAG, line)
        synchronized(buffer) {
            buffer.addFirst(line)
            while (buffer.size > MAX_LINES) buffer.removeLast()
        }
    }

    /** 最近若干条（最新的在前），设置页直接显示 */
    fun recent(): List<String> = synchronized(buffer) { buffer.toList() }

    /** 给日志用的短哈希，不泄露正文 */
    fun shortHash(hash: String): String = if (hash.length <= 8) hash else hash.take(8)
}
