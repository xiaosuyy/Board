package com.board.ime.sync

import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.dictionary.WordType
import com.board.ime.engine.rime.RimeRuntime

/**
 * 学习词 ⇄ librime 用户词典（userdb）。
 *
 * 这是「学习词同步」真正生效的关键：同步下来的学习词必须**写回 librime**，
 * 这样才能参与候选排序，而不只是显示在设置页里。
 *
 * 合并语义完全交给 librime：`UserDbImporter::Put` 用的是
 * `commits = max(旧, 新)`，所以
 * - A 设备 `你好/nihao/frequency 10` + B 设备 `6` → 合并后是 `10`（保留并合并学习信息）
 * - 重复导入同一份文件不会累加（幂等）
 * - 次数写成负数表示删除（墓碑），删除也能传播
 */
object LearnedWordBridge {

    /** librime 用户词典文本 → 同步记录 */
    fun parseUserDictText(text: String): List<SyncWordRecord> {
        val records = ArrayList<SyncWordRecord>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val columns = line.split('\t')
            if (columns.size < 2) return@forEach
            // librime 导出格式：词条 \t 编码 \t 次数
            val word = columns[0].trim()
            val code = columns[1].trim()
            if (word.isEmpty()) return@forEach
            val frequency = columns.getOrNull(2)
                ?.trim()
                ?.removePrefix("c=")
                ?.trim()
                ?.toIntOrNull() ?: 0
            records += SyncWordRecord(
                word = word,
                code = code.takeIf { it.isNotEmpty() },
                frequency = frequency,
                type = WordType.LEARNED.name,
                deleted = frequency < 0,
                updatedAt = 0L,
            )
        }
        return records
    }

    /**
     * 同步记录 → librime 用户词典文本。
     * 列顺序与 librime 一致（词条 \t 编码 \t 次数），删除写成负数。
     */
    fun toUserDictText(records: List<SyncWordRecord>): String = buildString {
        records.forEach { record ->
            if (record.word.isEmpty()) return@forEach
            val count = if (record.deleted) -maxOf(1, kotlin.math.abs(record.frequency))
            else maxOf(0, record.frequency)
            append(record.word).append('\t')
            append(record.code.orEmpty()).append('\t')
            append(count).append('\n')
        }
    }

    /**
     * 把（已合并的）学习词写回 librime。
     *
     * @return librime 报告的导入条数；失败返回 -1
     */
    fun applyToRime(records: List<SyncWordRecord>): Int {
        if (records.isEmpty()) return 0
        val dir = RimeRuntime.userDataDir() ?: return -1
        val file = java.io.File(dir, IMPORT_FILE)
        return try {
            file.writeText(toUserDictText(records))
            val count = RimeRuntime.importUserDict(file)
            count
        } catch (throwable: Throwable) {
            -1
        } finally {
            file.delete()
        }
    }

    /** 给日志用的短哈希（学习词正文不进日志） */
    fun hashOf(word: String, code: String?): String =
        SyncLog.shortHash(ClipboardHasher.hash(word + "\u0000" + code.orEmpty()))

    private const val IMPORT_FILE = "board_learned_sync.txt"
}
