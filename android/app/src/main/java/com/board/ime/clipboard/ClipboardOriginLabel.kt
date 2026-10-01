package com.board.ime.clipboard

/**
 * 条目的**来源小字**文案（Phase 1-F，**纯逻辑可单测**）。
 *
 * 只做 UI 文案，不参与任何数据/同步判断：
 * - `LOCAL`            → `本地`
 * - `REMOTE`(CLOUD)    → `云端 · <设备名>`（有设备信息时）
 * - `REMOTE`(CLOUD)    → `云端`（没有设备信息时）
 *
 * 设备名来自**已有**数据（Windows 写入的 `device` 字段，Android 侧兼容读 `device/deviceName/from`），
 * **不新增任何协议字段**。
 */
object ClipboardOriginLabel {

    fun of(origin: ClipboardOrigin, originType: String?): String {
        if (origin != ClipboardOrigin.REMOTE) return "本地"
        val device = originType?.takeIf { it.isNotBlank() }
        return if (device != null) "云端 · $device" else "云端"
    }
}
