package com.board.ime.sync

/**
 * 参与同步的设备平台。
 *
 * 阶段一只会写入 [ANDROID]；其余取值先作为数据模型保留，
 * 避免后续加 Windows 端时再改数据库结构。
 */
enum class DevicePlatform {
    ANDROID,
    DESKTOP,
    WEB,
    UNKNOWN,
}

/** 可以同步的实体类型 */
enum class SyncEntityType {
    /** 剪贴板（当前剪贴板 = SyncClipboard.json） */
    CLIPBOARD,

    /** 词库条目（自定义词 / 固定词 / 屏蔽词） */
    DICTIONARY,

    /** 学习词（librime 用户词典的合并结果） */
    LEARNED_WORD,

    /** 常用语 */
    PHRASE,

    /** 主题（外观设置） */
    THEME,

    /** 键盘设置 */
    KEYBOARD_SETTINGS,

    /** 保留：设备表本身不同步 */
    DEVICE,

    /** 保留 */
    SETTINGS,
}

/** 同步操作种类 */
enum class SyncOperationAction {
    CREATE,
    UPDATE,
    DELETE,
}

/** 单条同步操作的处理状态 */
enum class SyncOperationStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

/** 同步通道的整体状态，阶段一始终为 [IDLE] */
enum class SyncState {
    IDLE,
    SYNCING,
    ERROR,
}
