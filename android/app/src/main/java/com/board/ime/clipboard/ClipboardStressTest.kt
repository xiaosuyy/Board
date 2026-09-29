package com.board.ime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import com.board.ime.database.BoardDatabase
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
 * 生成文本 → `ClipboardRepository.capture()`（真哈希 / 真去重 / 真入库）
 * → 读回完整内容与摘要 → 搜索 → 写系统剪贴板（走真实的 setPrimaryClip）。
 */
object ClipboardStressTest {

    private const val TAG = "BoardClipboard"

    /** 需要覆盖的档位（字符数，不是字节数） */
    val SIZES = listOf(10_000, 100_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000)

    data class Row(
        val chars: Int,
        val bytes: Long,
        val hashMs: Long,
        val captureMs: Long,
        val storedChars: Int,
        val previewChars: Int,
        val searchHit: Boolean,
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

        val ok: Boolean
            get() = error == null && storedChars == chars && searchHit
    }

    /**
     * 生成一段可压缩性差、行结构清晰的文本（避免编辑器把它优化掉）。
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
     * @param database 用来构造带同步队列的仓库（capture 后会产生推送任务，验证同步侧）
     */
    suspend fun run(
        database: BoardDatabase,
        clipboardManager: ClipboardManager?,
    ): List<Row> = withContext(Dispatchers.IO) {
        val repository = ClipboardRepository(
            dao = database.clipboardDao(),
            syncOperationDao = database.syncOperationDao(),
        )
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

        // 走真实抓取：真哈希、真去重、真写库
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
                storedChars = -1,
                previewChars = -1,
                searchHit = false,
                clipboardOk = false,
                error = "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }
        val captureMs = System.currentTimeMillis() - captureStart

        // 读回完整内容（这是「全文查看」用的那条路径）
        val stored = repository.findByHash(hash)
        val storedChars = stored?.content?.length ?: -1
        val previewChars = stored?.preview?.length ?: -1
        // 全文内容必须和原文本逐字一致，而不是只有摘要
        val contentIntact = stored?.content == text

        // 搜索：用这段文本里独有的 token
        val searchHit = runCatching {
            repository.search("B$tag-", limit = 5).first().any { it.contentHash == hash }
        }.getOrDefault(false)

        // 写系统剪贴板（真实 setPrimaryClip；超大文本可能被系统拒绝）
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
                Log.w(TAG, "写入系统剪贴板失败 chars=$chars：$clipboardError")
                false
            }
        }
        if (clipboardOk) {
            // 我们主动写进去的，别再当成「用户复制」重复入库
            repository.suppressCapture(hash)
        }

        Log.i(
            TAG,
            "压力测试 chars=$actualChars bytes=$bytes hash=${hashMs}ms capture=${captureMs}ms " +
                "stored=$storedChars preview=$previewChars intact=$contentIntact " +
                "search=$searchHit clip=$clipboardOk result=$result",
        )

        return Row(
            chars = actualChars,
            bytes = bytes,
            hashMs = hashMs,
            captureMs = captureMs,
            storedChars = storedChars,
            previewChars = previewChars,
            searchHit = searchHit,
            clipboardOk = clipboardOk,
            clipboardError = clipboardError,
            error = when {
                stored == null -> "未入库"
                !contentIntact -> "完整内容不一致"
                else -> null
            },
        )
    }
}
