package com.board.ime.engine

import com.board.ime.engine.rime.RimeKey
import com.board.ime.engine.rime.RimeNative
import com.board.ime.engine.rime.RimeRuntime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 基于 librime 的中文输入引擎。
 *
 * 实现既有的 [InputEngine] 接口，中文全部交给 librime（朙月拼音方案），
 * 不做任何自己的拼音 / 分词逻辑；英文等拉丁输入则委托给 [latin]，
 * 这样英文联想与上一层行为完全一致，也保证 [BasicEngine] 依然可用。
 *
 * 架构位置：
 * ```
 * KeyboardView -> InputController -> InputEngine -> RimeEngine -> librime
 * ```
 */
class RimeEngine(
    private val latin: BasicEngine = BasicEngine(),
) : InputEngine {

    override val id: String = "rime"
    override val displayName: String = "Rime 朙月拼音"

    /**
     * 屏蔽词：librime 没有屏蔽接口，由 Board 在候选层过滤。
     * 过滤时必须同时记录原始下标，否则点击候选会选错词。
     */
    var blockedWords: Set<String> = emptySet()

    private val lock = ReentrantLock()

    private var currentMode: InputMode = InputMode.CHINESE

    private var candidatesCache: List<Candidate> = emptyList()
    private var rimeIndexCache: List<Int> = emptyList()
    private var composingCache: String = ""

    override var mode: InputMode
        get() = currentMode
        set(value) {
            if (currentMode == value) return
            reset()
            currentMode = value
            latin.mode = value
        }

    override val composing: CharSequence
        get() = if (currentMode == InputMode.CHINESE) composingCache else latin.composing

    override fun candidates(): List<Candidate> =
        if (currentMode == InputMode.CHINESE) candidatesCache else latin.candidates()

    override fun reset() {
        latin.reset()
        if (currentMode != InputMode.CHINESE) return

        // 与「同步线程关/开会话」互斥，避免用到已销毁的 session
        RimeRuntime.withSession(Unit) { session ->
            lock.withLock {
                RimeNative.nativeClearComposition(session)
                // 丢弃可能残留的待上屏内容，避免切到下一个输入框时串字
                RimeNative.nativeGetCommit(session)
                refresh(session)
            }
            Unit
        }
    }

    override fun handleKey(key: EngineKey): EngineResult {
        if (currentMode != InputMode.CHINESE) return latin.handleKey(key)

        return RimeRuntime.withSession(EngineResult.Ignored) { session ->
            lock.withLock { handleChineseKey(session, key) }
        }
    }

    override fun pickCandidate(index: Int): EngineResult {
        if (currentMode != InputMode.CHINESE) return latin.pickCandidate(index)

        return RimeRuntime.withSession(EngineResult.Ignored) { session ->
            lock.withLock {
                val committed = selectAndDrain(session, index)
                if (committed.isEmpty()) EngineResult.Ignored else EngineResult.Commit(committed)
            }
        }
    }

    // ------------------------------------------------------------ 内部实现

    private fun handleChineseKey(session: Long, key: EngineKey): EngineResult = when (key) {
        is EngineKey.Letter -> {
            if (RimeNative.nativeProcessKey(session, RimeKey.of(key.char), 0)) {
                afterInput(session)
            } else {
                EngineResult.Ignored
            }
        }

        EngineKey.Backspace -> {
            if (composingCache.isEmpty()) {
                EngineResult.Ignored
            } else {
                RimeNative.nativeProcessKey(session, RimeKey.BACKSPACE, 0)
                afterInput(session)
            }
        }

        // 空格选词；没有候选时按原样上屏并补一个空格
        EngineKey.Space -> commitWithSuffix(session, suffix = " ", preferCandidate = true)

        // 回车确认：按原样上屏，不补空格
        EngineKey.Enter -> commitWithSuffix(session, suffix = "", preferCandidate = false)

        // 中文标点：先结束组词，再输出标点
        is EngineKey.Punctuation -> commitWithSuffix(session, suffix = key.text, preferCandidate = true)
    }

    private fun afterInput(session: Long): EngineResult {
        val committed = RimeNative.nativeGetCommit(session).orEmpty()
        refresh(session)
        if (committed.isNotEmpty()) {
            return EngineResult.Commit(committed)
        }
        // 即使为空也要返回 Composing，让控制器把 composing 区域清空
        return EngineResult.Composing(composingCache)
    }

    private fun commitWithSuffix(session: Long, suffix: String, preferCandidate: Boolean): EngineResult {
        if (composingCache.isEmpty()) return EngineResult.Ignored

        val text = if (preferCandidate && candidatesCache.isNotEmpty()) {
            selectAndDrain(session, 0)
        } else {
            val raw = RimeNative.nativeGetInput(session).orEmpty()
            RimeNative.nativeClearComposition(session)
            RimeNative.nativeGetCommit(session)
            refresh(session)
            raw
        }

        if (text.isEmpty()) return EngineResult.Ignored
        return EngineResult.Commit(text + suffix)
    }

    private fun selectAndDrain(session: Long, index: Int): String {
        // index 是「可见候选」的下标，需要映射回 librime 的真实下标（屏蔽词会改变位置）
        val rimeIndex = rimeIndexCache.getOrNull(index) ?: index
        if (!RimeNative.nativeSelectCandidate(session, rimeIndex)) return ""
        val committed = RimeNative.nativeGetCommit(session).orEmpty()
        refresh(session)
        return committed
    }

    private fun refresh(session: Long) {
        composingCache = RimeNative.nativeGetInput(session).orEmpty()
        if (composingCache.isEmpty()) {
            candidatesCache = emptyList()
            rimeIndexCache = emptyList()
            return
        }
        val raw = RimeNative.nativeGetCandidates(session).orEmpty()
        val visible = ArrayList<Candidate>(raw.size)
        val indices = ArrayList<Int>(raw.size)
        raw.forEachIndexed { index, text ->
            if (text.isEmpty()) return@forEachIndexed
            if (text in blockedWords) return@forEachIndexed
            visible += Candidate(text = text)
            indices += index
        }
        candidatesCache = visible
        rimeIndexCache = indices
    }
}
