package com.board.ime.sync

import android.util.Log
import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.database.dao.DictionarySourceDao
import com.board.ime.dictionary.DictionarySource
import com.board.ime.dictionary.DictionarySourceType
import com.board.ime.sync.webdav.WebDavClient
import com.board.ime.sync.webdav.WebDavError
import com.board.ime.sync.webdav.WebDavResponse
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 大型词库（[DictionarySource]）的**文件级**同步。
 *
 * 云端布局（独立目录，不塞进用户词同步文件）：
 * ```
 * <syncDir>/dictionary/sources/index.json                  全部词库清单
 * <syncDir>/dictionary/sources/<sourceId>/manifest.json    单库元信息
 * <syncDir>/dictionary/sources/<sourceId>/dictionary.dict  词库本体
 * ```
 *
 * 与「复制成 60 万个 SyncOperation」正好相反：**一个词库就是一个文件 + 一份清单**，
 * 传输走流式读写，内存只跟 64KB 缓冲有关。
 *
 * 下载安全规则（绝不能「下到一半就覆盖当前可用词库」）：
 * 1. 先下到 `dictionary.dict.tmp`
 * 2. 流式算 SHA-256，与 manifest 比对，同时校验字节数
 * 3. 通过 → 原子改名成正式文件；不通过 → 删 tmp、保留原文件、本次算失败
 */
