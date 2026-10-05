package com.board.ime.engine

/**
 * 本地中英释义 TSV 资源（`assets/chinese_english.tsv`）的**纯解析**。
 *
 * 无 Android 依赖，便于单元测试；解析结果交给 [ChineseEnglishLexicon] 常驻内存。
 *
 * 格式：每行 `中文<TAB>英文`
 * - 空行、以 `#` 开头的注释行：忽略；
 * - 必须含 TAB，且两侧 trim 后都非空，否则整行忽略（**宁可少，也不错**）；
 * - 同一中文出现多次：保留**第一条**（人工确认的顺序即优先级）。
 *
 * 注意：解析器不做“猜词/翻译/模糊匹配”，只做精确的 `中文 -> 英文` 建表。
 */
object ChineseEnglishTsv {

    fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val chinese = line.substring(0, tab).trim()
            val english = line.substring(tab + 1).trim()
            if (chinese.isEmpty() || english.isEmpty()) return@forEach
            result.putIfAbsent(chinese, english)
        }
        return result
    }
}
