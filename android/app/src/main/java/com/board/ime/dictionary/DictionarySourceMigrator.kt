package com.board.ime.dictionary

import android.util.Log
import com.board.ime.database.dao.DictionaryDao
import com.board.ime.database.dao.DictionarySourceDao
import java.io.BufferedWriter
import java.io.File
import java.security.MessageDigest

/**
 * 把历史遗留的「大型导入词条」从 Room 搬成 [DictionarySource]。
 *
 * 背景：之前的导入实现会把整个大词库逐条写进 `dictionary_entry`
 * （真机上出现过 603,918 行、数据库 111MB），而且同步还会把这些行当成用户词导出，
 * 造成「同步永远卡在同步中」。
 *
 * 迁移原则（不丢数据）：
 * 1. **只认 `source = 'imported'` 的行** —— 用户自己加的词是 `local`、学习词是 `rime`，一律不动
 * 2. 先把词条**流式**写成独立词库文件（分页读 + 逐行写，绝不在内存里拼几十 MB 字符串），
 *    写完校验行数与统计**完全一致**
 * 3. 再写入一行 [DictionarySource]（含路径 / hash / 条数 / 大小）
 * 4. 最后才删掉 Room 里那批重复副本
 *
 * 任何一步失败都保留原数据。真机上 60 万行曾因一次性读入 + 拼字符串触发
 * OutOfMemoryError（14MB / 30MB 单次分配），所以这里必须是分页 + 流式。
 */
class DictionarySourceMigrator(
    private val dictionaryDao: DictionaryDao,
    private val sourceDao: DictionarySourceDao,
    private val importDir: File?,
) {

    data class Report(
        val movedEntries: Int,
        val sourceId: Long,
        val fileName: String,
        val hash: String,
        val rowsBefore: Int,
        val rowsAfter: Int,
        val fileBytes: Long,
    )

    /** 需要迁移的行数（0 表示已经迁移过 / 没有大词库） */
    suspend fun pendingCount(): Int = dictionaryDao.countBySource(SOURCE_IMPORTED)

    /**
     * 执行迁移。幂等：没有待迁移数据时直接返回 null。
     * 必须放在后台线程调用（会写文件 + 删几千～几十万行）。
     */
    suspend fun migrate(): Report? {
        val pending = dictionaryDao.countBySource(SOURCE_IMPORTED)
        if (pending <= 0) return null
        val dir = importDir
        if (dir == null) {
            Log.w(TAG, "没有可写目录，暂不迁移（保留 $pending 行）")
            return null
        }
        val rowsBefore = dictionaryDao.count()

        dir.mkdirs()
        val fileName = "imported_${System.currentTimeMillis()}.dict.txt"
        val file = File(dir, fileName)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0

        // 分页读 + 逐行写：内存占用只跟 PAGE_SIZE 有关
        try {
            file.bufferedWriter().use { writer ->
                var offset = 0
                while (true) {
                    val page = dictionaryDao.bySourcePaged(SOURCE_IMPORTED, PAGE_SIZE, offset)
                    if (page.isEmpty()) break
                    page.forEach { entry ->
                        if (entry.word.isEmpty()) return@forEach
                        val line = entry.word + "\t" + entry.code.orEmpty() + "\t" + entry.frequency + "\n"
                        writer.write(line)
                        digest.update(line.toByteArray(Charsets.UTF_8))
                        written++
                    }
                    offset += page.size
                    if (page.size < PAGE_SIZE) break
                }
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "写词库文件失败（原数据保留）", throwable)
            file.delete()
            return null
        }

        if (written != pending) {
            Log.w(TAG, "写入条数 $written != 原行数 $pending，放弃迁移（原数据保留）")
            file.delete()
            return null
        }

        val hash = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        val sourceId = sourceDao.upsert(
            DictionarySource(
                name = fileName,
                type = DictionarySourceType.IMPORTED,
                path = file.absolutePath,
                hash = hash,
                entryCount = written,
                sizeBytes = file.length(),
                enabled = true,
                format = DictionaryFormat.RIME_DICT.name,
            ),
        )

        val deleted = dictionaryDao.deleteBySource(SOURCE_IMPORTED)
        val rowsAfter = dictionaryDao.count()

        Log.i(
            TAG,
            "词库迁移完成：$deleted 行 → $fileName（$written 条, ${file.length()} B, hash=${hash.take(12)}）；" +
                "词条 $rowsBefore → $rowsAfter",
        )
        return Report(
            movedEntries = deleted,
            sourceId = sourceId,
            fileName = fileName,
            hash = hash,
            rowsBefore = rowsBefore,
            rowsAfter = rowsAfter,
            fileBytes = file.length(),
        )
    }

    companion object {
        private const val TAG = "BoardDictMigrate"

        /** 旧导入实现写入的标记 */
        const val SOURCE_IMPORTED = "imported"

        /** 超过这个条数的导入走「数据源文件」，不再灌 Room */
        const val LARGE_IMPORT_THRESHOLD = 2_000

        /** 分页大小：避免一次把几十万行读进内存 */
        private const val PAGE_SIZE = 2_000
    }
}
