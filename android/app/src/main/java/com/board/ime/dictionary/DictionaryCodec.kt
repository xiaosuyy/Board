package com.board.ime.dictionary

import com.board.ime.database.entity.DictionaryEntry
import org.json.JSONArray
import org.json.JSONObject

/** 支持导入 / 导出的格式 */
enum class DictionaryFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    TXT("TXT（每行 词条[制表符]编码[制表符]词频）", "txt", "text/plain"),
    CSV("CSV（word,code,frequency,type）", "csv", "text/csv"),
    JSON("JSON（数组，字段 word/code/frequency/type）", "json", "application/json"),
    RIME_DICT("Rime 词库（词条[制表符]编码[制表符]权重）", "dict.yaml", "text/plain"),
    ;

    companion object {
        fun fromFileName(name: String?): DictionaryFormat? {
            val lower = name?.lowercase() ?: return null
            return when {
                lower.endsWith(".json") -> JSON
                lower.endsWith(".csv") -> CSV
                lower.endsWith(".dict.yaml") || lower.endsWith(".yaml") || lower.endsWith(".yml") -> RIME_DICT
                lower.endsWith(".txt") -> TXT
                else -> null
            }
        }

        fun fromMimeType(mime: String?): DictionaryFormat? = when {
            mime == null -> null
            mime.contains("json", ignoreCase = true) -> JSON
            mime.contains("csv", ignoreCase = true) -> CSV
            mime.startsWith("text/", ignoreCase = true) -> TXT
            else -> null
        }
    }
}

/** 解析出来的一条词 */
data class ParsedWord(
    val word: String,
    val code: String?,
    val frequency: Int,
    val type: WordType?,
)

/** 解析结果：[entries] 能用的，[failed] 没解析出来的行数 */
data class ParseOutcome(
    val entries: List<ParsedWord>,
    val failed: Int,
)

/** 文件内容不符合所选格式 */
class DictionaryFormatException(message: String) : Exception(message)

/**
 * 词库文件解析 / 生成。
 *
 * 只做「格式 → 词条」和「词条 → 格式」的转换，不碰数据库。
 * 程度上尽量宽容（空行、注释、多余列都跳过并计入 failed）。
 */
object DictionaryCodec {

    // ---------------------------------------------------------------- 解析

