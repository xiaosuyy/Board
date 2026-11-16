package com.board.ime.dictionary

import android.content.Context
import org.json.JSONObject

/**
 * 学习词的「最后使用时间」与 30 天淡出。
 *
 * librime 的用户词典只记「选过多少次」，不记时间：选过一次的词会一直排在前面。
 * 这里在本地补一层使用时间（纯 SharedPreferences，不联网、不上传）：
 * - 30 天没用：候选栏里排到普通候选后面（还能选，只是不再优先）
 * - 60 天没用：从 librime 用户词典里移除学习加成（词库本身的词还在，再用一次会重新学习）
 *
 * 只记录汉字词（1–[MAX_WORD_LENGTH] 字），整句上屏与英文不进入这张表。
 */
class LearnedWordDecay(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lastUsed: MutableMap<String, Long> = load()
    private var lastFlushAt = 0L

    fun record(word: String, now: Long = System.currentTimeMillis()) {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return
        synchronized(this) {
            lastUsed[word] = now
            if (lastUsed.size > MAX_WORDS) trim()
            flushLocked(now, force = false)
        }
    }

    /** 距离上次使用多少天；没有记录时返回 null（不参与淡出） */
    fun idleDays(word: String, now: Long): Int? = synchronized(this) {
        val at = lastUsed[word] ?: return null
        ((now - at) / DAY_MS).toInt()
    }

    /** 30 天没用：候选不再优先 */
    fun isDemoted(word: String, now: Long = System.currentTimeMillis()): Boolean =
        (idleDays(word, now) ?: 0) >= DEMOTE_AFTER_DAYS

    /** 60 天没用：可以从用户词典移除学习加成 */
    fun expiredWords(now: Long = System.currentTimeMillis()): Set<String> = synchronized(this) {
        lastUsed.filterValues { now - it >= FORGET_AFTER_DAYS * DAY_MS }.keys.toSet()
    }

    fun forget(words: Collection<String>) {
        if (words.isEmpty()) return
        synchronized(this) {
            words.forEach { lastUsed.remove(it) }
            flushLocked(System.currentTimeMillis(), force = true)
        }
    }

    /** 一天最多扫一次 userdb；返回 true 表示这次应该扫 */
    fun isSweepDue(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong(KEY_LAST_SWEEP, 0L) >= DAY_MS

    fun markSwept(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SWEEP, now).apply()
    }

    private fun load(): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_LAST_USED, null) ?: return HashMap()
        return runCatching {
            val json = JSONObject(raw)
            val map = HashMap<String, Long>(json.length())
            json.keys().forEach { key -> map[key] = json.optLong(key, 0L) }
            map
        }.getOrElse { HashMap() }
    }

    private fun trim() {
        lastUsed.entries
            .sortedBy { it.value }
            .take((lastUsed.size - MAX_WORDS).coerceAtLeast(0))
            .forEach { lastUsed.remove(it.key) }
    }

    private fun flushLocked(now: Long, force: Boolean) {
        if (!force && now - lastFlushAt < FLUSH_INTERVAL_MS) return
        lastFlushAt = now
        val json = JSONObject()
        lastUsed.forEach { (word, at) -> json.put(word, at) }
        prefs.edit().putString(KEY_LAST_USED, json.toString()).apply()
    }

    companion object {
        /** 30 天不用：候选靠后 */
        const val DEMOTE_AFTER_DAYS = 30

        /** 60 天不用：移除学习加成 */
        const val FORGET_AFTER_DAYS = 60

        private const val PREFS = "learned_decay"
        private const val KEY_LAST_USED = "last_used"
        private const val KEY_LAST_SWEEP = "last_sweep"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_WORDS = 4000
        private const val MAX_WORD_LENGTH = 12
        private const val FLUSH_INTERVAL_MS = 30_000L
    }
}
