package com.board.ime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import com.board.ime.database.BoardDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 剪贴板大文本自测（**开发者模式专用**）。
 *
 * 为什么要有这个：adb 没有任何办法把一段 10MB 的文本写进设备的系统剪贴板
 * （`input text` 走的是输入系统而不是 ClipboardManager），
 * 所以「复制 → Board 抓取」这条链路只能由 Board 自己触发。
 *
 * 这里做的是**真实链路**，不是模拟：
 * 生成文本 → `ClipboardRepository.capture()`（真哈希 / 真去重 / 真分流到文件 / 真入库）
 * → 列表查询（验证不读 content 也能列出）→ 搜索 → 按 id 读全文 →
 * 删除 → 写系统剪贴板（走真实 setPrimaryClip，超限会明确记录为「系统限制」）。
 */
object ClipboardStressTest {

    private const val TAG = "BoardClipboard"

    /** 需要覆盖的档位（字符数，不是字节数） */
    val SIZES = listOf(100_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000)

    data class Row(
        val chars: Int,
        val bytes: Long,
        val hashMs: Long,
        val captureMs: Long,
        val storage: String,
        val storedChars: Int,
        val previewChars: Int,
        val listHit: Boolean,
        val searchHit: Boolean,
        val contentIntact: Boolean,
        val deletedOk: Boolean,
        val clipboardOk: Boolean,
        val clipboardError: String? = null,
        val error: String? = null,
    ) {
        val label: String
            get() = when {
                chars >= 1_000_000 -> "${chars / 1_000_000} MB"
                chars >= 1_000 -> "${chars / 1_000} KB"
                else -> "$chars 字"
            }

        /** 除了系统剪贴板（受 Android Binder 限制）之外的环节是否都通过 */
        val boardOk: Boolean
            get() = error == null && storedChars == chars && listHit && searchHit &&
                contentIntact && deletedOk
    }

    /**
     * 生成一段可压缩性差、行结构清晰的文本（避免被优化掉）。
     * 每行带上序号与档位，保证不同档位的 hash 一定不同。
     *
     * 注意末尾要 `trim()`：抓取入口自己会 `trim()`，不先对齐的话
     * 「按内容算出来的 hash」和「真正入库的 hash」不是同一个（自测踩过）。
     */
    fun generate(chars: Int, tag: String): String {
        val line = "B$tag-%06d: 超长剪贴板测试 ABCdef0123456789 ←→ 中文与符号混排。\n"
        val builder = StringBuilder(chars + line.length)
        var index = 0
        while (builder.length < chars) {
            builder.append(String.format(line, index))
            index++
        }
        return builder.substring(0, chars).trim()
    }

    /**
     * 依次跑完所有档位。
     *
     * @param inlineMaxBytes 本次使用的内联阈值（测 256KB / 512KB 用）
     */
    suspend fun run(
        database: BoardDatabase,
        clipboardManager: ClipboardManager?,
        filesDir: File,
        inlineMaxBytes: Long,
    ): List<Row> = withContext(Dispatchers.IO) {
        val repository = ClipboardRepository(
            dao = database.clipboardDao(),
            syncOperationDao = database.syncOperationDao(),
            contentStore = ClipboardRepository.contentStoreFor(filesDir),
        )
        repository.inlineMaxBytes = inlineMaxBytes
        SIZES.map { size -> runOne(repository, clipboardManager, size) }
    }

    private suspend fun runOne(
        repository: ClipboardRepository,
        clipboardManager: ClipboardManager?,
        chars: Int,
    ): Row {
        val tag = chars.toString().takeLast(3).padStart(3, '0')
        val text = generate(chars, tag)
        val actualChars = text.length
        val bytes = ClipboardHasher.utf8Length(text)

        val hashStart = System.currentTimeMillis()
        val hash = ClipboardHasher.hash(text)
        val hashMs = System.currentTimeMillis() - hashStart

        // 走真实抓取：真哈希、真去重、真分流（小文本内联 / 大文本写文件）、真写库
        val captureStart = System.currentTimeMillis()
        val result = runCatching {
            repository.capture(text, source = ClipboardCaptureSource.LOCAL)
        }.getOrElse { throwable ->
            Log.w(TAG, "压力测试捕获失败 chars=$chars：${throwable.message}")
            return Row(
                chars = actualChars,
                bytes = bytes,
                hashMs = hashMs,
                captureMs = System.currentTimeMillis() - captureStart,
                storage = "-",
                storedChars = -1,
                previewChars = -1,
                listHit = false,
                searchHit = false,
                contentIntact = false,
                deletedOk = false,
                clipboardOk = false,
                error = "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }
        val captureMs = System.currentTimeMillis() - captureStart

        // 列表查询：只读投影，绝不能因为内容太大而失败
        val list = runCatching { repository.list(limit = 200) }.getOrElse { emptyList() }
        val listed = list.firstOrNull { it.contentHash == hash }
        val listHit = runCatching { listed != null }.getOrDefault(false)

        val summary = runCatching { repository.findByHash(hash) }.getOrNull()
        val previewChars = summary?.preview?.length ?: -1
        val storage = summary?.contentStorage?.name ?: "-"

        // 搜索：用这段文本里独有的 token
        val searchHit = runCatching {
            repository.search("B$tag-", limit = 50).first().any { it.contentHash == hash }
        }.getOrDefault(false)

        // 全文：按 id 读（可能在文件里）
        val content = summary?.let { runCatching { repository.loadContent(it.id) }.getOrNull() }
        val storedChars = content?.length ?: -1
        val contentIntact = content == text

        // 删除：软删后应当从列表消失
        val deletedOk = if (summary == null) {
            false
        } else {
            runCatching {
                repository.remove(summary.id)
                repository.list(limit = 200).none { it.contentHash == hash }
            }.getOrDefault(false)
        }

        // 写系统剪贴板（真实 setPrimaryClip；超出 Android Binder 上限会被系统拒绝）
        var clipboardError: String? = null
        val clipboardOk = if (clipboardManager == null) {
            clipboardError = "没有 ClipboardManager"
            false
        } else {
            runCatching {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("BoardTest", text))
                true
            }.getOrElse { throwable ->
                clipboardError = "${throwable.javaClass.simpleName}: ${throwable.message}"
                Log.w(TAG, "写入系统剪贴板被系统拒绝 chars=$chars：$clipboardError")
                false
            }
        }
        if (clipboardOk) {
            repository.suppressCapture(hash)
        }

        Log.i(
            TAG,
            "压力测试 chars=$actualChars bytes=$bytes storage=$storage hash=${hashMs}ms " +
                "capture=${captureMs}ms stored=$storedChars preview=$previewChars " +
                "list=$listHit search=$searchHit intact=$contentIntact del=$deletedOk " +
                "clip=$clipboardOk result=$result",
        )

        return Row(
            chars = actualChars,
            bytes = bytes,
            hashMs = hashMs,
            captureMs = captureMs,
            storage = storage,
            storedChars = storedChars,
            previewChars = previewChars,
            listHit = listHit,
            searchHit = searchHit,
            contentIntact = contentIntact,
            deletedOk = deletedOk,
            clipboardOk = clipboardOk,
            clipboardError = clipboardError,
            error = when {
                summary == null -> "未入库"
                !contentIntact -> "全文不一致"
                else -> null
            },
        )
    }
}
