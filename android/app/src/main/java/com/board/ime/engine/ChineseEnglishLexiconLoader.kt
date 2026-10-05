package com.board.ime.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地中英释义表的加载器。
 *
 * 进程内**只加载一次**：从 `assets/chinese_english.tsv` 读取 → [ChineseEnglishTsv.parse]
 * → [ChineseEnglishLexicon.install]。之后所有 lookup 都是内存查询。
 *
 * - 可重复调用（[loaded] 保证只读一次文件）；
 * - 任何异常都只记日志并保持空表，**不会崩溃、不会联网、不会访问数据库**；
 * - 文件很小（纯文本），加载是同步且轻量的。
 */
object ChineseEnglishLexiconLoader {

    const val ASSET_NAME = "chinese_english.tsv"

    private val loaded = AtomicBoolean(false)

    /** @return true 表示本次真正执行了加载；false 表示已加载过或加载失败 */
    fun loadOnce(context: Context): Boolean {
        if (!loaded.compareAndSet(false, true)) return false
        return try {
            val text = context.applicationContext.assets
                .open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val map = ChineseEnglishTsv.parse(text)
            ChineseEnglishLexicon.install(map)
            Log.i(TAG, "本地中英释义表已加载：${map.size} 条（$ASSET_NAME）")
            true
        } catch (throwable: Throwable) {
            // 加载失败时保持空表：候选栏等价于不显示英文，不影响输入
            Log.w(TAG, "本地中英释义表加载失败，按无释义处理：${throwable.message}")
            false
        }
    }

    private const val TAG = "BoardGloss"
}
