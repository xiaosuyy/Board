package com.board.ime.dictionary

import android.content.Context
import android.util.Log
import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.database.BoardDatabase
import com.board.ime.database.dao.DictionaryDao
import com.board.ime.database.dao.DictionarySourceDao
import com.board.ime.database.entity.DictionaryEntry
import com.board.ime.engine.rime.RimeDataInstaller
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.sync.SyncWordMerge
import com.board.ime.sync.SyncWordRecord
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** 导入结果 */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    /** 走「大型词库数据源」时：数据源文件名 */
    val sourceName: String? = null,
    /** 走「大型词库数据源」时的条目数 */
    val sourceEntries: Int = 0,
) {
    val summary: String
        get() = if (sourceName != null) {
            "已作为词库数据源接入：$sourceName（$sourceEntries 条）\n" +
                "无法解析 $failed 条；数据源文件保存在应用私有目录，不占用用户词表"
        } else {
            "成功导入 $imported 条，跳过重复 $skipped 条，无法解析 $failed 条"
        }
}

/** 导出范围 */
enum class ExportScope(val label: String) {
    ALL("全部词条"),
    USER("用户词（自定义 + 固定 + 屏蔽）"),
    LEARNED("学习词"),
    ;

    companion object {
        fun types(scope: ExportScope): List<WordType> = when (scope) {
            ALL -> WordType.entries.toList()
            USER -> listOf(WordType.CUSTOM, WordType.PINNED, WordType.BLOCKED)
            LEARNED -> listOf(WordType.LEARNED)
        }
    }
}

/**
 * 词库仓库。
 *
 * 两层含义：
 * - **Board 侧**：`dictionary_entry` 表就是「我的词库」，支持增删改查、导入导出；
 * - **Rime 侧**：自定义词 / 固定词会同步进 `<user_data_dir>/custom_phrase.txt`（Rime 标准机制，
 *   优先级高于普通词库）；学习词是从 librime 的 userdb 导出后的只读镜像。
 *
 * 学习本身由 librime 完成（用户选词 → userdb），这里只负责镜像、统计、导出与清空。
 */
