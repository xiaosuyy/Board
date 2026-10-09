package com.board.ime.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 英文词频表的加载器。
 *
 * 进程内**只加载一次**：从 `assets/english_words.txt` 读取（每行一个词，**行序即词频名次**，
 * 越靠前越常用）→ [EnglishLexicon.install]。之后所有补全都是内存查询。
 *
 * - 可重复调用（[loaded] 保证只读一次文件）；
 * - 任何异常都只记日志并保留内置兜底词表，**不会崩溃、不会联网、不会访问数据库**；
 * - 纯文本小文件（约 74KB / 10k 词），同步加载轻量。
 */
object EnglishLexiconLoader {

    const val ASSET_NAME = "english_words.txt"

    private val loaded = AtomicBoolean(false)

    /** @return true 表示本次真正执行了加载；false 表示已加载过或加载失败 */
    fun loadOnce(context: Context): Boolean {
        if (!loaded.compareAndSet(false, true)) return false
        return try {
            val list = context.applicationContext.assets
                .open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.readLines() }
            EnglishLexicon.install(list)
            Log.i(TAG, "英文词频表已加载：${EnglishLexicon.size} 词（$ASSET_NAME）")
            true
        } catch (throwable: Throwable) {
            // 加载失败保持内置兜底词表，不影响输入
            Log.w(TAG, "英文词频表加载失败，使用内置兜底词表：${throwable.message}")
            false
        }
    }

    private const val TAG = "BoardEnLexicon"
}
