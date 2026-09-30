package com.board.ime.translation

import kotlinx.coroutines.delay

/**
 * **只用于验证**的离线 Provider：不联网、不需要凭据、结果确定。
 *
 * 用途：
 * - JVM 单元测试里验证状态机、隐私生命周期（内存清理）、复制/替换路径；
 * - 真机上临时验证 UI。
 *
 * **不会出现在正式的选择列表里**，用户无法选到它，
 * 因此不存在"看起来像翻译、其实是假结果"的风险。
 */
class MockTranslationProvider(
    private val delayMillis: Long = 0L,
    private val respond: (TranslationRequest) -> TranslationOutcome = { request ->
        TranslationOutcome.Success(
            TranslationResult(
                translatedText = "[${request.target.label}] ${request.text}",
                source = request.source,
                target = request.target,
            ),
        )
    },
) : TranslationProvider {

    override val displayName: String = "Mock（仅用于验证，不联网）"

    override fun isConfigured(): Boolean = true

    override suspend fun translate(request: TranslationRequest): TranslationOutcome {
        if (delayMillis > 0) delay(delayMillis)
        return respond(request)
    }
}