class DictionaryRepository(
    private val dao: DictionaryDao,
    context: Context? = null,
    /** 大型词库数据源（可选：老调用点不传也能工作） */
    private val sourceDao: DictionarySourceDao? = null,
    /** 数据库本体（VACUUM 回收空间用；可选） */
    private val database: BoardDatabase? = null,
) {

    /** 旧种子词清理标记（每个进程一次） */
    @Volatile
    private var legacyCleaned = false

    /**
     * 用户数据目录。
     *
     * 设置页可能在输入法服务启动之前打开，此时 [RimeRuntime] 还没初始化，
     * 所以这里优先用 Context 直接算出来，保证学习记录统计 / 自定义短语同步都能用。
     */
    private val installerUserDir: File? = context?.let { RimeDataInstaller(it).userDir() }

    private fun userDir(): File? = installerUserDir ?: RimeRuntime.userDataDir()

    // ---------------------------------------------------------------- 查询

    fun observeAll(limit: Int = DEFAULT_LIMIT): Flow<List<DictionaryEntry>> = dao.observeAll(limit)

    fun observeByType(type: WordType, limit: Int = DEFAULT_LIMIT): Flow<List<DictionaryEntry>> =
        dao.observeByType(type, limit)

    suspend fun countAll(): Int = dao.count()

    suspend fun countByType(type: WordType): Int = dao.countByType(type)

    suspend fun findByWord(word: String): DictionaryEntry? = dao.findByWord(word.trim())

    suspend fun blockedWords(): Set<String> = dao.wordsByType(WordType.BLOCKED).toSet()

    // ---------------------------------------------------------------- 增删改

    /** @return true 表示新增成功（已存在同名词条时返回 false，不覆盖） */
    suspend fun addWord(
        word: String,
        code: String? = null,
        type: WordType = WordType.CUSTOM,
        frequency: Int = 0,
    ): Boolean {
        val normalized = word.trim()
        if (normalized.isEmpty()) return false
        if (dao.findByWord(normalized) != null) return false
        dao.insert(
            DictionaryEntry(
                word = normalized,
                code = code?.trim()?.takeIf { it.isNotEmpty() },
                frequency = frequency,
                type = type,
                source = SOURCE_LOCAL,
            ),
        )
        return true
    }

    suspend fun updateWord(
        id: Long,
        word: String,
        code: String?,
        frequency: Int,
        type: WordType,
        enabled: Boolean,
    ): Boolean {
        val normalized = word.trim()
        if (normalized.isEmpty()) return false
        dao.updateFields(
            id = id,
            word = normalized,
            code = code?.trim()?.takeIf { it.isNotEmpty() },
            frequency = frequency,
            type = type,
            enabled = enabled,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    /** 软删除（墓碑）：列表里看不到，但能把「删除」同步到别的设备 */
    suspend fun delete(entry: DictionaryEntry) {
        val now = System.currentTimeMillis()
        dao.markDeleted(id = entry.id, deletedAt = now, updatedAt = now)
    }

    suspend fun deleteById(id: Long): Int = dao.deleteById(id)

    suspend fun deleteByType(type: WordType): Int = dao.deleteByType(type)

    // ---------------------------------------------------------------- 同步（词库 / 学习词 / 常用语）

    /**
     * 导出给同步用的记录（**含墓碑**，删除才能传播）。
     *
     * 只导出用户勾选的类型；`BLOCKED` 属于用户词库的一部分。
     */
    suspend fun exportRecords(types: List<WordType>): List<SyncWordRecord> =
        dao.exportAll(types).map { it.toSyncRecord() }

    /**
     * 把远端记录合并进本地（**合并，不是覆盖**）。
     *
     * 规则见 [SyncWordMerge]：同词不同拼音算两条、词频取最大、墓碑优先（新版），
     * 删除过的词条不会被另一端重新恢复。
     *
     * @return 写入 / 更新 / 墓碑的总条数
     */
    suspend fun mergeRecords(remote: List<SyncWordRecord>): Int {
        if (remote.isEmpty()) return 0
        // 安全阀：远端文件异常（例如误把整份词典当成同步文件）时不要往本地灌几十万条，
        // 直接拒绝并记日志，避免「词条数量爆炸」这种不可逆的破坏。
        if (remote.size > MAX_MERGE_RECORDS) {
            Log.w(
                TAG,
                "拒绝合并：远端记录数 ${remote.size} 超过上限 $MAX_MERGE_RECORDS，疑似文件异常",
            )
            return 0
        }
        val local = dao.exportAll(SYNC_TYPES).map { it.toSyncRecord() }
        val merged = SyncWordMerge.merge(local, remote)
        var touched = 0
        merged.forEach { record ->
            val type = WordType.fromName(record.type)
            val existing = dao.findByWordAny(record.word)
            if (existing == null) {
                if (!record.deleted) {
                    dao.insert(record.toEntity())
                    touched++
                }
                return@forEach
            }
            // 本地存在：只有在「合并结果与本地不同」时才写，避免无意义刷新
            if (existing.frequency == record.frequency &&
                existing.code == record.code &&
                existing.enabled == record.enabled &&
                existing.isDeleted == record.deleted &&
                existing.type == type
            ) {
                return@forEach
            }
            dao.updateFromSync(
                id = existing.id,
                code = record.code,
                frequency = record.frequency,
                type = type,
                enabled = record.enabled,
                isDeleted = record.deleted,
                deletedAt = if (record.deleted) record.updatedAt else null,
                source = SOURCE_SYNCED,
                sourceDeviceId = record.sourceDeviceId,
                updatedAt = record.updatedAt,
            )
            touched++
        }
        return touched
    }

    private fun DictionaryEntry.toSyncRecord(): SyncWordRecord = SyncWordRecord(
        word = word,
        code = code,
        frequency = frequency,
        type = type.name,
        enabled = enabled,
        deleted = isDeleted,
        sourceDeviceId = sourceDeviceId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun SyncWordRecord.toEntity(): DictionaryEntry = DictionaryEntry(
        word = word,
        code = code,
        frequency = frequency,
        type = WordType.fromName(type),
        enabled = enabled,
        source = SOURCE_SYNCED,
        sourceDeviceId = sourceDeviceId,
        isDeleted = deleted,
        deletedAt = if (deleted) updatedAt else null,
        createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
        updatedAt = if (updatedAt > 0L) updatedAt else System.currentTimeMillis(),
    )

    // ---------------------------------------------------------------- 导入 / 导出

    /**
     * 导入词条：按 word 去重，**不覆盖已有词条**。
     * @throws DictionaryFormatException 文件内容与所选格式不符
     */
    suspend fun import(content: String, format: DictionaryFormat, defaultType: WordType): ImportResult {
        val outcome = DictionaryCodec.parse(content, format)
        val existing = dao.allWords().toHashSet()
        val pending = ArrayList<DictionaryEntry>()
        var skipped = 0

        outcome.entries.forEach { parsed ->
            val word = parsed.word.trim()
            if (word.isEmpty()) return@forEach
            if (!existing.add(word)) {
                skipped++
                return@forEach
            }
            pending += DictionaryEntry(
                word = word,
                code = parsed.code?.trim()?.takeIf { it.isNotEmpty() },
                frequency = parsed.frequency,
                type = parsed.type ?: defaultType,
                // 小词表导入后就是「用户自己的词」：用 local，
                // 不能再标成 legacy 的 'imported'（那个标记只属于历史 60 万行，
                // 会被迁移逻辑搬走、也会被同步导出排除）
                source = SOURCE_LOCAL,
            )
        }

        if (pending.isNotEmpty()) {
            dao.insertAll(pending)
        }
        // 学习词额外写回 librime 用户词典：
        // 这样「导出 → 清空学习记录 → 再导入」能真正恢复候选排序，而不只是本地列表。
        val learned = pending.filter { it.type == WordType.LEARNED }
        if (learned.isNotEmpty()) {
            importLearnedIntoRime(learned)
        }
        Log.i(TAG, "导入完成：${pending.size} 条，跳过 $skipped，失败 ${outcome.failed}")
        return ImportResult(
            imported = pending.size,
            skipped = skipped,
            failed = outcome.failed,
        )
    }

    /** [scope] 决定导出哪些词 */
    suspend fun export(scope: ExportScope, format: DictionaryFormat): String {
        val types = ExportScope.types(scope)
        val entries = types.flatMap { dao.byType(it) }.sortedWith(
            compareBy({ it.type.ordinal }, { it.word }),
        )
        return DictionaryCodec.serialize(entries, format)
    }

    suspend fun exportCount(scope: ExportScope): Int =
        ExportScope.types(scope).sumOf { dao.countByType(it) }

    // ---------------------------------------------------------------- 大型词库 / 迁移

    private val migrationMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 历史遗留的大型导入词条 → [DictionarySource]（幂等，可在启动/打开设置时重复调用）。
     *
     * 注意：服务启动和设置页可能**同时**触发，所以这里用 Mutex 串行化，
     * 否则会写出两份文件、插两条 source。
     *
     * @return 迁移报告；没有待迁移数据时返回 null
     */
    suspend fun migrateLegacyImportedEntries(): DictionarySourceMigrator.Report? {
        val sources = sourceDao ?: return null
        if (!migrationMutex.tryLock()) return null
        try {
            val dir = userDir()?.let { File(it, "imported") }
            val report = runCatching {
                DictionarySourceMigrator(dao, sources, dir).migrate()
            }.getOrElse { throwable ->
                Log.e(TAG, "词库迁移失败（原数据保留）", throwable)
                null
            }
            // 迁移后立即回收空间：SQLite 删行不会自动缩小文件，不 VACUUM 的话
            // 数据库文件仍然占着那 100MB
            if (report != null) {
                runCatching { vacuum() }.onFailure { Log.w(TAG, "VACUUM 失败：${it.message}") }
            }
            dropDuplicateSources(sources)
            // 老数据（v7 之前）没有 sourceId，顺手补齐（幂等）
            ensureSourceIds()
            return report
        } finally {
            migrationMutex.unlock()
        }
    }

    /** 同名 / 同 hash 的重复数据源只留一条（并发迁移可能产生重复） */
    private suspend fun dropDuplicateSources(sources: DictionarySourceDao) {
        runCatching {
            val seen = HashSet<String>()
            sources.all().forEach { source ->
                val key = source.hash.ifEmpty { source.path }
                if (!seen.add(key)) {
                    sources.deleteById(source.id)
                    Log.i(TAG, "删除重复词库数据源：${source.name}")
                }
            }
        }
    }

    /** 回收 SQLite 空闲页（真机上 111MB → 迁移后需要它才能真正变小） */
    private suspend fun vacuum() {
        val db = database ?: return
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("VACUUM")
        }
        Log.i(TAG, "数据库 VACUUM 完成")
    }

    /** 待迁移的历史导入行数 */
    suspend fun legacyImportedCount(): Int = dao.countBySource(SOURCE_IMPORTED)

    /** 词库数据源数量（导入的大词库） */
    suspend fun sourceCount(): Int = sourceDao?.count() ?: 0

    /** 导入词库数据源列表（用于设置页展示具体文件 / 大小 / 是否 ready） */
    suspend fun importedSources(): List<DictionarySource> =
        sourceDao?.allByType(DictionarySourceType.IMPORTED) ?: emptyList()

    /**
     * 给老数据补 `sourceId`（v6→v7 迁移时留空）。
     *
     * `sourceId` 由名字派生，两台设备对同一个词库得到同一个值，云端目录才对得上。
     * 幂等，可以在每次启动 / 打开设置页时调用。
     */
    suspend fun ensureSourceIds(): Int {
        val sources = sourceDao ?: return 0
        var fixed = 0
        runCatching {
            sources.all().forEach { source ->
                val expected = stableSourceId(source.name)
                if (source.sourceId != expected) {
                    sources.updateMeta(
                        id = source.id,
                        sourceId = expected,
                        hash = source.hash,
                        entryCount = source.entryCount,
                        sizeBytes = source.sizeBytes,
                        version = source.version,
                        path = source.path,
                        updatedAt = source.updatedAt,
                    )
                    fixed++
                }
            }
        }.onFailure { Log.w(TAG, "补齐词库 sourceId 失败：${it.message}") }
        return fixed
    }

    /**
     * 把一个导入词库接入 Rime：
     * 生成 `.dict.yaml` → （规模允许时）deploy → 成功才标记 ready。
     *
     * 失败时**不影响原有可用词库**（RimeDictionaryBridge 会回滚 / 保留）。
     *
     * @return 是否已经 ready
     */
    suspend fun installSourceIntoRime(source: DictionarySource): Boolean {
        val dir = userDir() ?: return false
        val imported = importedDir() ?: return false
        val sources = sourceDao ?: return false

        if (!RimeDictionaryBridge.canAutoDeploy(source)) {
            Log.i(TAG, "词库 ${source.name}（${source.entryCount} 条）超过自动部署上限，等待手动部署")
            return false
        }
        if (!RimeDictionaryBridge.isRimeReady()) {
            Log.i(TAG, "Rime 未初始化，词库保持压缩存储待部署：${source.name}")
            return false
        }

        // 1) schema 挂接（额外词源，不动原来的 luna_pinyin）
        val live = sources.allByType(DictionarySourceType.IMPORTED)
        if (!RimeDictionaryBridge.applySchemaPatch(dir, live)) {
            Log.w(TAG, "schema patch 未就位，跳过部署：${source.name}")
            return false
        }
        // 2) 解压临时工作文件
        val staged = withContext(Dispatchers.IO) {
            RimeDictionaryBridge.stageForDeploy(dir, source, imported)
        } ?: return false

        // 3) full deploy：schema 变了必须整体重建
        val deployed = withContext(Dispatchers.IO) { RimeRuntime.deploy(fullCheck = true) }
        return finishSourceDeploy(source, dir, staged, deployed)
    }

    /**
     * 部署收尾。
     *
     * **只有 deploy 成功、并且确认 `<逻辑名>.table.bin` 真的生成了**，
     * 才删临时 `.dict.yaml` 并把 `ready` 置 1；
     * 否则保留 gz、删掉临时文件、`ready` 保持 0（不假装词库已生效）。
     */
    private suspend fun finishSourceDeploy(
        source: DictionarySource,
        dir: File,
        staged: File,
        deployed: Boolean,
    ): Boolean {
        val sources = sourceDao ?: return false
        val table = RimeDictionaryBridge.tableFile(dir, source)
        val tableReady = deployed && table.isFile && table.length() > 0L
        // 无论成败都清临时文件（gz 永久保留）
        runCatching { staged.delete() }
        sources.setReady(source.id, tableReady, source.version, System.currentTimeMillis())
        if (tableReady) {
            Log.i(TAG, "词库已接入 Rime：${source.name}（table=${table.name} ${table.length()} B）")
        } else {
            Log.w(
                TAG,
                "词库未生效（deploy=$deployed table=${table.isFile}），已清理临时文件并保留 gz：${source.name}",
            )
        }
        return tableReady
    }

    /**
     * 手动部署全部导入词库（大词库不自动部署，用户点按钮走这里）。
     * @return (ready 数量, 总数)
     */
    suspend fun deployImportedSources(): Pair<Int, Int> {
        val sources = sourceDao ?: return 0 to 0
        val dir = userDir() ?: return 0 to 0
        val imported = importedDir() ?: return 0 to 0
        if (!RimeDictionaryBridge.isRimeReady()) return 0 to 0
        return withContext(Dispatchers.IO) {
            val list = sources.allByType(DictionarySourceType.IMPORTED)
            if (list.isEmpty()) return@withContext 0 to 0

            // 1) schema 挂接：把大型词典作为额外词源写进 luna_pinyin_simp.custom.yaml
            if (!RimeDictionaryBridge.applySchemaPatch(dir, list)) {
                Log.w(TAG, "schema patch 未就位（存在用户自己的 custom.yaml？），本次不部署")
                return@withContext 0 to list.size
            }

            // 2) 部署前临时展开 gz（流式，头部会重写成逻辑 dictionary 名）
            val staged = HashMap<Long, File>()
            list.forEach { source ->
                RimeDictionaryBridge.stageForDeploy(dir, source, imported)?.let { staged[source.id] = it }
            }
            if (staged.isEmpty()) {
                Log.w(TAG, "没有可用的数据源，跳过部署")
                return@withContext 0 to list.size
            }

            // 3) full deploy：schema 变了必须整体重建
            val started = System.currentTimeMillis()
            val deployed = RimeRuntime.deploy(fullCheck = true)
            val cost = System.currentTimeMillis() - started

            // 4) 只有 table.bin 真的生成才算成功
            var ready = 0
            list.forEach { source ->
                val table = RimeDictionaryBridge.tableFile(dir, source)
                val ok = deployed && staged.containsKey(source.id) && table.isFile && table.length() > 0L
                if (ok) ready++
                // 5) 无论成败都清理临时解压文件；gz 永久保留
                staged[source.id]?.let { runCatching { it.delete() } }
                sources.setReady(source.id, ok, source.version, System.currentTimeMillis())
                if (ok) {
                    Log.i(TAG, "部署成功：${source.name} table=${table.name}（${table.length()} B）")
                } else {
                    Log.w(
                        TAG,
                        "部署未生效：${source.name} deploy=$deployed table=${table.isFile} " +
                            "（gz 与 build/ 均未改动）",
                    )
                }
            }
            Log.i(
                TAG,
                "手动部署完成：ready=$ready/${list.size} 耗时=${cost}ms，临时 .dict.yaml 已清理",
            )
            ready to list.size
        }
    }

    /** 长期存储目录：`files/rime/user/imported` */
    fun importedDir(): File? = userDir()?.let { File(it, "imported") }

    /** 由名字派生稳定 sourceId（sha256 前 16 位） */
    fun stableSourceId(name: String): String = ClipboardHasher.hash(name.trim()).take(16)

    fun observeSources(): Flow<List<DictionarySource>> =
        sourceDao?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * 导入入口：大词库走「文件 + 元信息」，小词表才进 Room。
     * 规模由 [DictionarySourceMigrator.LARGE_IMPORT_THRESHOLD] 决定。
     */
    suspend fun importIntoStore(
        content: String,
        format: DictionaryFormat,
        defaultType: WordType = WordType.CUSTOM,
        sourceName: String,
    ): ImportResult {
        val parsed = DictionaryCodec.parse(content, format)
        val sourceDao = this.sourceDao
        if (parsed.entries.size > DictionarySourceMigrator.LARGE_IMPORT_THRESHOLD && sourceDao != null) {
            return saveAsSource(parsed, format, sourceName, sourceDao)
        }
        return import(content, format, defaultType)
    }

    /** 大词库：只写文件 + 一行元信息，**不进 Room** */
    private suspend fun saveAsSource(
        parsed: ParseOutcome,
        format: DictionaryFormat,
        sourceName: String,
        sources: DictionarySourceDao,
    ): ImportResult {
        val dir = importedDir() ?: return ImportResult(0, 0, parsed.failed)
        dir.mkdirs()
        val displayName = sourceName.ifBlank { "imported_${System.currentTimeMillis()}" } + ".dict.yaml"

        // 先用临时文件写出「带 YAML 头的完整 .dict.yaml」并算内容 hash，
        // 再压缩成 <hash>.dict.yaml.gz —— 临时文件在 finally 里一定删掉。
        val tmpPlain = File(dir, "import-${System.currentTimeMillis()}.plain.tmp")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var written = 0
        try {
            tmpPlain.bufferedWriter().use { writer ->
                val header = buildString {
                    append("---\n")
                    append("name: ").append(displayName.removeSuffix(".dict.yaml")).append('\n')
                    append("version: \"1\"\n")
                    append("sort: by_weight\n")
                    append("use_preset_vocabulary: false\n")
                    append("columns:\n  - text\n  - code\n  - weight\n")
                    append("...\n")
                }
                writer.write(header)
                digest.update(header.toByteArray(Charsets.UTF_8))
                parsed.entries.forEach { entry ->
                    if (entry.word.isEmpty()) return@forEach
                    val line = buildString {
                        append(entry.word).append('\t')
                        append(entry.code.orEmpty()).append('\t')
                        append(entry.frequency).append('\n')
                    }
                    writer.write(line)
                    digest.update(line.toByteArray(Charsets.UTF_8))
                    written++
                }
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "写入大词库失败（未入库）", throwable)
            tmpPlain.delete()
            return ImportResult(0, 0, parsed.failed)
        }

        val hash = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        val plainBytes = tmpPlain.length()
        // 去重：内容相同就直接复用已有 gz，不再产生第二份物理数据
        val stored = DictionaryStore.writeCompressed(dir, hash, tmpPlain)
        tmpPlain.delete()
        if (stored == null) {
            return ImportResult(0, 0, parsed.failed)
        }

        // Room 里同一份内容最多一条 source
        val existingByHash = sources.all().firstOrNull { it.hash == hash }
        val existing = existingByHash ?: sources.findBySourceId(stableSourceId(displayName))
        sources.upsert(
            DictionarySource(
                id = existing?.id ?: 0L,
                sourceId = existing?.sourceId?.ifBlank { stableSourceId(displayName) }
                    ?: stableSourceId(displayName),
                name = existing?.name ?: displayName,
                type = DictionarySourceType.IMPORTED,
                path = stored.absolutePath,
                hash = hash,
                entryCount = written,
                sizeBytes = plainBytes,
                enabled = true,
                format = format.name,
                version = (existing?.version ?: 0L) + 1L,
                ready = existing?.ready ?: false,
            ),
        )
        Log.i(
            TAG,
            "大型词库已压缩存储：${stored.name}（$written 条，$plainBytes → ${stored.length()} B）",
        )
        return ImportResult(
            imported = 0,
            skipped = 0,
            failed = parsed.failed,
            sourceName = existing?.name ?: displayName,
            sourceEntries = written,
        )
    }

    // ---------------------------------------------------------------- gz 迁移

    /** 迁移结果（用于日志与设置页提示） */
    data class GzipMigrationReport(
        val migrated: Int = 0,
        val alreadyCompressed: Int = 0,
        val failed: Int = 0,
        val removedLegacyFiles: Int = 0,
        val plainBytes: Long = 0,
        val storedBytes: Long = 0,
    ) {
        val savedBytes: Long get() = (plainBytes - storedBytes).coerceAtLeast(0)
    }

    /**
     * 一次性把旧的长期存储（`.dict.yaml` / 裸 `.dict.txt`）迁移成 `<sha256>.dict.yaml.gz`。
     *
     * 安全铁律：**校验通过才删原文件**。
     * 1. 取「带 YAML 头的完整词典文本」（优先用已生成的 `.dict.yaml`，否则用数据源补头）
     * 2. 算内容 SHA-256 → 压成 `<hash>.dict.yaml.gz`
     * 3. **把 gz 解压回来再算一次 hash**，必须与原内容完全一致
     * 4. 通过后才把 DB 指向 gz，并删除旧文件；任何一步失败都保留原文件
     *
     * 已存在的 gz 直接复用（同内容不会产生第二份）。
     */
    suspend fun migrateToCompressedStore(): GzipMigrationReport {
        val sources = sourceDao ?: return GzipMigrationReport()
        val userDir = userDir() ?: return GzipMigrationReport()
        val dir = importedDir() ?: return GzipMigrationReport()
        return withContext(Dispatchers.IO) {
            var report = GzipMigrationReport()
            var plainTotal = 0L
            var storedTotal = 0L
            val list = sources.allByType(DictionarySourceType.IMPORTED)

            list.forEach { source ->
                if (DictionaryStore.isCompressedStore(source.path) && File(source.path).isFile) {
                    report = report.copy(alreadyCompressed = report.alreadyCompressed + 1)
                    plainTotal += source.sizeBytes
                    storedTotal += File(source.path).length()
                    return@forEach
                }

                val current = File(source.path)
                val legacyYaml = RimeDictionaryBridge.rimeDictFile(userDir, source)
                val legacyFiles = ArrayList<File>()
                val prepared = preparePlainContent(source, current, legacyYaml, dir, legacyFiles)
                if (prepared == null) {
                    Log.w(TAG, "迁移跳过（找不到可用的词典内容）：${source.name}")
                    report = report.copy(failed = report.failed + 1)
                    return@forEach
                }

                val hash = DictionaryStore.hashOf(prepared)
                val plainBytes = prepared.length()
                val stored = DictionaryStore.writeCompressed(dir, hash, prepared)
                prepared.delete()
                if (stored == null || !verifyCompressed(stored, hash)) {
                    // 校验失败：什么都不删
                    Log.w(TAG, "迁移校验失败，保留原文件：${source.name}")
                    report = report.copy(failed = report.failed + 1)
                    return@forEach
                }

                sources.updateMeta(
                    id = source.id,
                    sourceId = source.remoteId,
                    hash = hash,
                    entryCount = source.entryCount,
                    sizeBytes = plainBytes,
                    version = source.version,
                    path = stored.absolutePath,
                    updatedAt = System.currentTimeMillis(),
                )
                // 校验通过，才清理旧文件（含旧 .dict.yaml / .bak / 旧命名的 yaml）
                legacyFiles.filter { it.isFile }.forEach { file ->
                    if (file.delete()) report = report.copy(removedLegacyFiles = report.removedLegacyFiles + 1)
                }
                if (current.isFile && !DictionaryStore.isCompressedStore(current.path)) {
                    if (current.delete()) report = report.copy(removedLegacyFiles = report.removedLegacyFiles + 1)
                }
                plainTotal += plainBytes
                storedTotal += stored.length()
                report = report.copy(migrated = report.migrated + 1)
                Log.i(TAG, "已迁移为 gz：${stored.name}（$plainBytes → ${stored.length()} B）")
            }

            // 收尾：删掉无引用的旧命名 yaml 与 .bak（有引用的绝不动）
            val live = sources.allByType(DictionarySourceType.IMPORTED)
            val liveNames = live.map { RimeDictionaryBridge.rimeDictName(it) }.toSet()
            val livePaths = live.map { File(it.path).absolutePath }.toSet()
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.name
                val isLegacyYaml = name.endsWith(".dict.yaml") && name !in liveNames
                val isBackup = name.endsWith(".bak")
                val isUnreferencedSource = !DictionaryStore.isCompressedStore(name) &&
                    file.absolutePath !in livePaths &&
                    (name.endsWith(".dict.txt") || name.endsWith(".plain.tmp"))
                if (isLegacyYaml || isBackup || isUnreferencedSource) {
                    if (file.delete()) {
                        report = report.copy(removedLegacyFiles = report.removedLegacyFiles + 1)
                    }
                }
            }

            report = report.copy(plainBytes = plainTotal, storedBytes = storedTotal)
            if (report.migrated > 0 || report.removedLegacyFiles > 0) {
                Log.i(
                    TAG,
                    "词库压缩迁移完成：迁移 ${report.migrated}，已压缩 ${report.alreadyCompressed}，" +
                        "失败 ${report.failed}，清理旧文件 ${report.removedLegacyFiles}，" +
                        "体积 $plainTotal → $storedTotal",
                )
            }
            report
        }
    }

    /** 准备一份「带 YAML 头的完整词典文本」临时文件（临时文件由调用方删除） */
    private fun preparePlainContent(
        source: DictionarySource,
        current: File,
        legacyYaml: File,
        dir: File,
        legacyFiles: MutableList<File>,
    ): File? {
        // 1) 已生成的 .dict.yaml 就是最终内容（带头），直接用
        if (legacyYaml.isFile) {
            legacyFiles += legacyYaml
            legacyFiles += File(legacyYaml.absolutePath + ".bak")
            return legacyYaml
        }
        // 2) 旧的数据源（裸 词条\t编码\t词频）→ 补上 YAML 头
        if (!current.isFile) return null
        if (DictionaryStore.isCompressedStore(current.path)) return null
        val tmp = File(dir, "migrate-${source.id}.plain.tmp")
        return try {
            tmp.bufferedWriter().use { writer ->
                val header = buildString {
                    append("---\n")
                    append("name: ").append(RimeDictionaryBridge.baseName(source)).append('\n')
                    append("version: \"").append(source.version).append("\"\n")
                    append("sort: by_weight\n")
                    append("use_preset_vocabulary: false\n")
                    append("columns:\n  - text\n  - code\n  - weight\n")
                    append("...\n")
                }
                writer.write(header)
                current.bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (line.isNotBlank()) { writer.write(line); writer.write("\n") } }
                }
            }
            legacyFiles += current
            tmp
        } catch (throwable: Throwable) {
            Log.e(TAG, "准备迁移内容失败：${throwable.message}", throwable)
            tmp.delete()
            null
        }
    }

    /** 解压回来重新算 hash，必须与期望一致 */
    private fun verifyCompressed(file: File, expectedHash: String): Boolean =
        DictionaryStore.hashOfCompressed(file)?.equals(expectedHash, ignoreCase = true) == true

    // ---------------------------------------------------------------- 与 librime 联动

    /**
     * 把「自定义词 / 固定词」同步到 Rime 的 custom_phrase（标准 Rime 机制，输入时优先出现）。
     * @return 写入的条数
     */
    suspend fun syncCustomPhrasesToRime(): Int {
        val file = userDir()?.let { File(it, "${RimeRuntime.CUSTOM_PHRASE_DICT}.txt") } ?: return 0
        // 自定义词 / 固定词 / 常用语都会进 Rime 的 custom_phrase（同步过来的也一样）
        val types = WordType.entries.filter { it.syncedToRime }
        val entries = types.flatMap { dao.byType(it) }
        // Rime 的 custom_phrase 是「按输入串前缀匹配」的：键是 `编码<空格><TAB>词条`，
        // 而用户实际敲的是连写的拼音（bode），所以写入时必须去掉编码里的空格。
        val lines = entries
            .filter { it.enabled && it.word.isNotBlank() && !it.code.isNullOrBlank() }
            .joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n") { entry ->
                "${entry.word}\t${entry.code.orEmpty().replace(" ", "")}"
            }
        return try {
            file.parentFile?.mkdirs()
            file.writeText(lines)
            // 让新的自定义短语在当前会话生效
            RimeRuntime.restartSession()
            entries.size
        } catch (throwable: Throwable) {
            Log.e(TAG, "同步自定义短语失败", throwable)
            0
        }
    }

    /** 把学习词写回 librime 用户词典（格式：`词条 \t 编码 \t 次数`，与导出文件一致） */
    private suspend fun importLearnedIntoRime(entries: List<DictionaryEntry>) {
        val dir = userDir() ?: return
        val tmp = File(dir, LEARNED_IMPORT_FILE)
        try {
            tmp.writeText(
                entries.joinToString(separator = "\n", postfix = "\n") { entry ->
                    "${entry.word}\t${entry.code.orEmpty()}\t${entry.frequency}"
                },
            )
            val count = RimeRuntime.importUserDict(tmp)
            Log.i(TAG, "写回 librime 学习记录：${entries.size} 条（返回 $count）")
        } catch (throwable: Throwable) {
            Log.e(TAG, "写回 librime 学习记录失败", throwable)
        } finally {
            tmp.delete()
        }
    }

    /**
     * 从 librime 的用户词典导出学习记录，覆盖式镜像成 [WordType.LEARNED] 词条。
     * @return 学习词条数
     */
    suspend fun refreshLearnedFromRime(): Int {
        // 顺手清掉旧版本写入的英文种子词，避免污染统计（每个进程只做一次）
        removeLegacySeededWordsOnce()
        val dir = userDir() ?: return dao.countByType(WordType.LEARNED)
        val tmp = File(dir, LEARNED_EXPORT_FILE)
        val count = RimeRuntime.exportUserDict(tmp)
        if (count < 0) {
            // Rime 还没初始化（例如只打开了设置页）：保留上一次的镜像，不要清空
            tmp.delete()
            return dao.countByType(WordType.LEARNED)
        }
        if (!tmp.exists()) {
            dao.deleteByType(WordType.LEARNED)
            return 0
        }
        val text = runCatching { tmp.readText() }.getOrDefault("")
        tmp.delete()

        val outcome = DictionaryCodec.parseRimeUserDict(text)
        val entries = outcome.entries.map { parsed ->
            DictionaryEntry(
                word = parsed.word,
                code = parsed.code,
                frequency = parsed.frequency,
                type = WordType.LEARNED,
                source = SOURCE_RIME,
            )
        }
        dao.deleteByType(WordType.LEARNED)
        if (entries.isNotEmpty()) {
            dao.insertAll(entries)
        }
        Log.i(TAG, "同步学习记录：${entries.size} 条（导出返回 $count）")
        return entries.size
    }
    /** 清空学习记录：删除 librime 的 userdb + 本地镜像 */
    suspend fun clearLearned(): Boolean {
        val cleared = RimeRuntime.clearUserLearning()
        dao.deleteByType(WordType.LEARNED)
        return cleared
    }

    /** 把学习记录导出成 Rime 用户词典文本（可以直接再导入回去） */
    suspend fun exportLearnedAsRimeFile(destination: File): Int =
        RimeRuntime.exportUserDict(destination)

    /** 从 Rime 用户词典文本导入学习记录 */
    suspend fun importLearnedFromRimeFile(source: File): Int =
        RimeRuntime.importUserDict(source)

    // ---------------------------------------------------------------- 维护

    /** 旧版本写入的英文种子词不属于「我的词库」，清理掉以免污染统计 */
    suspend fun removeLegacySeededWords(): Int {
        legacyCleaned = true
        return dao.deleteBySource(SOURCE_SEEDED)
    }

    /** 每个进程只清理一次（设置页可能在输入法服务之前打开） */
    private suspend fun removeLegacySeededWordsOnce(): Int {
        if (legacyCleaned) return 0
        return removeLegacySeededWords()
    }

    companion object {
        private const val TAG = "BoardDict"
        private const val LEARNED_EXPORT_FILE = "board_learned_export.txt"
        private const val LEARNED_IMPORT_FILE = "board_learned_import.txt"
        const val DEFAULT_LIMIT = 500
        const val SOURCE_LOCAL = "local"
        const val SOURCE_IMPORTED = "imported"
        const val SOURCE_RIME = "rime"
        const val SOURCE_SEEDED = "seeded"
        const val SOURCE_SYNCED = "synced"

        /** 单次合并允许的最大记录数：超出基本可以断定同步文件异常 */
        const val MAX_MERGE_RECORDS = 5_000

        /** 参与同步的类型（学习词单独走 librime 用户词典合并） */
        val SYNC_TYPES = listOf(
            WordType.CUSTOM,
            WordType.PINNED,
            WordType.BLOCKED,
            WordType.PHRASE,
        )
    }
}
