package com.board.ime.voice

/**
 * 把百度实时 ASR 的回调文本组装成「会话全文」。
 *
 * 官方协议语义（《实时语音识别-websocket API》）：
 * - `MID_TEXT`：**一句话的临时识别结果** → 它是**这一句的累计文本**，
 *   所以必须**覆盖**（不能 append）；
 * - `FIN_TEXT`：**一句话的最终识别结果**（带该句 start_time/end_time）
 *   → 表示**这一句**结束，把这一句**追加一次**到已确定文本；
 *   一个会话会有**多个** FIN_TEXT，且服务端**不会**因为一句结束而断开连接；
 * - 因此「服务器认为这句说完了」≠「用户结束语音」。
 *
 * 去重优先用协议自带的信息（`sn` 尾部的 `_ws_N` 段号 / `start_time`），
 * 只有协议没给足够信息时才退回到轻量的前缀判断（服务端若返回"整会话全文"则覆盖）。
 *
 * 纯逻辑、不碰 Android，便于单测。
 */
class VoiceTranscriptAssembler {

    /** 已确定的语音段（按顺序累加，每段只加一次） */
    private val settled = StringBuilder()

    /** 当前正在识别的这一句（会被新的 partial 覆盖） */
    private var pendingSegment: String = ""

    /** 最近一次已确定段的标识（段号），用于忽略同一段的重复 final */
    private var lastSegmentKey: String? = null

    /** 面板要显示的全部文本：已确定 + 当前这句 */
    val displayText: String
        get() = settled.toString() + pendingSegment

    /** 已结束的所有语音段（用户停止后要提交的就是它） */
    val settledText: String
        get() = settled.toString()

    /** 当前是否有任何文本（部分或已确定） */
    val hasText: Boolean
        get() = settled.isNotEmpty() || pendingSegment.isNotEmpty()

    fun onPartial(text: String) {
        // 覆盖，绝不 append
        pendingSegment = text
    }

    fun onSegmentFinal(text: String, segmentKey: String?) {
        if (text.isEmpty()) {
            pendingSegment = ""
            return
        }
        if (segmentKey != null && segmentKey == lastSegmentKey) {
            // 同一段重复上报：忽略，避免重复
            pendingSegment = ""
            return
        }
        val settledSoFar = settled.toString()
        if (settledSoFar.isNotEmpty() && text.startsWith(settledSoFar)) {
            // 服务端这一条给的是「整段会话全文」：用最新全文覆盖，而不是追加
            settled.setLength(0)
            settled.append(text)
        } else {
            // 正常的「本段全文」：追加一次
            settled.append(text)
        }
        if (segmentKey != null) lastSegmentKey = segmentKey
        pendingSegment = ""
    }

    fun reset() {
        settled.setLength(0)
        pendingSegment = ""
        lastSegmentKey = null
    }

    companion object {
        /**
         * 从服务端 `sn` / `start_time` 里取段标识。
         *
         * 官方示例的 sn 形如 `399427ce-e999-11e9-94c8-fa163e4e6064_ws_2`，
         * 同一句话的 MID_TEXT 与 FIN_TEXT 共用同一个 `_ws_2` → 可直接当段号用。
         */
        fun segmentKeyOf(sn: String?, startTime: Long?): String? {
            val suffix = sn?.substringAfterLast("_ws_", "")?.trim()
            if (!suffix.isNullOrEmpty()) return "ws_$suffix"
            // 没有段号时退回用该句起始时间（官方 FIN_TEXT 必带 start_time）
            return startTime?.takeIf { it > 0 }?.let { "t_$it" }
        }
    }
}
