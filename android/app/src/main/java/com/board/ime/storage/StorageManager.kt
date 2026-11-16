package com.board.ime.storage

import android.content.Context
import android.util.Log
import com.board.ime.database.dao.ClipboardDao
import com.board.ime.database.dao.DictionarySourceDao
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 存储分类里的一项 */
data class StorageItem(
    val label: String,
    val bytes: Long,
    val note: String = "",
)

/** 无引用 / 可回收的文件 */
data class ReclaimableFile(
    val path: String,
    val bytes: Long,
    val reason: String,
    /** 属于哪一类操作：CACHE / SYNC_TEMP / OLD_DICTIONARY / CLIPBOARD_DELETED */
    val kind: Kind,
) {
    enum class Kind { CACHE, SYNC_TEMP, OLD_DICTIONARY, CLIPBOARD_DELETED }
}

/** 内容相同的重复文件 */
data class DuplicateGroup(
    val hash: String,
    val label: String,
    val files: List<Pair<String, Long>>,
) {
    val wastedBytes: Long get() = files.drop(1).sumOf { it.second }
}

data class StorageReport(
    val apkBytes: Long,
    val items: List<StorageItem>,
    val reclaimable: List<ReclaimableFile>,
    val duplicates: List<DuplicateGroup>,
) {
    val dataBytes: Long get() = items.sumOf { it.bytes }
    val totalBytes: Long get() = apkBytes + dataBytes

    fun reclaimableOf(kind: ReclaimableFile.Kind): List<ReclaimableFile> =
        reclaimable.filter { it.kind == kind }

    fun bytesOf(kind: ReclaimableFile.Kind): Long = reclaimableOf(kind).sumOf { it.bytes }

    val reclaimableBytes: Long get() = reclaimable.sumOf { it.bytes }
    val duplicateBytes: Long get() = duplicates.sumOf { it.wastedBytes }
}

data class CleanResult(val removedFiles: Int, val freedBytes: Long) {
    val summary: String
        get() = if (removedFiles == 0) "没有可清理的内容" else "已清理 $removedFiles 个文件，释放 ${freedBytes / 1024 / 1024} MB"
}

/**
 * 存储审计 + 清理。
 *
 * ## 所有权规则（最重要的一条）
 *
 * **先判定「这个文件是谁的、有没有人引用」，再决定能不能删。** 具体：
 *
 * | 路径 | 归属 | 是否可清理 |
 * |---|---|---|
 * | `rime/shared/` | Rime 必需资源（从 APK 解出） | **永不** |
 * | `rime/user/build/` | Rime 部署产物 | **永不**（删了要重新部署） |
 * | `rime/user/` 下的 userdb 目录 | **用户学习数据** | **永不** |
 * | `rime/user/` 下的 yaml 配置 | Rime 配置 | **永不** |
 * | `rime/user/imported/` 里的数据源文件 | 用户导入的词库 | 仅当没有任何 dictionary_source 引用 |
 * | `rime/user/imported/` 里的 `.dict.yaml.bak` | 部署回滚备份 | 可（没有 deploy 就不需要） |
 * | `clipboard/` | 剪贴板正文 | 仅当对应记录已软删除或不存在 |
 * | `no_backup/` | WorkManager 队列 | 不在这里动（由它自己管理） |
 * | `cache/` 与 `crash.log` | 缓存/日志 | 可清 |
 *
 * 任何情况下都**不会**删除用户词库、学习数据、收藏、仍在使用的剪贴板。
 */
