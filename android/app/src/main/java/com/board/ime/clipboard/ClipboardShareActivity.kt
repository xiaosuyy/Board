package com.board.ime.clipboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.board.ime.database.BoardDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「分享给 Board」入口（`ACTION_SEND` / `text/plain`）。
 *
 * 设计取舍（与需求一致）：
 * - **优先用 `content://` URI**（`EXTRA_STREAM` / `data`）从流里读，
 *   而不是要求对方把几十 MB 文本塞进 Intent 的 `EXTRA_TEXT` 字符串；
 * - 只有对方确实只给了 `EXTRA_TEXT`（且没有 URI）时才退回读字符串；
 * - 导入走 [ClipboardRepository.importText] → 超长内容会自动落到文件里，
 *   不会因为 SQLite 单行上限而失败。
 *
 * 这个 Activity 只做「接收 + 入库 + 提示」，界面是透明的。
 */
class ClipboardShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { receive(intent) } }
            // 分享进来的内容等同于「复制了新内容」→ 走剪贴板自动同步通道
            if (outcome.getOrNull() == ClipboardCaptureResult.INSERTED ||
                outcome.getOrNull() == ClipboardCaptureResult.UPDATED
            ) {
                runCatching {
                    com.board.ime.sync.SyncEngineHolder.get(this@ClipboardShareActivity).requestClipboardSync()
                }.onFailure {
                    // 以前这里静默吞异常，导致"没触发"无法定位
                    Log.w(TAG, "触发剪贴板同步失败：${it.message}", it)
                }
            }
            val message = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        ClipboardCaptureResult.INSERTED -> "已保存到 Board 剪贴板"
                        ClipboardCaptureResult.UPDATED -> "这段内容已经在 Board 剪贴板里了"
                        ClipboardCaptureResult.SKIPPED_SENSITIVE -> "疑似验证码 / 密码，按设置未保存"
                        ClipboardCaptureResult.SKIPPED_EMPTY -> "没有可保存的文本"
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "分享导入失败：${error.message}", error)
                    "导入失败：${error.javaClass.simpleName}"
                },
            )
            Toast.makeText(this@ClipboardShareActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun receive(intent: Intent?): ClipboardCaptureResult {
        if (intent == null) return ClipboardCaptureResult.SKIPPED_EMPTY
        // B1（D1）：与输入法服务 / SyncEngine 共用**同一个** repository 实例，
        // 这样「分享给 Board」产生的本机历史也会进入增量历史 log。
        val repository = com.board.ime.sync.SyncEngineHolder.clipboardRepository(this)

        // 0) 图片分享（Phase 1-E）：与系统剪贴板图片**完全同一条**链路，不复制上传代码
        val imageUri = sharedImageUri(intent)
        if (imageUri != null) {
            val engine = com.board.ime.sync.SyncEngineHolder.get(this@ClipboardShareActivity)
            if (!engine.loadConfig().autoSync) {
                Log.i(TAG, "autoSync=OFF（暂停），忽略分享的图片")
                return ClipboardCaptureResult.SKIPPED_EMPTY
            }
            val result = repository.captureImageFromUri(contentResolver, imageUri, enqueuePush = true)
            if (result != null) {
                // 只记状态，绝不记图片内容
                Log.i(TAG, "分享导入（图片） result=$result")
                return result
            }
            Log.w(TAG, "分享的图片无法读取或解码，已忽略")
            return ClipboardCaptureResult.SKIPPED_EMPTY
        }

        // 1) 优先：content:// URI（大文本不必经过 Intent 字符串）
        val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
        if (uri != null && uri.scheme?.startsWith("content") == true) {
            val text = readUri(uri)
            if (!text.isNullOrEmpty()) {
                Log.i(TAG, "分享导入（URI）：${text.length} 字")
                return repository.importText(text)
            }
        }

        // 2) 退回：EXTRA_TEXT（对方只给了字符串）
        val extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!extra.isNullOrBlank()) {
            Log.i(TAG, "分享导入（EXTRA_TEXT）：${extra.length} 字")
            return repository.importText(extra)
        }
        return ClipboardCaptureResult.SKIPPED_EMPTY
    }

    /**
     * 分享意图里的图片 URI。
     *
     * 判定：`Intent.type` 优先（`image` 开头）→ URI 的实际 MIME → 对方完全没给 type 时按扩展名兜底。
     * 支持 `content://`；`file://` 也能通过 `ContentResolver.openInputStream` 读。
     * **绝不把 URI 转成 String 当普通文本。**
     */
    private fun sharedImageUri(intent: Intent): Uri? {
        val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data ?: return null
        val declared = intent.type
        if (ClipboardImageCodec.isImageMime(declared)) return uri
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (ClipboardImageCodec.isImageMime(mime)) return uri
        if (declared != null) return null
        val name = uri.lastPathSegment?.lowercase() ?: return null
        return uri.takeIf { candidate -> IMAGE_EXTENSIONS.any { name.endsWith(it) } }
    }

    private fun readUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    }.onFailure { Log.w(TAG, "读取分享内容失败：${it.message}") }.getOrNull()

    /** 兼容 Android 13+ 的类型化 getParcelableExtra */
    private inline fun <reified T : Any> Intent.parcelableExtra(name: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name) as? T
        }

    private val contentStore get() = ClipboardRepository.contentStoreFor(this)

    private companion object {
        const val TAG = "BoardClipboardShare"

        /** 对方没给 MIME 时的兜底扩展名 */
        val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")
    }
}
