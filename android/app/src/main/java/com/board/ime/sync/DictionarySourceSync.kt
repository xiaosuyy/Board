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
                sourceId = source.remoteId,
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
     * @param onProgress (词库名, 0f..1f 或 null)
     */
    suspend fun sync(
        localDir: File,
        onProgress: (String, Float?) -> Unit = { _, _ -> },
    ): Report {
        localDir.mkdirs()

        val index = loadIndex()
        val remote = LinkedHashMap<String, Manifest>()
        index.forEach { remote[it.sourceId] = it }

        var report = Report()
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

            val remoteManifest = remote[id]
            if (remoteManifest != null &&
                remoteManifest.sha256 == manifest.sha256 &&
                remoteManifest.fileSize == manifest.fileSize
            ) {
                SyncLog.event(
                    SyncEvent.SKIP_SAME_HASH,
                    "词库 ${source.name} 云端一致（sha=${SyncLog.shortHash(source.hash)}），跳过上传",
                )
                report = report.copy(skipped = report.skipped + 1)
                return@forEach
            }

            ensureDir(id)
            val started = System.currentTimeMillis()
            val put = client.uploadFile(
                path = dataPath(id),
                file = file,
                contentType = "application/octet-stream",
            ) { done, total ->
                onProgress(source.name, fraction(done, total))
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
            val existing = sources.findBySourceId(manifest.sourceId)
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
        if (report.uploaded > 0) {
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

        // 原子替换：先改名，再删旧名（renameTo 在同一目录内是原子的）
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        val now = System.currentTimeMillis()
        val existing = sources.findBySourceId(manifest.sourceId)
        val row = DictionarySource(
            id = existing?.id ?: 0L,
            sourceId = manifest.sourceId,
            name = targetName,
            type = DictionarySourceType.IMPORTED,
            path = target.absolutePath,
            hash = actualHash,
            entryCount = manifest.entryCount,
            sizeBytes = actualSize,
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

    private suspend fun ensureDir(id: String) {
        runCatching { client.ensureDirectory("$DICT_ROOT/$id") }
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
