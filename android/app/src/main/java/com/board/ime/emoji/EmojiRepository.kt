package com.board.ime.emoji

import android.content.Context
import com.board.ime.settings.BoardPreferences

/**
 * 表情与符号的本地检索。
 *
 * 数据全部随 APK 分发、只在本机解析：
 * - `emoji/cldr.tsv`：Unicode CLDR 的中英文关键词（Unicode License v3）
 * - `emoji/zh-extra.tsv`：聊天常用词（优先）
 * - `emoji/search.tsv`：由上述两份生成的拼音 / 首字母索引
 *
 * 与 WowType 的约定一致：
 * - 打字推荐只认「两个字以上、够具体」的词；整词命中时只上屏 emoji，
 *   句尾命中时上屏「整句 + emoji」。
 * - 数字字额外推荐按键 emoji（八 → 8️⃣）。
 */
class EmojiRepository(
    private val context: Context,
    private val preferences: BoardPreferences,
) {

    data class EmojiSuggestion(
        val emoji: String,
        val display: String,
        val commit: String,
    )

    private data class Entry(
        val emoji: String,
        val zh: List<String>,
        val en: List<String>,
    )

    data class Symbol(val text: String, val names: List<String>)

    @Volatile
    private var loaded = false

    private var entries: List<Entry> = emptyList()
    private var byZh: Map<String, List<Int>> = emptyMap()
    private var extra: Map<String, List<String>> = emptyMap()
    private var byPinyin: Map<String, List<String>> = emptyMap()

    /** 预热：服务启动时在后台线程调用一次，避免第一次点表情时卡住主线程 */
    fun preload() {
        load()
    }

    fun all(): List<String> {
        load()
        return entries.map { it.emoji }
    }

    fun recent(): List<String> = preferences.recentEmojis

    fun recordRecent(emoji: String) {
        if (emoji.isBlank()) return
        val next = (listOf(emoji) + recent().filter { it != emoji }).take(MAX_RECENT)
        preferences.recentEmojis = next
    }

    /**
     * 表情面板搜索：中文关键词、全拼、首字母、英文都认。
     *
     * 顺序：额外词 → 拼音精确 → 英文精确 → 符号精确 → 拼音前缀 → 英文前缀。
     */
    fun search(query: String, limit: Int = MAX_RESULTS): List<String> {
        load()
        val raw = query.trim()
        if (raw.isEmpty()) return all()

        val out = LinkedHashSet<String>()
        if (raw.any { it in CJK_RANGE }) {
            extra[raw]?.let(out::addAll)
            byZh[raw]?.forEach { out.add(entries[it].emoji) }
            entries.forEach { entry ->
                if (entry.zh.any { it.contains(raw) }) out.add(entry.emoji)
            }
        } else {
            val q = raw.lowercase()
            byPinyin[q]?.let(out::addAll)
            addEnglish(out, q, prefix = false)
            addSymbols(out, q, prefix = false)
            byPinyin.forEach { (token, emojis) ->
                if (token != q && token.startsWith(q)) out.addAll(emojis)
            }
            addEnglish(out, q, prefix = true)
            addSymbols(out, q, prefix = true)
        }
        return out.take(limit)
    }

    /**
     * 打字时推荐：首选候选文本 → 最多 [limit] 个 emoji。
     * 整词命中给「只有 emoji」，句尾命中给「整句 + emoji」。
     */
    fun recommend(text: String, limit: Int = 1): List<EmojiSuggestion> {
        load()
        val value = text.trim()
        if (value.isEmpty() || limit <= 0 || value.length > MAX_RECOMMEND_LENGTH) return emptyList()

        keycap(value)?.let { emoji ->
            return listOf(EmojiSuggestion(emoji = emoji, display = emoji, commit = emoji))
        }

        val exact = specific(value)
        if (exact.isNotEmpty()) {
            return exact.take(limit).map { emoji ->
                EmojiSuggestion(emoji = emoji, display = emoji, commit = emoji)
            }
        }

        // 句尾的词：越长越具体，「今天好开心」先试「天好开心」……最后「开心」
        for (start in 1 until value.length) {
            val tail = value.substring(start)
            val hits = specific(tail)
            if (hits.isNotEmpty()) {
                return hits.take(limit).map { emoji ->
                    EmojiSuggestion(
                        emoji = emoji,
                        display = tail + emoji,
                        commit = value + emoji,
                    )
                }
            }
        }
        return emptyList()
    }

    fun keycap(text: String): String? = KEYCAPS[text.trim()]

    // ------------------------------------------------------------ 内部实现

    /** 够具体的词才拿来推荐：「水果」这类对应几十个 emoji 的关键词跳过 */
    private fun specific(word: String): List<String> {
        if (word.length < 2) return emptyList()
        extra[word]?.let { return it }
        val hits = byZh[word] ?: return emptyList()
        return if (hits.size <= SPECIFIC_MAX) hits.map { entries[it].emoji } else emptyList()
    }

    private fun addEnglish(out: MutableSet<String>, q: String, prefix: Boolean) {
        entries.forEach { entry ->
            if (entry.en.any { keyword -> matches(keyword, q, prefix) }) out.add(entry.emoji)
        }
    }

    private fun addSymbols(out: MutableSet<String>, q: String, prefix: Boolean) {
        SYMBOLS.forEach { symbol ->
            if (symbol.names.any { name -> matches(name.lowercase(), q, prefix) }) {
                out.add(symbol.text)
            }
        }
    }

    private fun matches(keyword: String, q: String, prefix: Boolean): Boolean {
        val tokens = keyword.split(NON_ALNUM)
        return tokens.any { token ->
            if (token.isEmpty()) false
            else if (prefix) token != q && token.startsWith(q)
            else token == q
        }
    }

    @Synchronized
    private fun load() {
        if (loaded) return

        val parsed = ArrayList<Entry>(1900)
        val zhIndex = HashMap<String, MutableList<Int>>()
        val extraMap = LinkedHashMap<String, MutableList<String>>()
        val pinyinMap = LinkedHashMap<String, MutableList<String>>()

        readLines(ASSET_EXTRA) { line ->
            val columns = line.split('\t')
            if (columns.size < 2) return@readLines
            val word = columns[0].trim()
            val emojis = columns[1].split(' ').filter { it.isNotBlank() }
            if (word.isEmpty() || emojis.isEmpty()) return@readLines
            extraMap.getOrPut(word) { ArrayList() }.addAll(emojis)
        }

        readLines(ASSET_CLDR) { line ->
            val columns = line.split('\t')
            if (columns.size < 3) return@readLines
            val emoji = columns[0].trim()
            val zh = columns[1].split('|').filter { it.isNotBlank() }
            val en = columns[2].split('|').filter { it.isNotBlank() }
            if (emoji.isEmpty()) return@readLines
            val index = parsed.size
            zh.forEach { word -> zhIndex.getOrPut(word) { ArrayList() }.add(index) }
            parsed += Entry(emoji, zh, en)
        }

        readLines(ASSET_SEARCH) { line ->
            val columns = line.split('\t')
            if (columns.size < 2) return@readLines
            val token = columns[0].trim()
            val emojis = columns[1].split(' ').filter { it.isNotBlank() }
            if (token.isEmpty() || emojis.isEmpty()) return@readLines
            pinyinMap.getOrPut(token) { ArrayList() }.addAll(emojis)
        }

        entries = parsed
        byZh = zhIndex
        extra = extraMap
        byPinyin = pinyinMap
        loaded = true
    }

    private inline fun readLines(path: String, block: (String) -> Unit) {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isNotEmpty() && !line.startsWith('#')) block(line)
            }
        }
    }

    private companion object {
        const val ASSET_CLDR = "emoji/cldr.tsv"
        const val ASSET_EXTRA = "emoji/zh-extra.tsv"
        const val ASSET_SEARCH = "emoji/search.tsv"

        const val MAX_RESULTS = 160
        const val MAX_RECENT = 32
        const val MAX_RECOMMEND_LENGTH = 40

        /** 一个关键词对应超过这么多 emoji 就算太泛，不拿来做打字推荐 */
        const val SPECIFIC_MAX = 3

        val CJK_RANGE = '\u4e00'..'\u9fff'
        val NON_ALNUM = Regex("[^a-z0-9]+")

        val KEYCAPS = mapOf(
            "零" to "0️⃣",
            "〇" to "0️⃣",
            "一" to "1️⃣",
            "二" to "2️⃣",
            "三" to "3️⃣",
            "四" to "4️⃣",
            "五" to "5️⃣",
            "六" to "6️⃣",
            "七" to "7️⃣",
            "八" to "8️⃣",
            "九" to "9️⃣",
            "十" to "🔟",
        )

        /** 常用符号（拼音名与中文名都可搜），与输入法符号面板一致 */
        val SYMBOLS = listOf(
            Symbol("，", listOf("douhao", "逗号")),
            Symbol("。", listOf("juhao", "句号")),
            Symbol("、", listOf("dunhao", "顿号")),
            Symbol("；", listOf("fenhao", "分号")),
            Symbol("：", listOf("maohao", "冒号")),
            Symbol("？", listOf("wenhao", "问号")),
            Symbol("！", listOf("tanhao", "感叹号")),
            Symbol("“", listOf("yinhao", "引号", "zuoyinhao")),
            Symbol("”", listOf("yinhao", "引号", "youyinhao")),
            Symbol("‘", listOf("danyinhao")),
            Symbol("’", listOf("danyinhao")),
            Symbol("（", listOf("kuohao", "括号", "zuokuohao")),
            Symbol("）", listOf("kuohao", "括号", "youkuohao")),
            Symbol("【", listOf("fangkuohao", "方括号")),
            Symbol("】", listOf("fangkuohao", "方括号")),
            Symbol("《", listOf("shuminghao", "书名号")),
            Symbol("》", listOf("shuminghao", "书名号")),
            Symbol("——", listOf("pozhehao", "破折号")),
            Symbol("……", listOf("shengluehao", "省略号")),
            Symbol("·", listOf("jianhaohao", "间隔号")),
            Symbol("～", listOf("bolanghao", "波浪号")),
            Symbol("￥", listOf("renminbi", "人民币", "yuan")),
            Symbol("＄", listOf("meiyuan", "美元")),
            Symbol("％", listOf("baifenhao", "百分号")),
            Symbol("‰", listOf("qianfenhao")),
            Symbol("℃", listOf("sheshidu", "摄氏度")),
            Symbol("±", listOf("zhengfuhao", "正负号")),
            Symbol("×", listOf("chenghao", "乘号")),
            Symbol("÷", listOf("chuhao", "除号")),
            Symbol("≈", listOf("yuedengyu", "约等于")),
            Symbol("≠", listOf("budengyu", "不等于")),
            Symbol("≤", listOf("xiaoyudengyu", "小于等于")),
            Symbol("≥", listOf("dayudengyu", "大于等于")),
            Symbol("∞", listOf("wuqiong", "无穷")),
            Symbol("√", listOf("genhao", "根号")),
            Symbol("°", listOf("du", "度")),
            Symbol("→", listOf("youjiantou", "右箭头")),
            Symbol("←", listOf("zuojiantou", "左箭头")),
            Symbol("↑", listOf("shangjiantou", "上箭头")),
            Symbol("↓", listOf("xiajiantou", "下箭头")),
            Symbol("☆", listOf("xingxing", "星星")),
            Symbol("★", listOf("xingxing", "星星")),
            Symbol("○", listOf("yuanquan", "圆圈")),
            Symbol("●", listOf("yuanquan", "圆圈")),
            Symbol("△", listOf("sanjiao", "三角")),
            Symbol("▲", listOf("sanjiao", "三角")),
            Symbol("□", listOf("fangkuang", "方框")),
            Symbol("■", listOf("fangkuang", "方框")),
            Symbol("✓", listOf("gou", "对勾")),
            Symbol("✗", listOf("cha", "叉")),
            Symbol("①", listOf("yihao", "1")),
            Symbol("②", listOf("erhao", "2")),
            Symbol("③", listOf("sanhao", "3")),
            Symbol("④", listOf("sihao", "4")),
            Symbol("⑤", listOf("wuhao", "5")),
            Symbol("§", listOf("duanluohao")),
            Symbol("№", listOf("bianhao")),
        )
    }
}
