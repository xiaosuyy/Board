package com.board.ime.settings

/**
 * 中文输入引擎选择。
 *
 * - [RIME]：真正的中文输入（librime + 朙月拼音方案）
 * - [BASIC]：阶段一的占位引擎，只做英文联想；保留它方便对比测试
 */
enum class EngineKind {
    RIME,
    BASIC,
    ;

    companion object {
        fun fromName(value: String?): EngineKind =
            entries.firstOrNull { it.name == value } ?: RIME
    }
}
