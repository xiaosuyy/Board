package com.board.ime.ui.panel

/**
 * 候选格尺寸的**纯计算**（不依赖 Android，便于单元测试）。
 *
 * 规则与 P3 需求一致：
 * - 有非空白英文释义才显示第二行；
 * - 候选格内容宽 = `max(中文实际宽, 英文实际宽)`；
 * - 候选格宽 = 内容宽 + 左右内边距，并且不小于最小宽度；
 * - 英文更长时由英文决定宽度（不截断 / 不省略 / 不换行，靠横向滚动看全）。
 *
 * [com.board.ime.ui.panel.CandidateBar] 里的候选 View 在 `onMeasure` 时调用这里的
 * 函数，测试覆盖的就是真实使用的这段逻辑。
 */
internal object CandidateItemMetrics {

    /** 只有非空白释义才显示英文第二行（null / "" / 全空白都按没有处理） */
    fun showsEnglishLine(englishGloss: String?): Boolean = !englishGloss.isNullOrBlank()

    /** 内容宽：中英取最大值（任一为 0 视为不参与） */
    fun contentWidth(chineseWidth: Int, englishWidth: Int): Int =
        maxOf(chineseWidth, englishWidth, 0)

    /** 候选格宽：内容宽 + 左右内边距，且不小于 [minWidth] */
    fun requiredWidth(
        chineseWidth: Int,
        englishWidth: Int,
        horizontalPadding: Int,
        minWidth: Int,
    ): Int {
        val content = contentWidth(chineseWidth, englishWidth)
        val padding = horizontalPadding.coerceAtLeast(0)
        return maxOf(content + padding * 2, minWidth.coerceAtLeast(0))
    }
}
