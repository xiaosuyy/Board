package com.board.ime.keyboard

/**
 * 一个按键的定义。
 *
 * 宽度用“单位”表示，一行内所有键 + 左右留白构成同一基准宽度（10 单位），
 * 因此键盘可以自适应任意屏幕宽度。
 */
data class KeySpec(
    val type: KeyType,
    /** 上屏文本；字母统一用小写保存，展示 / 上屏时按 Shift 处理 */
    val text: String? = null,
    /** 展示文本，默认与 [text] 相同 */
    val label: String? = null,
    /** 相对宽度单位 */
    val units: Float = 1f,
    /**
     * 竖列合并块编号：同一非 0 值的相邻键会画成一块圆角卡片，
     * 键与键之间只留细分隔线（数字层左侧 @ % -）。
     */
    val stackId: Int = 0,
) {
    val displayText: String get() = label ?: text.orEmpty()
}

/** 一行按键，[leadingPadUnits] / [trailingPadUnits] 用于让该行整体居中 */
data class KeyRow(
    val keys: List<KeySpec>,
    val leadingPadUnits: Float = 0f,
    val trailingPadUnits: Float = 0f,
) {
    val totalUnits: Float
        get() {
            var sum = leadingPadUnits + trailingPadUnits
            for (key in keys) sum += key.units
            return sum
        }
}
