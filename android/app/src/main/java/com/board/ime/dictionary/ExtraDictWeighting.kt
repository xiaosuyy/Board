package com.board.ime.dictionary

/**
 * 导入词库（额外的静态词源）的**权重策略**（纯逻辑，便于单元测试）。
 *
 * 产品优先级：**用户学习词 > 基础常用词 > 常用扩展词 > 低频扩展词**。
 *
 * ## 为什么需要它
 * 基础词库 `luna_pinyin` 使用 `use_preset_vocabulary: true`（essay.txt 预置词频，
 * 中位约 265）。如果给导入词库塞一个偏大的“伪词频”，生僻词 / 专名就会压过日常词。
 * 因此这里约定：
 * - 导入词库的 `initial_quality` 取一个**明显为负**的值 → 任何基础词库候选都排在它前面；
 * - 导入词条内部的权重只在**扩展词之间**排序，取很小的值（按音节数，1..16）。
 */
internal object ExtraDictWeighting {

    /**
     * 额外词源的候选质量（`initial_quality`）。
     *
     * librime 会把该值叠加到候选权重上再统一排序；取负值保证“基础常用词”
     * 永远排在“扩展词”之前，扩展词只负责**扩大覆盖面**，不接管排序。
     */
    const val INITIAL_QUALITY: Int = -1000

    /**
     * 没有真实词频的导入词条，按**音节数**给一个很轻的近似：短词通常是日常词，
     * 长词多是专名 / 古语。返回值范围 1..16（仅用于扩展词内部排序）。
     */
    fun weightForSyllables(syllables: Int): Int =
        (16 - syllables).coerceAtLeast(1)
}