class DictionarySourceSync(
    private val client: WebDavClient,
    private val sources: DictionarySourceDao,
    /** 数据源落库后接入 Rime（生成 dict.yaml + 必要时 deploy），返回是否可用 */
    private val install: suspend (DictionarySource) -> Boolean,
) {

    data class Report(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val uploadedBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val installed: Int = 0,
        val firstError: String? = null,
    ) {
        val touched: Int get() = uploaded + downloaded + skipped
    }

    /** manifest.json 的内容，字段名即协议 */
    data class Manifest(
        val sourceId: String,
        val name: String,
        val type: String,
        val entryCount: Int,
        val fileSize: Long,
        val sha256: String,
        val version: Long,
        val updatedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("sourceId", sourceId)
            put("name", name)
            put("type", type)
            put("entryCount", entryCount)
            put("fileSize", fileSize)
            put("sha256", sha256)
            put("version", version)
            put("updatedAt", updatedAt)
        }

        companion object {
            fun from(json: JSONObject): Manifest? {
                val id = json.optString("sourceId").ifBlank { return null }
                return Manifest(
                    sourceId = id,
                    name = json.optString("name").ifBlank { id },
                    type = json.optString("type", DictionarySourceType.IMPORTED.name),
                    entryCount = json.optInt("entryCount", 0),
                    fileSize = json.optLong("fileSize", 0L),
                    sha256 = json.optString("sha256"),
                    version = json.optLong("version", 1L),
                    updatedAt = json.optLong("updatedAt", 0L),
                )
            }

            fun of(source: DictionarySource): Manifest = Manifest(
                // 内容身份 = SHA-256：同一份内容永远映射到同一个云端目录，
                // 与文件名无关（否则 A.dict 与 B.dict 同内容会在云端存两份）
                sourceId = source.hash.ifBlank { source.remoteId },
                name = source.name,
                type = source.type.name,
                entryCount = source.entryCount,
                fileSize = source.sizeBytes,
                sha256 = source.hash,
                version = source.version,
                updatedAt = source.updatedAt,
            )
        }
    }

    /**
     * 跑一次词库文件同步。
     *
     * @param localDir 本地词库文件目录（`files/rime/user/imported`）
     * @param syncDir 同步目录（例如 `Board`）。**必须带**：MKCOL 走的是「相对 baseUrl」的路径，
     *   漏掉它就会把集合建到账号根目录，导致随后 PUT 因父目录不存在而 409（真机上踩过）。
     * @param onProgress (词库名, 0f..1f 或 null)
     */
    suspend fun sync(
        localDir: File,
        syncDir: String,
        onProgress: (String, Float?) -> Unit = { _, _ -> },
    ): Report {
        localDir.mkdirs()

        val index = loadIndex()
        val remote = LinkedHashMap<String, Manifest>()
        index.forEach { remote[it.sourceId] = it }

        var report = Report()
        var indexDirty = false   // 清单被规范化过（把旧 id 收敛到内容寻址 id）就需要写回
        val locals = sources.allByType(DictionarySourceType.IMPORTED).filter { it.enabled }
        val localIds = HashSet<String>()

        // ---------- 本地 → 云端 ----------
        locals.forEach { source ->
            val id = source.remoteId
            localIds += id
            val manifest = Manifest.of(source)
            val file = File(source.path)
            if (!file.exists() || source.hash.isBlank()) {
                report = report.copy(
                    failed = report.failed + 1,
                    firstError = report.firstError ?: "本地词库文件缺失：${source.name}",
                )
                return@forEach
            }

            // 云端是否已有**同一份内容**？
            //
            // 两个关键点（都是真机上踩出来的）：
            // 1. 先按 id 找，找不到再**按内容 hash** 在清单里找 —— id 是内容寻址的，
            //    但历史 manifest 可能还是旧的「文件名派生 id」，只按 id 查会永远查不到。
            // 2. **不再要求 fileSize 相同** —— sizeBytes 在 gz 迁移时被改成"未压缩大小"，
            //    与云端旧记录不一致，会导致每轮同步都重复上传整份词库（用户看到的就是
            //    「一直同步不成功」+ 设置页 45s 看门狗报「同步超时」）。
            val remoteManifest = remote[id]
                ?: index.firstOrNull { it.sha256.equals(manifest.sha256, ignoreCase = true) }
            if (remoteManifest != null &&
                remoteManifest.sha256.equals(manifest.sha256, ignoreCase = true)
            ) {
                // 顺手把清单收敛到内容寻址的 id（下次 index.json 写回时生效）
                if (remoteManifest.sourceId != manifest.sourceId) {
                    remote.remove(remoteManifest.sourceId)
                    remote[manifest.sourceId] = manifest
                    indexDirty = true
                }
                SyncLog.event(
                    SyncEvent.SKIP_SAME_HASH,
                    "词库 ${source.name} 云端一致（sha=${SyncLog.shortHash(source.hash)}），跳过上传",
                )
                report = report.copy(skipped = report.skipped + 1)
                return@forEach
            }

            ensureDir(id, syncDir)
            val started = System.currentTimeMillis()
            // 线上传的永远是**未压缩**内容（协议不变）：本地若是 gz，先临时解压，传完必删
            val upload = if (com.board.ime.dictionary.DictionaryStore.isCompressedStore(file.path)) {
                val tmp = File(localDir, "sync-upload-${id}.plain.tmp")
                if (!com.board.ime.dictionary.DictionaryStore.decompressTo(file, tmp, source.hash)) {
                    tmp.delete()
                    report = report.copy(
                        failed = report.failed + 1,
                        firstError = report.firstError ?: "本地压缩词库解压失败：${source.name}",
                    )
                    return@forEach
                }
                tmp
            } else {
                file
            }
            val put = try {
                client.uploadFile(
                    path = dataPath(id),
                    file = upload,
                    contentType = "application/octet-stream",
                ) { done, total ->
                    onProgress(source.name, fraction(done, total))
                }
            } finally {
                if (upload !== file) upload.delete()
            }
            if (!put.isOk) {
                report = report.copy(
                    failed = report.failed + 1,
                    firstError = report.firstError ?: describe(put, "上传词库 ${source.name} 失败"),
                )
                return@forEach
            }
            // 数据先上去，manifest 后写：读端不会看到「指向不存在文件」的清单
            val putManifest = client.putText(
                manifestPath(id),
                manifest.toJson().toString(2),
                CONTENT_TYPE_JSON,
            )
            if (!putManifest.isOk) {
                report = report.copy(
                    failed = report.failed + 1,
                    firstError = report.firstError
                        ?: describe(putManifest, "写词库清单 ${source.name} 失败"),
                )
                return@forEach
            }
            remote[id] = manifest
            report = report.copy(
                uploaded = report.uploaded + 1,
                uploadedBytes = report.uploadedBytes + file.length(),
            )
            SyncLog.event(
                SyncEvent.UPLOAD,
                "词库上传 ${source.name} ${file.length()}B sha=${SyncLog.shortHash(source.hash)} " +
                    "${System.currentTimeMillis() - started}ms",
            )
        }

        // ---------- 云端 → 本地 ----------
        remote.values.toList().forEach { manifest ->
            if (manifest.sourceId in localIds) return@forEach
            // 先按 sourceId 找，再按**内容 hash** 找：老数据里同一份内容可能有两条 source
            // （因为早期用文件名派生 id）。命中内容就复用那一行，不再新增重复记录。
            val existing = sources.findBySourceId(manifest.sourceId)
                ?: sources.allByType(DictionarySourceType.IMPORTED)
                    .firstOrNull { it.hash.equals(manifest.sha256, ignoreCase = true) }
            if (existing != null && existing.hash == manifest.sha256 && File(existing.path).exists()) {
                SyncLog.event(
                    SyncEvent.SKIP_SAME_HASH,
                    "词库 ${manifest.name} 本地已一致，跳过下载",
                )
                report = report.copy(skipped = report.skipped + 1)
                return@forEach
            }

            onProgress(manifest.name, 0f)
            val outcome = downloadOne(localDir, manifest, onProgress)
            if (outcome == null) {
                report = report.copy(
                    failed = report.failed + 1,
                    firstError = report.firstError ?: "下载词库 ${manifest.name} 失败或校验不通过",
                )
                return@forEach
            }
            val installed = runCatching { install(outcome) }.getOrDefault(false)
            report = report.copy(
                downloaded = report.downloaded + 1,
                downloadedBytes = report.downloadedBytes + manifest.fileSize,
                installed = report.installed + if (installed) 1 else 0,
            )
            SyncLog.event(
                SyncEvent.DOWNLOAD,
                "词库下载 ${manifest.name} ${manifest.fileSize}B sha=${SyncLog.shortHash(manifest.sha256)} " +
                    "rime=${if (installed) "ready" else "pending"}",
            )
        }

        // ---------- 回写清单（让其它设备能看到本次新增 / 更新） ----------
        if (report.uploaded > 0 || indexDirty) {
            val array = JSONArray()
            remote.values.forEach { array.put(it.toJson()) }
            val indexJson = JSONObject().apply {
                put("version", INDEX_VERSION)
                put("updatedAt", System.currentTimeMillis())
                put("sources", array)
            }
            val put = client.putText(INDEX_PATH, indexJson.toString(2), CONTENT_TYPE_JSON)
            if (!put.isOk) {
                report = report.copy(
                    failed = report.failed + 1,
                    firstError = report.firstError ?: describe(put, "写词库总清单失败"),
                )
            }
        }
        return report
    }

    /** 下载单个词库：tmp → 校验 → 原子替换；失败时保留原文件 */
    private suspend fun downloadOne(
        localDir: File,
        manifest: Manifest,
        onProgress: (String, Float?) -> Unit,
    ): DictionarySource? {
        val dir = File(localDir, manifest.sourceId).apply { mkdirs() }
        val tmp = File(dir, FILE_DATA + ".tmp")
        val targetName = safeFileName(manifest)
        val target = File(dir, targetName)
        tmp.delete()

        val response = client.downloadToFile(dataPath(manifest.sourceId), tmp) { done, total ->
            onProgress(manifest.name, fraction(done, total))
        }
        if (!response.isOk) {
            SyncLog.warn(
                SyncEvent.NETWORK_ERROR,
                "词库下载失败（原文件保留）：${manifest.name} ${describe(response, "")}",
            )
            tmp.delete()
            return null
        }

        // 校验：先看大小，再流式算 hash
        val actualSize = tmp.length()
        if (manifest.fileSize > 0L && actualSize != manifest.fileSize) {
            SyncLog.warn(
                SyncEvent.CONFLICT,
                "词库大小不符（期望 ${manifest.fileSize} 实际 $actualSize），保留原文件并丢弃下载内容",
            )
            tmp.delete()
            return null
        }
        val actualHash = ClipboardHasher.hashFile(tmp)
        if (manifest.sha256.isNotBlank() && !actualHash.equals(manifest.sha256, ignoreCase = true)) {
            SyncLog.warn(
                SyncEvent.CONFLICT,
                "词库 SHA-256 校验失败（期望 ${SyncLog.shortHash(manifest.sha256)} " +
                    "实际 ${SyncLog.shortHash(actualHash)}），保留原文件",
            )
            tmp.delete()
            return null
        }

        // 校验通过 → 压成长期存储格式 `<sha256>.dict.yaml.gz`（原子替换），并删掉临时明文
        val storedFile = com.board.ime.dictionary.DictionaryStore.writeCompressed(localDir, actualHash, tmp)
        if (storedFile == null) {
            SyncLog.warn(SyncEvent.CONFLICT, "压缩存储失败，保留原文件并丢弃下载内容：${manifest.name}")
            tmp.delete()
            return null
        }
        tmp.delete()

        val now = System.currentTimeMillis()
        val existing = sources.findBySourceId(manifest.sourceId)
        val row = DictionarySource(
            id = existing?.id ?: 0L,
            sourceId = manifest.sourceId,
            name = targetName,
            type = DictionarySourceType.IMPORTED,
            path = storedFile.absolutePath,
            hash = actualHash,
            entryCount = manifest.entryCount,
            sizeBytes = manifest.fileSize.takeIf { it > 0 } ?: actualSize,
            enabled = true,
            format = "RIME_DICT",
            version = manifest.version,
            ready = false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val id = sources.upsert(row)
        return row.copy(id = if (id > 0L) id else row.id)
    }

    /** 云端孤儿扫描结果 */
    data class SweepReport(
        val scanned: Int = 0,
        val orphans: Int = 0,
        val removed: Int = 0,
        val error: String? = null,
    ) {
        val summary: String
            get() = when {
                error != null -> error
                orphans == 0 -> "云端正干净：扫描 $scanned 个目录，没有孤儿"
                else -> "扫描 $scanned 个目录，发现孤儿 $orphans 个，已删除 $removed 个"
            }
    }

    /**
     * 扫描云端 `dictionary/sources/` 下的目录，删掉**没有任何本地数据源引用**的孤儿目录。
     *
     * 背景：早期用「文件名派生 id」建过一批目录，后来又改成内容寻址 id，
     * 那些旧目录不再被 index.json 引用，会永久留在云端（用户看到"建了很多文件夹"）。
     * 合并重复词库只删"被移除的行"对应的 id，清不掉这些**无主**目录，所以需要这一步。
     *
     * 只删「目录名不是任何存活数据源内容 hash」的目录；活的目录一律不碰。
     */
    suspend fun sweepRemoteOrphans(syncDir: String): SweepReport {
        val listing = client.list(DICT_ROOT)
        if (!listing.isOk) {
            return SweepReport(error = "无法列出云端目录（HTTP ${listing.httpCode}）")
        }
        val dirs = listing.value.orEmpty()
        val live = sources.allByType(DictionarySourceType.IMPORTED)
            .map { it.hash.lowercase() }
            .toSet()
        var report = SweepReport(scanned = dirs.size)
        dirs.filter { it.lowercase() !in live }.forEach { orphan ->
            report = report.copy(orphans = report.orphans + 1)
            runCatching {
                val data = client.delete("$DICT_ROOT/$orphan/$FILE_DATA")
                val manifest = client.delete("$DICT_ROOT/$orphan/$FILE_MANIFEST")
                val dir = client.delete("$DICT_ROOT/$orphan")
                if (data.isOk || manifest.isOk || dir.isOk) {
                    report = report.copy(removed = report.removed + 1)
                    SyncLog.event(SyncEvent.DONE, "已删除云端孤儿目录：$orphan")
                }
            }
        }
        SyncLog.event(SyncEvent.DONE, "云端孤儿扫描：${report.summary}")
        return report
    }
    /** 合并结果 */
    data class MergeReport(
        val groupsMerged: Int = 0,
        val rowsRemoved: Int = 0,
        val remoteDirsRemoved: Int = 0,
        val indexUpdated: Boolean = false,
        val error: String? = null,
    ) {
        val summary: String
            get() = if (rowsRemoved == 0) {
                "没有重复词库，无需合并"
            } else {
                "已合并 $groupsMerged 组：删除 $rowsRemoved 条重复记录，清理云端目录 $remoteDirsRemoved 个"
            }
    }

    /**
     * 合并「同一份内容、多个 source」的重复词库。
     *
     * 1. 按**内容 SHA-256** 分组，每组只保留一条（优先已接入 Rime、其次优先指向 gz、最后取 id 最小）
     * 2. 删除多余的 dictionary_source 行；**不动磁盘文件**（内容寻址的 gz 是共享的，
     *    真正变成孤儿的文件交给 StorageManager 回收）
     * 3. 尽力清理云端多余目录（DELETE dictionary.dict / manifest.json），并重写 index.json
     *
     * 本地合并先做、且一定生效；云端清理是尽力而为，失败不影响本地结果。
     */
    suspend fun mergeDuplicateSources(syncDir: String): MergeReport {
        val duplicated = sources.allByType(DictionarySourceType.IMPORTED)
            .filter { it.hash.isNotBlank() }
            .groupBy { it.hash.lowercase() }
            .filter { it.value.size > 1 }
        if (duplicated.isEmpty()) return MergeReport()

        var report = MergeReport()
        val removedIds = ArrayList<String>()
        duplicated.forEach { (_, rows) ->
            val keep = rows.sortedWith(
                compareByDescending<DictionarySource> { it.ready }
                    .thenByDescending { com.board.ime.dictionary.DictionaryStore.isCompressedStore(it.path) }
                    .thenBy { it.id },
            ).first()
            rows.filter { it.id != keep.id }.forEach { duplicate ->
                sources.deleteById(duplicate.id)
                removedIds += duplicate.remoteId
                report = report.copy(rowsRemoved = report.rowsRemoved + 1)
            }
            report = report.copy(groupsMerged = report.groupsMerged + 1)
            SyncLog.event(
                SyncEvent.DONE,
                "合并重复词库：保留 ${keep.name}（sha=${SyncLog.shortHash(keep.hash)}），删掉 ${rows.size - 1} 条",
            )
        }

        // 云端清理：DELETE 走 urlFor，会自动带上同步目录，所以这里不要再拼 syncDir
        removedIds.forEach { id ->
            runCatching {
                val data = client.delete("$DICT_ROOT/$id/$FILE_DATA")
                val manifest = client.delete("$DICT_ROOT/$id/$FILE_MANIFEST")
                if (data.isOk || manifest.isOk) {
                    report = report.copy(remoteDirsRemoved = report.remoteDirsRemoved + 1)
                }
            }
        }

        // 重写云端清单：只保留合并后的数据源，避免别的设备又把删掉的目录拉回来
        runCatching {
            val array = JSONArray()
            sources.allByType(DictionarySourceType.IMPORTED).forEach { array.put(Manifest.of(it).toJson()) }
            val indexJson = JSONObject().apply {
                put("version", INDEX_VERSION)
                put("updatedAt", System.currentTimeMillis())
                put("sources", array)
            }
            val put = client.putText(INDEX_PATH, indexJson.toString(2), CONTENT_TYPE_JSON)
            report = report.copy(
                indexUpdated = put.isOk,
                error = if (put.isOk) null else "云端清单更新失败（本地合并已生效）",
            )
        }
        SyncLog.event(SyncEvent.DONE, "合并重复词库完成：${report.summary}")
        return report
    }
    // ---------------------------------------------------------------- 清单

    private suspend fun loadIndex(): List<Manifest> {
        val response = client.getText(INDEX_PATH)
        if (response.error == WebDavError.NOT_FOUND) {
            SyncLog.event(SyncEvent.DOWNLOAD, "云端还没有词库清单（首次同步）")
            return emptyList()
        }
        if (!response.isOk) {
            Log.w(TAG, "读取词库清单失败：${response.error}")
            return emptyList()
        }
        return parseIndex(response.value.orEmpty())
    }

    /** 解析清单；任何字段缺失都安全跳过，不让一份坏清单毁掉同步 */
    fun parseIndex(json: String): List<Manifest> = try {
        JSONObject(json).optJSONArray("sources")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(Manifest::from)
            }
        } ?: emptyList()
    } catch (throwable: Throwable) {
        Log.w(TAG, "词库清单解析失败，已忽略")
        emptyList()
    }

    /** MKCOL 需要「同步目录 + 词库目录」的完整相对路径（相对 baseUrl） */
    private suspend fun ensureDir(id: String, syncDir: String) {
        val prefix = syncDir.trim().trim('/')
        val path = if (prefix.isEmpty()) "$DICT_ROOT/$id" else "$prefix/$DICT_ROOT/$id"
        runCatching { client.ensureDirectory(path) }
    }

    private fun fraction(done: Long, total: Long): Float? =
        if (total > 0L) (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else null

    private fun describe(response: WebDavResponse<*>, prefix: String): String {
        val detail = when (response.error) {
            WebDavError.TIMEOUT -> "超时"
            WebDavError.NETWORK -> "网络不可用"
            WebDavError.AUTH -> "认证失败"
            WebDavError.SERVER -> "服务器错误 ${response.httpCode}"
            WebDavError.NOT_FOUND -> "404"
            else -> response.message ?: response.error.name
        }
        return if (prefix.isBlank()) detail else "$prefix：$detail"
    }

    private fun safeFileName(manifest: Manifest): String {
        val base = manifest.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")
        val prefix = "${manifest.sourceId}-"
        // 已经有 sourceId 前缀（第二次同步下载时云端保存的就是这个名字）就不要再叠一层
        val name = if (base.startsWith(prefix) || base == manifest.sourceId) base else prefix + base
        return name.ifBlank { manifest.sourceId } + ".dict.txt"
    }

    companion object {
        private const val TAG = "BoardDictSync"

        /** 云端根目录（相对同步目录） */
        const val DICT_ROOT = "dictionary/sources"
        const val INDEX_PATH = "$DICT_ROOT/index.json"
        const val FILE_MANIFEST = "manifest.json"
        const val FILE_DATA = "dictionary.dict"
        private const val INDEX_VERSION = 1
        private const val CONTENT_TYPE_JSON = "application/json"

        fun manifestPath(sourceId: String): String = "$DICT_ROOT/$sourceId/$FILE_MANIFEST"

        fun dataPath(sourceId: String): String = "$DICT_ROOT/$sourceId/$FILE_DATA"
    }
}
