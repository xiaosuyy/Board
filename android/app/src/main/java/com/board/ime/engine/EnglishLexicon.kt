package com.board.ime.engine

/**
 * 阶段一内置的英文词表。
 *
 * 它只服务于 [BasicEngine] 的“英文联想骨架”，不是词库系统：
 * - 中文词库、导入导出、词频统计放到后续阶段（Room 已建好 `dictionary_entry` 表）。
 * - 顺序大致按常用程度排列，用于给房间数据库做首次种子词频。
 */
object EnglishLexicon {

    val words: List<String> = listOf(
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

    private val byFirstChar: Map<Char, List<String>> = words.groupBy { it.first() }

    /** 按前缀推荐，结果按“长度短 + 字典序”排序，更接近常见输入法的联想顺序 */
    fun suggest(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val pool = byFirstChar[prefix.first()] ?: return emptyList()
        return pool.asSequence()
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .sortedWith(compareBy({ it.length }, { it }))
            .take(limit)
            .toList()
    }

    /** 供 Room 词库做首次种子数据：word to 初始词频 */
    val seedEntries: List<Pair<String, Int>> by lazy {
        val total = words.size
        words.mapIndexed { index, word -> word to (total - index) }
    }
}
