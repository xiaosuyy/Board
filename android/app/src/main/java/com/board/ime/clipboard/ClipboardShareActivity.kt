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
                runCatching { com.board.ime.sync.SyncEngineHolder.get(this@ClipboardShareActivity).requestClipboardSync() }
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
        val repository = ClipboardRepository(
            dao = BoardDatabase.get(this).clipboardDao(),
            syncOperationDao = BoardDatabase.get(this).syncOperationDao(),
            contentStore = contentStore,
            sensitivityDetector = com.board.ime.clipboard.HeuristicClipboardSensitivityDetector { com.board.ime.settings.BoardPreferences(this).skipSensitiveClipboard },
        )

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
    }
}
