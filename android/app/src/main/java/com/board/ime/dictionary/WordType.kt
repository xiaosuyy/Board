package com.board.ime.dictionary

/**
 * 词条类型。
 *
 * - [LEARNED]：由 librime 用户词典（userdb）学习得到的记录，只读镜像，用于展示 / 导出 / 清空
 * - [CUSTOM]：用户自己添加的词，会写入 librime 的 custom_phrase，输入时优先出现
 * - [PINNED]：固定词，同样写入 custom_phrase，但不会被「批量导入 / 清空」影响
 * - [BLOCKED]：屏蔽词，不参与上屏（librime 没有屏蔽接口，由 Board 在候选层过滤）
 */
enum class WordType(val label: String) {
    LEARNED("学习词"),
    CUSTOM("自定义词"),
    PINNED("固定词"),
    BLOCKED("屏蔽词"),
    /** 常用语：同样是「编码 → 文本」，单独分类便于同步与展示 */
    PHRASE("常用语"),
    ;

    /** 是否由 Board 主动同步到 librime 的 custom_phrase */
    val syncedToRime: Boolean
        get() = this == CUSTOM || this == PINNED || this == PHRASE

    companion object {
        fun fromName(value: String?): WordType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CUSTOM

        fun fromLabel(label: String?): WordType =
            entries.firstOrNull { it.label == label } ?: fromName(label)
    }
}
