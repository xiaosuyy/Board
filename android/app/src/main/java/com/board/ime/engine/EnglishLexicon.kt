package com.board.ime.engine

/**
 * 英文词表（按词频排序）。
 *
 * 对齐微信输入法的英文候选（本地部分，不含云端）：
 * - 微信英文候选来自 `EnglishStaticDictV3/V5` + 模型预测，**按常用度**给出补全；
 * - 这里用一份**按词频排序**的英文词表（`assets/english_words.txt`，10k 高频词，
 *   顺序即词频名次，见 [EnglishLexiconLoader]），按前缀补全、**常用词优先**。
 *
 * 兜底：assets 未装入（加载失败 / 单测）时回退到内置的少量常用词，行为与旧版一致。
 * 纯内存、无 Android 依赖，可单测。
 */
object EnglishLexicon {

    /** 兜底词表：assets 未装入时仍可用，顺序大致按常用度 */
    val fallbackWords: List<String> = listOf(
        "a", "about", "after", "again", "all", "also", "always", "am", "and", "answer",
        "any", "are", "around", "as", "at", "back", "bad", "be", "because", "been",
        "before", "best", "better", "between", "big", "bit", "both", "but", "by", "call",
        "can", "change", "check", "come", "could", "day", "did", "do", "does", "done",
        "down", "each", "early", "easy", "end", "even", "ever", "every", "feel", "few",
        "find", "first", "for", "found", "from", "get", "give", "go", "going", "good",
        "got", "great", "group", "had", "hand", "has", "have", "he", "hello", "help",
        "her", "here", "high", "him", "his", "home", "hope", "hour", "how", "however",
        "if", "in", "into", "is", "it", "its", "just", "keep", "kind", "know",
        "large", "last", "later", "leave", "left", "let", "life", "like", "line", "little",
        "long", "look", "made", "make", "man", "many", "may", "maybe", "me", "mean",
        "menu", "might", "more", "most", "much", "must", "my", "name", "need", "never",
        "new", "next", "nice", "no", "not", "note", "nothing", "now", "number", "of",
        "off", "ok", "okay", "old", "on", "once", "one", "only", "open", "or",
        "other", "our", "out", "over", "own", "part", "people", "place", "please", "point",
        "put", "question", "quite", "read", "really", "right", "room", "run", "said", "same",
        "say", "see", "seem", "send", "set", "she", "should", "show", "side", "since",
        "small", "so", "some", "something", "sorry", "still", "such", "sure", "take", "talk",
        "tell", "than", "thank", "thanks", "that", "the", "their", "them", "then", "there",
        "these", "they", "thing", "think", "this", "those", "though", "three", "through", "time",
        "to", "today", "together", "tomorrow", "too", "try", "turn", "two", "under", "until",
        "up", "us", "use", "used", "very", "want", "was", "watch", "water", "way",
        "we", "well", "went", "were", "what", "when", "where", "which", "while", "who",
        "why", "will", "with", "without", "word", "work", "world", "would", "write", "year",
        "yes", "yesterday", "yet", "you", "young", "your",
    )

    @Volatile
    private var words: List<String> = fallbackWords

    /** word -> 词频名次（越小越常用） */
    @Volatile
    private var rank: Map<String, Int> = index(fallbackWords)

    @Volatile
    private var byFirstChar: Map<Char, List<String>> = fallbackWords.groupBy { it.first() }

    /** 当前生效词条数（日志 / 诊断） */
    val size: Int get() = words.size

    /**
     * 整表替换（由 [EnglishLexiconLoader] 装入）。
     *
     * - 过滤空串、去重；**保留传入顺序**作为词频名次；
     * - 数量少于 [MIN_INSTALL_SIZE] 视为无效，保持现有（避免坏资源把词表清空）。
     */
    fun install(list: List<String>) {
        val cleared = normalize(list)
        if (cleared.size < MIN_INSTALL_SIZE) return
        apply(cleared)
    }

    /** 仅供测试：跳过最小规模保护。 */
    internal fun installUnchecked(list: List<String>) = apply(normalize(list))

    private fun normalize(list: List<String>): List<String> {
        val cleaned = LinkedHashSet<String>()
        for (raw in list) {
            val w = raw.trim().lowercase()
            if (w.isNotEmpty()) cleaned += w
        }
        return cleaned.toList()
    }

    private fun apply(ordered: List<String>) {
        words = ordered
        rank = index(ordered)
        byFirstChar = ordered.groupBy { it.first() }
    }

    /**
     * 按前缀补全，**常用词优先**（词频名次升序），名次相同再按长度 / 字典序。
     * 返回的候选 word 均为小写；大小写由调用方处理。
     */
    fun suggest(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val pool = byFirstChar[prefix.first()] ?: return emptyList()
        return pool.asSequence()
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it.length }, { it }))
            .take(limit)
            .toList()
    }

    /** 供 Room 词库做首次种子数据：word -> 初始词频（越靠前越高） */
    val seedEntries: List<Pair<String, Int>> by lazy {
        val total = words.size
        words.mapIndexed { index, word -> word to (total - index) }
    }

    private fun index(list: List<String>): Map<String, Int> =
        list.withIndex().associate { (i, w) -> w to i }

    private const val MIN_INSTALL_SIZE = 500
}
