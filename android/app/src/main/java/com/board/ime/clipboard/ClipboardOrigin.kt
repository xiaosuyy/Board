package com.board.ime.clipboard

/**
 * 剪贴板条目的来源。
 *
 * 关键点：判断标准是**「这条内容是从哪台设备产生的」**，不是「有没有同步成功」。
 * - [LOCAL]：本机（Board 这台设备）产生的。本机复制后上传到云端，仍然算 [LOCAL]
 * - [REMOTE]：通过同步从**其他设备**拿到的
 */
enum class ClipboardOrigin {
    LOCAL,
    REMOTE,
    ;

    companion object {
        fun fromName(value: String?): ClipboardOrigin =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}
