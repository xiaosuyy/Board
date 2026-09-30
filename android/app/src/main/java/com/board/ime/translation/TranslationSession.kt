package com.board.ime.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 翻译面板的状态机（**不引入额外复杂状态**） */
enum class TranslationUiState {
    /** 已打开、还没发起翻译（可以显示原文 + [翻译] ） */
    IDLE,

    /** 请求中：只显示「翻译中…」，不阻塞键盘 */
    LOADING,

    /** 有译文：只显示结果，等用户点 [复制] / [替换] */
    RESULT,

    /** 出错：显示人话原因 */
    ERROR,

    /** 还没配置翻译服务：提示并给出去设置的入口 */
    NOT_CONFIGURED,
}

/**
 * 翻译会话：**原文与译文只存在于这里的内存字段**。
 *
 * 生命周期（与隐私基线一致）：
 * - 打开：读取当前输入框文本（一次性，只放内存）→ 显示；
 * - 翻译：只有用户点 [翻译] 才发一次请求；请求可被取消；
 * - 退出 / 替换 / 失败 / IME 销毁：立即清空 `sourceText`、`resultText`；
 * - **没有历史、没有缓存、没有最近翻译**，也不写任何本地存储与日志。
 *
 * 只持久化**语言选择**（`saveSourceLanguage` / `saveTargetLanguage`），不保存正文。
 */
class TranslationSession(
    private val host: Host,
    private val provider: () -> TranslationProvider?,
    private val scope: CoroutineScope,
    private val loadSourceLanguage: () -> TranslationLanguage,
    private val saveSourceLanguage: (TranslationLanguage) -> Unit,
    private val loadTargetLanguage: () -> TranslationLanguage,
    private val saveTargetLanguage: (TranslationLanguage) -> Unit,
) {

    interface Host {
        fun showTranslationPanel()
        fun hideTranslationPanel()
        fun renderTranslation(
            state: TranslationUiState,
            sourceText: String,
            resultText: String,
            source: TranslationLanguage,
            target: TranslationLanguage,
            message: String,
        )

        /** 当前输入框里**已经上屏**的文本（不含未提交的拼音）；取不到返回 null */
        fun currentInputText(): String?

        /** 用译文替换当前输入框文本（实现方负责先丢弃未提交的组词） */
        fun replaceCurrentInput(text: String)

        /** 只在用户点 [复制] 时调用 */
        fun copyToClipboard(text: String)
    }

    var state: TranslationUiState = TranslationUiState.IDLE
        private set

    var sourceLanguage: TranslationLanguage = loadSourceLanguage()
        private set

    var targetLanguage: TranslationLanguage = loadTargetLanguage()
        private set

    // ---- 以下两个字段就是"翻译数据"的全部：只在内存、退出即清 ----
    private var sourceText: String = ""
    private var resultText: String = ""

    private var job: Job? = null

    /** 打开面板：读一次当前输入框文本（只放内存），不发起任何网络请求 */
    fun open() {
        cancelJob()
        sourceText = host.currentInputText().orEmpty()
        resultText = ""
        host.showTranslationPanel()
        state = if (isProviderReady()) TranslationUiState.IDLE else TranslationUiState.NOT_CONFIGURED
        render()
    }

    /** 用户点 [翻译]：这是**唯一**会联网的入口 */
    fun translate() {
        val text = sourceText.trim()
        if (text.isEmpty()) {
            state = TranslationUiState.ERROR
            render(TranslationFailure.EmptyText.message)
            return
        }
        val active = provider()
        if (active == null || !active.isConfigured()) {
            state = TranslationUiState.NOT_CONFIGURED
            render()
            return
        }
        cancelJob()
        state = TranslationUiState.LOADING
        resultText = ""
        render()
        job = scope.launch {
            when (val outcome = active.translate(TranslationRequest(text, sourceLanguage, targetLanguage))) {
                is TranslationOutcome.Success -> {
                    resultText = outcome.result.translatedText
                    state = TranslationUiState.RESULT
                    render()
                }

                is TranslationOutcome.Failure -> {
                    if (outcome.failure is TranslationFailure.Cancelled) {
                        // 用户已经离开：不覆盖面板状态
                        return@launch
                    }
                    state = TranslationUiState.ERROR
                    resultText = ""
                    render(outcome.failure.message)
                }
            }
        }
    }

    /** 切换源语言（只记住语言选择，不保存正文） */
    fun cycleSourceLanguage() {
        sourceLanguage = nextOf(TranslationLanguage.SOURCE_OPTIONS, sourceLanguage)
        saveSourceLanguage(sourceLanguage)
        render()
    }

    fun cycleTargetLanguage() {
        targetLanguage = nextOf(TranslationLanguage.TARGET_OPTIONS, targetLanguage)
        saveTargetLanguage(targetLanguage)
        render()
    }

    /** 用户点 [复制]：写系统剪贴板（不自动做，也不自动写编辑框） */
    fun copyResult() {
        val text = resultText
        if (text.isEmpty()) return
        host.copyToClipboard(text)
    }

    /** 用户点 [替换]：用译文替换输入框文本，然后立即结束会话并清空内存 */
    fun replaceInput() {
        val text = resultText
        if (text.isEmpty()) return
        host.replaceCurrentInput(text)
        close()
    }

    /** 用户返回 / 离开翻译面板：取消请求 + 清空翻译数据 */
    fun close() {
        cancelJob()
        clearTranslationData()
        state = TranslationUiState.IDLE
        host.hideTranslationPanel()
    }

    /** IME 销毁：取消协程 + 清空 */
    fun release() {
        cancelJob()
        clearTranslationData()
        state = TranslationUiState.IDLE
    }

    /** 供 UI 显示：当前是否已经有可复制/可替换的译文 */
    fun hasResult(): Boolean = resultText.isNotEmpty()

    private fun isProviderReady(): Boolean = provider()?.isConfigured() == true

    private fun clearTranslationData() {
        sourceText = ""
        resultText = ""
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun render(message: String = "") {
        host.renderTranslation(
            state = state,
            sourceText = sourceText,
            resultText = resultText,
            source = sourceLanguage,
            target = targetLanguage,
            message = message,
        )
    }

    private fun nextOf(
        options: List<TranslationLanguage>,
        current: TranslationLanguage,
    ): TranslationLanguage {
        val index = options.indexOf(current)
        return options[(index + 1).mod(options.size)]
    }
}
