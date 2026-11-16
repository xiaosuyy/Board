package com.board.ime.dictionary

/** 自定义词条：用户给定的词 + 输入编码 */
data class CustomWord(val word: String, val code: String)

/**
 * 自定义词的编码规则（纯本地计算，不在按键路径查库）。
 *
 * 编码归一化与写入 Rime `custom_phrase` 的规则保持一致（去掉空格、转小写），
 * 这样「Rime 里怎么匹配」和「Board 侧怎么匹配」不会出现两套标准。
 */
object CustomWordCodes {

    fun normalize(code: String): String = code.replace(" ", "").trim().lowercase()

    /**
     * 简拼：按音节取首字母（`beijingdaxue` → `bjdx`）。
     *
     * 切不出两个及以上音节（例如字母编码 `mail`）时返回空串——这种编码只按原样匹配。
     */
    fun initials(code: String): String {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return ""
        val tokens = PinyinSyllables.segment(normalized).split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 2) return ""
        if (tokens.any { token -> token.any { it < 'a' || it > 'z' } }) return ""
        return tokens.joinToString("") { it.first().toString() }
    }
}

/**
 * 换行词条的匹配表。
 *
 * Rime 的 `custom_phrase` 是逐行文本，词条里的换行放不进去，
 * 所以这类词条（长句 / 多行地址）由 Board 直接上屏；这里只做「编码 → 词条」的内存查表。
 * 在进入输入框时一次性构建，按键时只做 HashMap 查询。
 */
class CustomWordIndex(words: List<CustomWord>) {

    private val byCode = HashMap<String, MutableList<CustomWord>>()

    init {
        words.forEach { word ->
            val code = CustomWordCodes.normalize(word.code)
            if (code.isEmpty()) return@forEach
            byCode.getOrPut(code) { ArrayList() } += word
            val initials = CustomWordCodes.initials(code)
            if (initials.isNotEmpty() && initials != code) {
                byCode.getOrPut(initials) { ArrayList() } += word
            }
        }
    }

    /** 当前拼音 / 编码命中的词条（按添加顺序，稳定不抖动） */
    fun match(composing: CharSequence): List<CustomWord> {
        if (composing.isEmpty()) return emptyList()
        // preedit 带音节分隔空格（zi jie），与写入 custom_phrase 的归一化保持一致
        return byCode[CustomWordCodes.normalize(composing.toString())].orEmpty()
    }
}