    /** 根据文件名 / MIME / 内容自动判断格式 */
    fun detect(content: String, fileName: String?, mimeType: String?): DictionaryFormat {
        DictionaryFormat.fromFileName(fileName)?.let { return it }
        DictionaryFormat.fromMimeType(mimeType)?.let { return it }
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> DictionaryFormat.JSON
            trimmed.lineSequence().firstOrNull()?.contains(',') == true -> DictionaryFormat.CSV
            trimmed.startsWith("---") -> DictionaryFormat.RIME_DICT
            else -> DictionaryFormat.TXT
        }
    }

    fun parse(content: String, format: DictionaryFormat): ParseOutcome = when (format) {
        DictionaryFormat.TXT -> parseDelimited(content, ',', null)
        DictionaryFormat.CSV -> parseCsv(content)
        DictionaryFormat.JSON -> parseJson(content)
        DictionaryFormat.RIME_DICT -> parseDelimited(content, '\t', "---")
    }

    /**
     * Rime 用户词典导出文件。
     *
     * 注意列顺序：librime 的 `TableDb` 写出时会做一次 `row[0].swap(row[1])`，
     * 所以导出文件是 `词条 \t 编码 \t 次数`（导入时同一份格式也成立）。
     */
    fun parseRimeUserDict(content: String): ParseOutcome {
        var failed = 0
        val entries = ArrayList<ParsedWord>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val columns = line.split('\t')
            if (columns.size < 2) {
                failed++
                return@forEach
            }
            val word = columns[0].trim()
            val code = columns[1].trim()
            if (word.isEmpty()) {
                failed++
                return@forEach
            }
            val frequency = parseFrequency(columns.getOrNull(2))
            entries += ParsedWord(word, code.takeIf { it.isNotEmpty() }, frequency, WordType.LEARNED)
        }
        return ParseOutcome(entries, failed)
    }

    private fun parseDelimited(content: String, delimiter: Char, headerMarker: String?): ParseOutcome {
        var failed = 0
        val entries = ArrayList<ParsedWord>()
        var inHeader = headerMarker != null
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) return@forEach
            if (inHeader) {
                // 跳过 Rime 词库的 YAML 头（--- 开头，直到出现真正以制表符分隔的词条行）
                if (line.startsWith(headerMarker!!) || line.contains(':')) return@forEach
                inHeader = false
            }
            val columns = line.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
            if (columns.isEmpty()) {
                failed++
                return@forEach
            }
            val word = columns[0]
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH) {
                failed++
                return@forEach
            }
            entries += ParsedWord(
                word = word,
                code = columns.getOrNull(1)?.takeIf { it.isNotEmpty() },
                frequency = parseFrequency(columns.getOrNull(2)),
                type = columns.getOrNull(3)?.let { WordType.fromLabel(it) },
            )
        }
        return ParseOutcome(entries, failed)
    }

    private fun parseCsv(content: String): ParseOutcome {
        var failed = 0
        val entries = ArrayList<ParsedWord>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val columns = splitCsvLine(line)
            if (columns.isEmpty()) return@forEach
            val word = columns[0].trim()
            if (word.equals("word", ignoreCase = true)) return@forEach // 表头
            if (word.isEmpty()) {
                failed++
                return@forEach
            }
            entries += ParsedWord(
                word = word,
                code = columns.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
                frequency = parseFrequency(columns.getOrNull(2)),
                type = columns.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.let { WordType.fromLabel(it) },
            )
        }
        return ParseOutcome(entries, failed)
    }

    private fun parseJson(content: String): ParseOutcome {
        val array = try {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optJSONArray("words") ?: JSONArray(trimmed)
            } else {
                JSONArray(trimmed)
            }
        } catch (throwable: Throwable) {
            throw DictionaryFormatException("JSON 解析失败：${throwable.message}")
        }

        var failed = 0
        val entries = ArrayList<ParsedWord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val word = when (item) {
                is JSONObject -> item.optString("word")
                is String -> item
                else -> ""
            }.trim()
            if (word.isEmpty()) {
                failed++
                continue
            }
            val obj = item as? JSONObject
            entries += ParsedWord(
                word = word,
                code = obj?.optString("code")?.takeIf { it.isNotEmpty() },
                frequency = obj?.optInt("frequency", 0) ?: 0,
                type = obj?.optString("type")?.takeIf { it.isNotEmpty() }?.let { WordType.fromLabel(it) },
            )
        }
        return ParseOutcome(entries, failed)
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += current.toString()
                    current.setLength(0)
                }

                else -> current.append(ch)
            }
        }
        result += current.toString()
        return result
    }

    private fun parseFrequency(value: String?): Int {
        val raw = value?.trim()?.removePrefix("c=")?.trim() ?: return 0
        return raw.toIntOrNull() ?: 0
    }

    // ---------------------------------------------------------------- 生成

    fun serialize(entries: List<DictionaryEntry>, format: DictionaryFormat): String = when (format) {
        DictionaryFormat.JSON -> JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("word", entry.word)
                        entry.code?.takeIf { it.isNotEmpty() }?.let { put("code", it) }
                        put("frequency", entry.frequency)
                        put("type", entry.type.name)
                        put("enabled", entry.enabled)
                    },
                )
            }
        }.toString(2)

        DictionaryFormat.CSV -> buildString {
            appendLine("word,code,frequency,type")
            entries.forEach { entry ->
                appendLine(
                    listOf(
                        csvEscape(entry.word),
                        csvEscape(entry.code.orEmpty()),
                        entry.frequency.toString(),
                        entry.type.name,
                    ).joinToString(","),
                )
            }
        }

        DictionaryFormat.TXT, DictionaryFormat.RIME_DICT -> buildString {
            entries.forEach { entry ->
                append(entry.word)
                append('\t')
                append(entry.code.orEmpty())
                append('\t')
                append(entry.frequency)
                append('\n')
            }
        }
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\"" else value

    private const val MAX_WORD_LENGTH = 64
}
