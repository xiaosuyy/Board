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

    /**
     * 纯数字自定义编码（`code -> 词条列表`）。
     *
     * 由外部在**数据刷新时**（进入输入框）注入一次，按键路径只做内存查表，不访问数据库。
     * 只在 NUMBER 模式、且当前数字串与某个 code **完全相等**时追加候选（前缀不匹配）。
     */
    private var numericCustomPhrases: Map<String, List<String>> = emptyMap()

    fun setNumericCustomPhrases(phrases: Map<String, List<String>>) {
        numericCustomPhrases = phrases
    }

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
        get() = if (currentMode == InputMode.CHINESE || composingCache.isNotEmpty()) {
            composingCache
        } else {
            latin.composing
        }

    override fun candidates(): List<Candidate> =
        if (currentMode == InputMode.CHINESE || composingCache.isNotEmpty()) {
            candidatesCache
        } else {
            // 数字自定义码：只有「当前数字串 == 某个纯数字 code」时才追加候选；
            // 原有（BasicEngine 的）候选保持不动、仍排在最前，前缀不追加。
            val base = latin.candidates()
            val numeric = numericCustomCandidates(latin.composing.toString())
            if (numeric.isEmpty()) base else base + numeric
        }

    override fun reset() {
        latin.reset()
        // NUMBER/SYMBOL 下 `@` 也可能在 Rime 里留下 composing，这时也要一并清掉。
        if (currentMode != InputMode.CHINESE && composingCache.isEmpty()) return

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
        // `@` 既会出现在字母层（此时 currentMode=CHINESE），也会出现在数字层 / 中文符号层
        // （Board 会把这两层切成 InputMode.NUMBER / SYMBOL）。
        //
        // 真机实测：数字层点 `@` 时 `handleKey` 收到的 mode=NUMBER，于是被提前委托给
        // BasicEngine，`@` 永远到不了 librime（独立 session 实测 process_key('@') 返回 true
        // 且有 6 个候选，含 custom_phrase 的 @qq.com / @gmail.com）。
        //
        // 因此这里**只**把 NUMBER / SYMBOL 模式下的 `Punctuation("@")` 放行到 Rime 中文路径，
        // 复用既有的 handleChineseKey / nativeProcessKey（不新增第二套 @ 处理）；
        // 其余 NUMBER / SYMBOL 输入（数字、普通标点等）与 ENGLISH 模式仍走原来的 BasicEngine。
        val specialAt = (currentMode == InputMode.NUMBER || currentMode == InputMode.SYMBOL) &&
            key is EngineKey.Punctuation && key.text == "@"
        if (currentMode != InputMode.CHINESE && !specialAt) {
            // 之前 `@` 可能在 Rime 里留下 composing；回到 BasicEngine 前先清掉，
            // 避免候选条/输入框显示与当前 composing 不一致。
            clearRimeComposingIfAny()
            return latin.handleKey(key)
        }

        return RimeRuntime.withSession(EngineResult.Ignored) { session ->
            lock.withLock { handleChineseKey(session, key) }
        }
    }

    /**
     * 清掉 Rime 会话里可能残留的 composing。
     *
     * 只会来自 NUMBER / SYMBOL 模式下被放行的 `@`；中文模式下由 [reset] 负责。
     */
    private fun clearRimeComposingIfAny() {
        if (composingCache.isEmpty() && candidatesCache.isEmpty()) return
        RimeRuntime.withSession(Unit) { session ->
            lock.withLock {
                RimeNative.nativeClearComposition(session)
                RimeNative.nativeGetCommit(session)
                refresh(session)
            }
        }
    }

    override fun pickCandidate(index: Int): EngineResult {
        // NUMBER/SYMBOL 下若 `@` 正在 Rime 里 composing，点候选仍要走 Rime。
        if (currentMode != InputMode.CHINESE && composingCache.isEmpty()) {
            // 候选映射必须与 [candidates] 完全一致：先 BasicEngine 候选，其后是数字自定义候选。
            val base = latin.candidates()
            val numeric = numericCustomCandidates(latin.composing.toString())
            val customIndex = index - base.size
            if (customIndex in numeric.indices) {
                // 数字自定义码：提交词条本身、清空当前数字 composing、不补空格。
                latin.reset()
                return EngineResult.Commit(numeric[customIndex].text)
            }
            return latin.pickCandidate(index)
        }

        return RimeRuntime.withSession(EngineResult.Ignored) { session ->
            lock.withLock {
                val committed = selectAndDrain(session, index)
                if (committed.isEmpty()) EngineResult.Ignored else EngineResult.Commit(committed, composing = composingCache)
            }
        }
    }

    /**
     * NUMBER 模式下，当前数字串**完全等于**某个纯数字 code 时的自定义候选（前缀不匹配）。
     * 其它模式（CHINESE / ENGLISH / SYMBOL）一律返回空，避免影响既有行为。
     */
    private fun numericCustomCandidates(composing: String): List<Candidate> {
        if (currentMode != InputMode.NUMBER || composing.isEmpty()) return emptyList()
        val words = numericCustomPhrases[composing] ?: return emptyList()
        return words.map { Candidate(text = it) }
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

        // 中文标点：先结束组词，再输出标点。
        //
        // 唯一例外是 `@`：它可能是「特殊自定义编码」的首字符（custom_phrase 的 code，
        // 例如 `@` -> `@qq.com`），必须像普通按键一样送进 librime 形成 composing，
        // 才能被 custom_phrase 命中。若 librime 不接受（未部署相应 speller/recognizer），
        // 则回落到原来的「结束组词 + 输出标点」行为。
        // 其余标点（, . ? ! : ; ' " 等）仍走原路径，行为不变。
        is EngineKey.Punctuation ->
            if (key.text == "@" && RimeNative.nativeProcessKey(session, RimeKey.of('@'), 0)) {
                afterInput(session)
            } else {
                commitWithSuffix(session, suffix = key.text, preferCandidate = true)
            }
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
            // 双拼：preedit 是全拼（编辑区显示的内容），回车按显示的全拼上屏，去掉音节间空格
            val preedit = RimeNative.nativeGetPreedit(session).orEmpty()
            val raw = (preedit.ifEmpty { RimeNative.nativeGetInput(session).orEmpty() })
                .replace(" ", "")
            RimeNative.nativeClearComposition(session)
            RimeNative.nativeGetCommit(session)
            refresh(session)
            raw
        }

        if (text.isEmpty()) return EngineResult.Ignored
        // 逐字选词：仍有未选完的拼音时，不补后缀，保留 composing 继续选下一个字
        if (composingCache.isNotEmpty()) return EngineResult.Commit(text, composing = composingCache)
        return EngineResult.Commit(text + suffix)
    }

    private fun selectAndDrain(session: Long, index: Int): String {
        // index 是「可见候选」的下标，需要映射回 librime 的真实下标（屏蔽词会改变位置）
        val rimeIndex = rimeIndexCache.getOrNull(index) ?: index
        val candidateText = candidatesCache.getOrNull(index)?.text
        val syllables = RimeNative.nativeGetPreedit(session).orEmpty()
            .split(' ', '\'').filter { it.isNotEmpty() }
        val selected = RimeNative.nativeSelectCandidate(session, rimeIndex)
        val committed = RimeNative.nativeGetCommit(session).orEmpty()
        if (!selected || committed.isEmpty()) {
            // 真机/模拟器实测：对「拼写不完整」的候选，librime 的 select_candidate* 会返回 true
            // 但**什么都不做**（commit 为空、composition 未清空），表现为「点后面的候选没反应」。
            // 兜底：提交该候选文本。若候选只覆盖前几个音节（如 xu 对应「许」），
            // 把剩余音节（pengcheng）重新送入 librime，继续逐字选词（对齐微信）。
            if (!candidateText.isNullOrEmpty() && composingCache.isNotEmpty()) {
                val rest = syllables.drop(candidateText.length).joinToString("")
                RimeNative.nativeClearComposition(session)
                RimeNative.nativeGetCommit(session)
                if (rest.isNotEmpty()) {
                    rest.forEach { RimeNative.nativeProcessKey(session, RimeKey.of(it), 0) }
                    RimeNative.nativeGetCommit(session)
                }
                refresh(session)
                return candidateText
            }
            refresh(session)
            return ""
        }
        refresh(session)
        return committed
    }

    private fun refresh(session: Long) {
        val input = RimeNative.nativeGetInput(session).orEmpty()
        val preedit = RimeNative.nativeGetPreedit(session).orEmpty()
        // 双拼方案带 preedit_format：preedit 是转换后的全拼（编辑区显示全拼，与微信一致）；
        // 全拼方案两者相同，行为不变。
        composingCache = preedit.ifEmpty { input }
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
        val syllables = preedit.split(' ', '\'').count { it.isNotEmpty() }
        val ordered = orderCandidates(visible, indices, syllables)
        candidatesCache = ordered.first
        rimeIndexCache = ordered.second
    }

    /**
     * 候选横排：整句只留几个，后面是词，最后是单字。
     *
     * 只对 **3 个音节以上**的输入生效：单 / 双音节时 Rime 自己的词频序更准
     * （例如 `a` 的首选是「啊」而不是两字词），改序反而变差。
     * 被截掉的整句不丢弃，排在最后，避免所有候选都是长句时选不出来。
     */
    private fun orderCandidates(
        visible: List<Candidate>,
        indices: List<Int>,
        syllables: Int,
    ): Pair<List<Candidate>, List<Int>> {
        if (syllables < 3 || visible.size < 2) return visible to indices

        val sentences = ArrayList<Int>()
        val words = ArrayList<Int>()
        val chars = ArrayList<Int>()
        visible.indices.forEach { index ->
            when (visible[index].text.length) {
                0, 1 -> chars += index
                in 2..3 -> words += index
                else -> sentences += index
            }
        }
        val kept = if (words.isEmpty() && chars.isEmpty()) sentences else sentences.take(SENTENCE_LIMIT)
        val order = ArrayList<Int>(visible.size)
        order += kept
        order += words
        order += chars
        order += sentences.drop(kept.size)

        val orderedVisible = order.map { visible[it] }
        val orderedIndices = order.map { indices[it] }
        return orderedVisible to orderedIndices
    }

    private companion object {
        /** 候选栏里最多保留的整句数量（对齐微信：整句只留几个） */
        const val SENTENCE_LIMIT = 3
    }
}
