package com.board.ime.engine

/**
 * 阶段一的英文引擎实现。
 *
 * 职责：
 * - 维护当前正在组词的字母序列；
 * - 按前缀给出简单的英文候选（内置词表，不查数据库）；
 * - 空格 / 回车 / 标点到达时把组词内容提交上屏。
 *
 * 中文模式下它只做“拉丁字母直通”，不做任何中文转换 ——
 * 完整中文输入会在后续阶段由 `RimeEngine`（librime）实现 [InputEngine] 后替换。
 */
class BasicEngine(initialMode: InputMode = InputMode.ENGLISH) : InputEngine {

    override val id: String = "basic"
    override val displayName: String = "Basic Engine"

    private val buffer = StringBuilder()

    private var currentMode: InputMode = initialMode

    override var mode: InputMode
        get() = currentMode
        set(value) {
            if (currentMode == value) return
            currentMode = value
            reset()
        }

    override val composing: CharSequence
        get() = buffer.toString()

    override fun reset() {
        buffer.setLength(0)
    }

    override fun candidates(): List<Candidate> {
        if (buffer.isEmpty()) return emptyList()
        return when (currentMode) {
            // 中文由 Rime 引擎负责；BasicEngine 下中文层不产出候选
            InputMode.CHINESE -> emptyList()
            InputMode.ENGLISH, InputMode.NUMBER, InputMode.SYMBOL ->
                englishCandidates(buffer.toString())
        }
    }

    override fun handleKey(key: EngineKey): EngineResult = when (key) {
        is EngineKey.Letter -> {
            buffer.append(key.char)
            EngineResult.Composing(buffer.toString())
        }

        EngineKey.Backspace -> {
            if (buffer.isEmpty()) {
                EngineResult.Ignored
            } else {
                buffer.setLength(buffer.length - 1)
                EngineResult.Composing(buffer.toString())
            }
        }

        EngineKey.Space -> {
            if (buffer.isEmpty()) EngineResult.Ignored else commitWith(suffix = " ")
        }

        EngineKey.Enter -> {
            if (buffer.isEmpty()) EngineResult.Ignored else commitWith(suffix = "")
        }

        is EngineKey.Punctuation -> {
            if (buffer.isEmpty()) EngineResult.Ignored else commitWith(suffix = key.text)
        }
    }

    override fun pickCandidate(index: Int): EngineResult {
        if (currentMode == InputMode.CHINESE) return EngineResult.Ignored
        val candidate = candidates().getOrNull(index) ?: return EngineResult.Ignored
        if (!candidate.enabled) return EngineResult.Ignored
        reset()
        // 英文选词后补一个空格，符合 Gboard 的输入习惯
        return EngineResult.Commit(candidate.text + " ")
    }

    private fun commitWith(suffix: String): EngineResult {
        val text = buffer.toString() + suffix
        reset()
        return EngineResult.Commit(text)
    }

    private fun englishCandidates(input: String): List<Candidate> {
        val lower = input.lowercase()
        val leadingUpper = input.firstOrNull()?.isUpperCase() == true

        val result = ArrayList<Candidate>(MAX_CANDIDATES)
        // 第一个候选永远保留“原样输入”，保证直接回车 / 空格不会丢字
        result += Candidate(text = input)

        EnglishLexicon.suggest(prefix = lower, limit = MAX_CANDIDATES).forEach { word ->
            if (word == lower) return@forEach
            val display = if (leadingUpper) word.replaceFirstChar { it.uppercaseChar() } else word
            result += Candidate(text = display)
        }
        return result.take(MAX_CANDIDATES)
    }

    private companion object {
        /**
         * 英文候选上限（含"原样输入"那一项）。
         *
         * 对齐微信英文：微信是「一页候选 + `∨` 展开更多」；Board 用**同一个可横向滑动的候选栏**，
         * 因此这里给足一整页（与 [com.board.ime.ui.panel.CandidateBar] 的上限一致），
         * 不再像早期那样只给 8 个。多出来的候选横向滑动即可看全。
         */
        const val MAX_CANDIDATES = 50
    }
}