class StorageManager(
    context: Context,
    private val sourceDao: DictionarySourceDao?,
    private val clipboardDao: ClipboardDao?,
) {

    private val appContext = context.applicationContext
    private val dataDir: File get() = appContext.filesDir.parentFile ?: appContext.filesDir
    private val filesDir: File get() = appContext.filesDir
    private val importedDir: File get() = File(File(filesDir, "rime/user"), "imported")
    private val clipboardDir: File get() = File(filesDir, "clipboard")

    // ---------------------------------------------------------------- 审计

    suspend fun audit(): StorageReport = withContext(Dispatchers.IO) {
        val apk = runCatching { File(appContext.applicationInfo.sourceDir).length() }.getOrDefault(0L)

        val shared = sizeOf(File(filesDir, "rime/shared"))
        val build = sizeOf(File(filesDir, "rime/user/build"))
        val learned = userDbDirs().sumOf { sizeOf(it) } +
            (listSafely(File(filesDir, "rime/user"))
                .filter { it.isFile && it.extension == "yaml" }
                .sumOf { it.length() })
        val dictionaries = sizeOf(importedDir)
        val clipboard = sizeOf(clipboardDir)
        val sync = sizeOf(File(dataDir, "no_backup"))
        val cache = sizeOf(File(dataDir, "cache")) + crashLogBytes()
        val database = sizeOf(File(dataDir, "databases"))

        val items = listOf(
            StorageItem("应用资源（Rime 资源）", shared, "schema / dictionary / opencc"),
            StorageItem("Rime 部署产物", build, "table.bin / prism.bin，删了要重新部署"),
            StorageItem("学习数据", learned, "librime userdb（用户学习词）"),
            StorageItem("词库", dictionaries, "导入的大词库 + Rime 工作文件"),
            StorageItem("剪贴板", clipboard, "超长文本的正文文件"),
            StorageItem("同步数据", sync, "WorkManager 队列"),
            StorageItem("数据库", database, "board.db（含 WAL）"),
            StorageItem("缓存 / 临时", cache, "cache 目录 + crash.log"),
        )

        StorageReport(
            apkBytes = apk,
            items = items,
            reclaimable = findReclaimable(),
            duplicates = findDuplicatesInternal(),
        )
    }

    private fun userDbDirs(): List<File> =
        listSafely(File(filesDir, "rime/user"))
            .filter { it.isDirectory && it.name.endsWith(".userdb") }

    /**
     * 安全列目录。librime 会在 `cache/rime/log/` 写非 UTF-8 的文件名，
     * 而 `File.listFiles()` 走 `UnixFileSystem.list0` + `NewStringUTF`，在 CheckJNI 下会
     * 直接 abort 整个进程（点「高级」秒退的根因）。NIO 的 DirectoryStream 按字节读目录项，不会崩。
     */
    private fun listSafely(dir: File): List<File> =
        if (!dir.isDirectory) {
            emptyList()
        } else {
            runCatching {
                Files.newDirectoryStream(dir.toPath()).use { stream ->
                    stream.map { it.toFile() }
                }
            }.getOrDefault(emptyList())
        }

    private fun crashLogBytes(): Long =
        File(filesDir, "crash.log").takeIf { it.isFile }?.length() ?: 0L

    private fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        listSafely(dir).forEach { child ->
            total += if (child.isDirectory) sizeOf(child) else child.length()
        }
        return total
    }

    // ---------------------------------------------------------------- 可回收文件

    private suspend fun findReclaimable(): List<ReclaimableFile> {
        val reclaimable = ArrayList<ReclaimableFile>()

        // 1) 词库：未被任何 dictionary_source 引用的数据源文件 / Rime 工作文件 / .bak
        val sources = runCatching { sourceDao?.all() }.getOrNull().orEmpty()
        val referenced = sources.map { File(it.path).absolutePath }.toSet()
        // 当前**有效**的 Rime 工作文件名：按精确名字比对。
        // 不能用 `sourceId-` 前缀判断 —— 旧的双前缀遗留文件（a-b-xxx.dict.yaml）
        // 也会命中前缀，结果被误判成「仍在使用」而永远清不掉（实测踩过）。
        val liveRimeNames = sources
            .map { com.board.ime.dictionary.RimeDictionaryBridge.rimeDictName(it) }
            .toSet()

        listSafely(importedDir).forEach { entry ->
            if (entry.isDirectory) {
                // 数据源目录：里面没有 GB 级文件是正常的（未下载过只是空目录）
                listSafely(entry).forEach { file ->
                    if (File(file.absolutePath).absolutePath !in referenced) {
                        reclaimable += ReclaimableFile(
                            path = file.absolutePath,
                            bytes = file.length(),
                            reason = "数据源文件已无 dictionary_source 引用",
                            kind = ReclaimableFile.Kind.OLD_DICTIONARY,
                        )
                    }
                }
            } else if (entry.isFile) {
                val name = entry.name
                when {
                    name.endsWith(".dict.yaml.bak") -> reclaimable += ReclaimableFile(
                        entry.absolutePath,
                        entry.length(),
                        "Rime 词典回滚备份（部署结束就应删除）",
                        ReclaimableFile.Kind.SYNC_TEMP,
                    )

                    name.endsWith(".dict.yaml") && name !in liveRimeNames -> reclaimable += ReclaimableFile(
                        entry.absolutePath,
                        entry.length(),
                        "旧命名遗留的 Rime 词典（已被新名字取代）",
                        ReclaimableFile.Kind.OLD_DICTIONARY,
                    )
                }
            }
        }

        // 2) 剪贴板：记录已软删除 / 记录不存在 → 正文文件可清
        val liveClipboard = runCatching { clipboardDao?.allFilePaths() }.getOrNull().orEmpty()
            .map { File(it).absolutePath }.toSet()
        val deletedClipboard = runCatching { clipboardDao?.deletedFilePaths() }.getOrNull().orEmpty()
            .map { File(it).absolutePath }.toSet()
        listSafely(clipboardDir).forEach { bucket ->
            listSafely(bucket).forEach { file ->
                val path = file.absolutePath
                when {
                    path in deletedClipboard -> reclaimable += ReclaimableFile(
                        path,
                        file.length(),
                        "剪贴板记录已删除，正文文件应一并回收",
                        ReclaimableFile.Kind.CLIPBOARD_DELETED,
                    )

                    path !in liveClipboard -> reclaimable += ReclaimableFile(
                        path,
                        file.length(),
                        "没有任何剪贴板记录引用（孤儿文件）",
                        ReclaimableFile.Kind.CLIPBOARD_DELETED,
                    )
                }
            }
        }

        // 3) 缓存 / 日志
        File(filesDir, "crash.log").takeIf { it.isFile && it.length() > 0 }?.let {
            reclaimable += ReclaimableFile(
                it.absolutePath,
                it.length(),
                "崩溃日志（可重建）",
                ReclaimableFile.Kind.CACHE,
            )
        }
        listSafely(File(dataDir, "cache")).forEach { entry ->
            if (entry.name == "board.db.lck") return@forEach
            val bytes = if (entry.isDirectory) sizeOf(entry) else entry.length()
            if (bytes > 0) {
                reclaimable += ReclaimableFile(
                    entry.absolutePath,
                    bytes,
                    "缓存目录内容（可重建）",
                    ReclaimableFile.Kind.CACHE,
                )
            }
        }

        return reclaimable.sortedByDescending { it.bytes }
    }

    // ---------------------------------------------------------------- 重复检测

    private suspend fun findDuplicatesInternal(): List<DuplicateGroup> {
        val groups = ArrayList<DuplicateGroup>()

        // 词库目录：按内容哈希分组（同内容不同文件名 = 重复）
        val byHash = HashMap<String, MutableList<Pair<String, Long>>>()
        listSafely(importedDir).forEach { entry ->
            val files = if (entry.isDirectory) listSafely(entry) else listOf(entry)
            files.filter { it.isFile && it.length() > 64 * 1024 }.forEach { file ->
                val hash = com.board.ime.clipboard.ClipboardHasher.hashFile(file)
                if (hash.isNotEmpty()) {
                    byHash.getOrPut(hash) { ArrayList() } += file.absolutePath to file.length()
                }
            }
        }
        byHash.forEach { (hash, files) ->
            if (files.size > 1) {
                groups += DuplicateGroup(
                    hash = hash,
                    label = "词库目录里有 ${files.size} 个内容完全相同的文件",
                    files = files,
                )
            }
        }

        // 数据库里同一个 hash 被登记了多条数据源
        val sources = runCatching { sourceDao?.all() }.getOrNull().orEmpty()
        sources.groupBy { it.hash }.filter { it.value.size > 1 }.forEach { (hash, rows) ->
            groups += DuplicateGroup(
                hash = hash,
                label = "database 里有 ${rows.size} 条数据源指向同一份内容",
                files = rows.map { "database#${it.id} ${it.name}" to it.sizeBytes },
            )
        }
        return groups
    }

    // ---------------------------------------------------------------- 清理

    /** 清理缓存 / 日志（**绝不**碰词库、学习数据、剪贴板） */
    suspend fun cleanCache(): CleanResult = clean(ReclaimableFile.Kind.CACHE)

    /** 清理同步 / 部署留下的临时文件（`.bak` 等） */
    suspend fun cleanSyncTemp(): CleanResult = clean(ReclaimableFile.Kind.SYNC_TEMP)

    /** 清理已无引用的旧词库文件 */
    suspend fun cleanOldDictionaries(): CleanResult = clean(ReclaimableFile.Kind.OLD_DICTIONARY)

    /** 回收已删除剪贴板记录对应的正文文件 */
    suspend fun cleanDeletedClipboard(): CleanResult = clean(ReclaimableFile.Kind.CLIPBOARD_DELETED)

    /** 把「检查重复词库」的结果转成可读文本 */
    suspend fun duplicateReport(): List<DuplicateGroup> =
        withContext(Dispatchers.IO) { findDuplicatesInternal() }

    private suspend fun clean(kind: ReclaimableFile.Kind): CleanResult = withContext(Dispatchers.IO) {
        val targets = findReclaimable().filter { it.kind == kind }
        var removed = 0
        var freed = 0L
        targets.forEach { target ->
            // 二次确认：只允许删除白名单目录下的文件（防止误删 Rime 必需文件）
            if (!isSafeToDelete(target.path)) {
                Log.w(TAG, "跳过不在白名单内的路径：${target.path}")
                return@forEach
            }
            val file = File(target.path)
            val bytes = if (file.isDirectory) sizeOf(file) else file.length()
            val ok = runCatching { deleteTree(file) }.getOrDefault(false)
            if (ok) {
                removed++
                freed += bytes
            }
        }
        Log.i(TAG, "清理 $kind：删除 $removed 项，释放 $freed 字节")
        CleanResult(removed, freed)
    }

    /** 删除目录树；同样避开 File.listFiles()（非 UTF-8 文件名会让进程 abort） */
    private fun deleteTree(file: File): Boolean {
        if (!file.isDirectory) return file.delete() || !file.exists()
        Files.walkFileTree(
            file.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(path)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return !file.exists()
    }

    /**
     * 删除白名单：只允许清理「缓存」和「词库目录里无引用的文件」。
     *
     * `rime/shared`、`rime/user/build`、`*.userdb`、`no_backup`、`databases` 一律拒绝。
     */
    private fun isSafeToDelete(path: String): Boolean {
        val normalized = File(path).absolutePath
        val allowedRoots = listOf(
            File(dataDir, "cache").absolutePath,
            File(filesDir, "crash.log").absolutePath,
            importedDir.absolutePath,
            clipboardDir.absolutePath,
        )
        if (allowedRoots.none { normalized == it || normalized.startsWith("$it${File.separator}") }) {
            return false
        }
        // 词库里绝不能误删 Rime 必需的资源目录
        val forbidden = listOf("/rime/shared/", "/rime/user/build/", ".userdb/")
        return forbidden.none { normalized.contains(it) }
    }

    private companion object {
        const val TAG = "BoardStorage"
    }
}
