package com.board.ime.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译状态机 / 隐私生命周期的验证（**全部离线，不联网**）。
 *
 * 这里验证的是：只有用户点 [翻译] 才调用 provider、复制/替换由用户触发、
 * 退出与失败都会清空内存中的原文译文，且语言选择是唯一被"记住"的东西。
 *
 * **真实百度 API 不在这里验证**（没有凭据），报告中写 NOT VERIFIED。
 */
class TranslationSessionTest {

    private class FakeHost : TranslationSession.Host {
        var inputText: String? = null
        var shown = false
        var hidden = false
        var replaced: String? = null
        var copied: String? = null
        val renders = mutableListOf<Render>()

        data class Render(
            val state: TranslationUiState,
            val sourceText: String,
            val resultText: String,
            val message: String,
        )

        override fun showTranslationPanel() {
            shown = true
        }

        override fun hideTranslationPanel() {
            hidden = true
        }

        override fun renderTranslation(
            state: TranslationUiState,
            sourceText: String,
            resultText: String,
            source: TranslationLanguage,
            target: TranslationLanguage,
            message: String,
        ) {
            renders += Render(state, sourceText, resultText, message)
        }

        override fun currentInputText(): String? = inputText

        override fun replaceCurrentInput(text: String) {
            replaced = text
        }

        override fun copyToClipboard(text: String) {
            copied = text
        }

        val last: Render get() = renders.last()
    }

    private class CountingProvider(private val outcome: TranslationOutcome) : TranslationProvider {
        var calls = 0
        override val displayName: String = "测试服务"
        override fun isConfigured(): Boolean = true
        override suspend fun translate(request: TranslationRequest): TranslationOutcome {
            calls++
            return outcome
        }
    }

    private fun session(
        host: FakeHost,
        provider: TranslationProvider?,
        saved: MutableList<String> = mutableListOf(),
    ): TranslationSession = TranslationSession(
        host = host,
        provider = { provider },
        scope = CoroutineScope(Dispatchers.Unconfined),
        loadSourceLanguage = { TranslationLanguage.AUTO },
        saveSourceLanguage = { saved += it.code },
        loadTargetLanguage = { TranslationLanguage.ENGLISH },
        saveTargetLanguage = { saved += it.code },
    )

    @Test
    fun openReadsInputButNeverCallsProvider() {
        val host = FakeHost().apply { inputText = "你好" }
        val provider = CountingProvider(TranslationOutcome.Success(
            TranslationResult("[en] 你好", TranslationLanguage.CHINESE, TranslationLanguage.ENGLISH),
        ))
        val subject = session(host, provider)

        subject.open()

        assertTrue(host.shown)
        assertEquals(TranslationUiState.IDLE, subject.state)
        assertEquals("你好", host.last.sourceText)
        assertEquals("打开面板不得发起任何网络请求", 0, provider.calls)
    }

    @Test
    fun translateOnlyOnUserActionAndCopyWritesClipboard() {
        val host = FakeHost().apply { inputText = "你好" }
        val provider = MockTranslationProvider()
        val subject = session(host, provider)

        subject.open()
        subject.translate()

        assertEquals(TranslationUiState.RESULT, subject.state)
        assertEquals("[English] 你好", host.last.resultText)
        assertTrue(subject.hasResult())

        // 复制之前，剪贴板不得被写过；编辑框也不得被写过
        assertNull(host.copied)
        assertNull(host.replaced)

        subject.copyResult()
        assertEquals("[English] 你好", host.copied)
        assertNull("复制不允许自动替换输入框", host.replaced)
    }

    @Test
    fun replaceHandsTextToHostAndClearsMemory() {
        val host = FakeHost().apply { inputText = "你好" }
        val subject = session(host, MockTranslationProvider())

        subject.open()
        subject.translate()
        subject.replaceInput()

        assertEquals("[English] 你好", host.replaced)
        assertTrue("替换后必须关闭面板", host.hidden)
        assertFalse("替换后内存里不得再留有译文", subject.hasResult())
    }

    @Test
    fun closeClearsTranslationData() {
        val host = FakeHost().apply { inputText = "你好" }
        val subject = session(host, MockTranslationProvider())

        subject.open()
        subject.translate()
        assertTrue(subject.hasResult())

        subject.close()

        assertTrue(host.hidden)
        assertFalse(subject.hasResult())
        // 再次打开时不得残留上一次的原文 / 译文
        host.inputText = "世界"
        subject.open()
        assertEquals("世界", host.last.sourceText)
        assertEquals("", host.last.resultText)
    }

    @Test
    fun emptyTextIsRejectedWithoutCallingProvider() {
        val host = FakeHost().apply { inputText = "   " }
        val provider = CountingProvider(
            TranslationOutcome.Success(
                TranslationResult("x", TranslationLanguage.AUTO, TranslationLanguage.ENGLISH),
            ),
        )
        val subject = session(host, provider)

        subject.open()
        subject.translate()

        assertEquals(TranslationUiState.ERROR, subject.state)
        assertEquals(TranslationFailure.EmptyText.message, host.last.message)
        assertEquals("空文本不得联网", 0, provider.calls)
    }

    @Test
    fun missingCredentialsAsksUserToConfigure() {
        val host = FakeHost().apply { inputText = "你好" }
        val subject = session(host, provider = null)

        subject.open()

        assertEquals(TranslationUiState.NOT_CONFIGURED, subject.state)
        // 具体文案由面板负责显示（TranslationPanel 会把 NOT_CONFIGURED 显示成「请先配置翻译服务」）
    }

    @Test
    fun failureShowsHumanMessageAndClearsResult() {
        val host = FakeHost().apply { inputText = "你好" }
        val provider = CountingProvider(TranslationOutcome.Failure(TranslationFailure.Network))
        val subject = session(host, provider)

        subject.open()
        subject.translate()

        assertEquals(1, provider.calls)
        assertEquals(TranslationUiState.ERROR, subject.state)
        assertEquals(TranslationFailure.Network.message, host.last.message)
        assertFalse(subject.hasResult())
    }

    @Test
    fun onlyLanguageChoiceIsPersisted() {
        val host = FakeHost().apply { inputText = "你好" }
        val saved = mutableListOf<String>()
        val subject = session(host, MockTranslationProvider(), saved)

        subject.open()
        subject.translate()
        subject.cycleTargetLanguage()
        subject.cycleSourceLanguage()

        // 保存下来的只有语言 code，永远不含正文
        // 目标：en → jp；源：auto → zh
        assertEquals(listOf("jp", "zh"), saved)
    }

    @Test
    fun closeCancelsInFlightRequest() {
        val host = FakeHost().apply { inputText = "你好" }
        val provider = MockTranslationProvider(delayMillis = 5_000)
        val subject = session(host, provider)

        subject.open()
        subject.translate()
        assertEquals(TranslationUiState.LOADING, subject.state)

        subject.close()

        assertEquals(TranslationUiState.IDLE, subject.state)
        assertTrue(host.hidden)
        assertFalse("取消后不得留下已完成的译文", subject.hasResult())
    }

    @Test
    fun releaseClearsEverything() {
        val host = FakeHost().apply { inputText = "你好" }
        val subject = session(host, MockTranslationProvider())

        subject.open()
        subject.translate()
        subject.release()

        assertFalse(subject.hasResult())
        assertEquals(TranslationUiState.IDLE, subject.state)
    }
}
