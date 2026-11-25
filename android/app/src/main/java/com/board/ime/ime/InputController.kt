package com.board.ime.ime

import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.board.ime.engine.Candidate
import com.board.ime.engine.EngineKey
import com.board.ime.engine.EngineResult
import com.board.ime.engine.InputEngine
import com.board.ime.engine.InputMode
import com.board.ime.keyboard.KeySpec
import com.board.ime.keyboard.KeyType
import com.board.ime.keyboard.KeyboardLayout
import com.board.ime.keyboard.KeyboardLayouts
import com.board.ime.settings.KeyboardKind

/**
 * 输入控制器。
 *
 * 是「键盘 UI」和「输入引擎」之间唯一的桥：
 * - 维护四种输入状态（English / Chinese / Number / Symbol）与 Shift / 符号页 等 UI 状态；
 * - 把按键翻译成 [EngineKey] 交给引擎，把引擎结果写进 [InputConnection]；
 * - 决定当前应该显示哪一层键盘布局（View 只负责显示）。
 *
 * 架构约束（不要破坏）：
 * ```
 * KeyboardView -> InputController -> InputEngine -> RimeEngine -> librime
 * ```
 * 这里不出现任何 librime / View 类型。
 */
class InputController(
    private val engine: InputEngine,
    initialLetterMode: InputMode = InputMode.ENGLISH,
    initialKeyboardKind: KeyboardKind = KeyboardKind.QWERTY,
) {

    var inputConnection: InputConnection? = null
        private set

    var editorInfo: EditorInfo? = null
        private set

    /** 状态变化回调，由输入法服务用来刷新键盘与候选栏 */
    var onStateChanged: (() -> Unit)? = null

    /**
     * 逐字选词上屏后，我们会立刻 setComposingText 保留未选完的拼音；
     * 紧随其后的 commit 选区回调（composingStart/End = -1）不能把它当作
     * 「应用侧取消了 composing」而清空。在此时间戳前忽略这类回调。
     */
    private var suppressSelectionResetUntil: Long = 0L

    /** 提示回调（未实现的功能键）：由服务弹一条轻提示 */
    var onHint: ((String) -> Unit)? = null

    /** 读取系统剪贴板文本（由服务注入，按住 v 下滑粘贴用） */
    var clipboardText: (() -> CharSequence?)? = null

    /** 候选栏第三个位置的表情推荐（由服务注入，不把表情数据层引入控制器） */
    data class EmojiCandidate(val emoji: String, val display: String, val commit: String)

    var emojiSuggest: ((String) -> EmojiCandidate?)? = null
    var onEmojiCommitted: ((String) -> Unit)? = null

    /** 日期 / 时间 / 星期等紧跟首选的候选（由服务注入，纯本地计算） */
    data class SmartCandidate(val display: String, val commit: String)

    var smartSuggest: ((String) -> List<SmartCandidate>)? = null

    /**
     * 自定义词候选（由服务注入）：命中的词条排在所有候选最前面。
     *
     * 走这条路的词条是 Rime `custom_phrase` 放不下的（含换行的长句），
     * 普通自定义词仍由 Rime 自己匹配，避免同一个词出现两次。
     */
    var customSuggest: ((String) -> List<SmartCandidate>)? = null

    /**
     * 长期不用的学习词（30 天以上没选过）：由服务注入，返回 true 的候选排到后面。
     * 首选位不参与，保证「打全了的拼音正好是词」时仍排第一。
     */
    var staleWord: ((String) -> Boolean)? = null

    /** 每次上屏（引擎提交）通知一次，由服务记录学习词的最后使用时间 */
    var onWordCommitted: ((String) -> Unit)? = null

    /** 每点一个符号（符号层）通知一次，由服务记录「最近使用符号」 */
    var onSymbolCommitted: ((String) -> Unit)? = null

    /** 最近使用符号（按最近优先）；由服务从偏好注入 */
    var recentSymbolsProvider: (() -> List<String>)? = null

    /** 只输入一个 v 再按空格：打开快捷面板（剪贴板 / 常用语 / 符号 / 表情） */
    var onVSpaceToolbox: (() -> Unit)? = null

    /** 九键底部行的语音键（由服务注入，触发语音输入） */
    var onVoice: (() -> Unit)? = null

    /**
     * 译模式的实时译文候选（由服务异步注入）。
     *
     * @param source 译文对应的原文；服务用它判断结果是否已经过期
     * @param committedChars 译文覆盖的、光标前**已上屏**的字符数（0 = 只覆盖正在组词的部分）
     */
    data class LiveTranslation(
        val source: String,
        val display: String,
        val commit: String,
        val committedChars: Int,
    )

    var liveTranslation: LiveTranslation? = null
        private set

    fun setLiveTranslation(value: LiveTranslation?) {
        if (liveTranslation == value) return
        liveTranslation = value
        notifyStateChanged()
    }

    /** 中英文自动空格设置（由服务从偏好注入；null = 关闭） */
    var autoSpace: (() -> AutoSpaceConfig)? = null

    /** `v` + 数字层表达式（如 v1+2*3）的原始输入；null = 不在表达式模式 */
    private var expression: String? = null

    private sealed interface DisplayAction {
        data class Engine(val index: Int) : DisplayAction
        data class Direct(val commit: String, val emoji: String? = null) : DisplayAction
        data object LiveTranslation : DisplayAction
    }

    /** 与当前 [candidates] 一一对应的动作：点第几个候选时是选引擎词还是直接上屏 */
    private var displayActions: List<DisplayAction> = emptyList()

    var shiftActive: Boolean = false
        private set

    var capsLock: Boolean = false
        private set

    /** 当前输入状态（五种之一） */
    var mode: InputMode = InputMode.ENGLISH
        private set

    /** 数字 / 符号层背后"记住的字母层"，按 ABC 时回到这里 */
    var letterMode: InputMode = InputMode.ENGLISH
        private set

    /**
     * 当前键盘布局种类（全键盘 / 九键 / 日语）。
     *
     * 它与 [mode] 正交：决定「字母层」用哪套键位（QWERTY 与日语都用 letters，
     * 九键用 t9），以及「中/EN」键切换的语言范围（九键 / 全键盘在中英之间，
     * 日语在日英之间）。
     */
    var keyboardKind: KeyboardKind = initialKeyboardKind
        private set

    init {
        letterMode = if (initialLetterMode.isLetterMode) initialLetterMode else InputMode.ENGLISH
        mode = letterMode
        engine.mode = mode
    }

    /** 符号层页码：0 = 常用符号，1 = 更多符号 */
    private var symbolPage: Int = 0

    /**
     * 候选列表：引擎候选 + 紧跟首选的日期/时间候选 + 第三个位置的 emoji 推荐。
     *
     * 选择时通过 [displayActions] 换算回引擎下标，或直接提交日期 / 金额 / emoji。
     * 表达式模式（v1+2*3）下只显示计算结果。
     */
    val candidates: List<Candidate>
        get() = buildCandidates()

    private fun buildCandidates(): List<Candidate> {
        val exp = expression
        if (exp != null) {
            val suggestions = smartSuggestions(exp)
            displayActions = suggestions.map { DisplayAction.Direct(it.commit) }
            return suggestions.map { Candidate(text = it.display) }
        }

        val base = engine.candidates()
        val translation = liveTranslation
        val custom = customSuggest?.invoke(engine.composing.toString()).orEmpty()

        val out = ArrayList<Candidate>(base.size + custom.size + 6)
        val actions = ArrayList<DisplayAction>(base.size + custom.size + 6)

        // 命中的自定义词排第一（用户显式定义的词优先于词库）
        custom.forEach { suggestion ->
            out += Candidate(text = suggestion.display)
            actions += DisplayAction.Direct(suggestion.commit)
        }

        if (base.isEmpty()) {
            if (translation != null) {
                // 没有组词（例如整行已上屏）时，译文自己占候选栏
                out += Candidate(text = translation.display)
                actions += DisplayAction.LiveTranslation
            }
            if (out.isEmpty()) {
                displayActions = emptyList()
                return out
            }
            displayActions = actions
            return out
        }

        out += base[0]
        actions += DisplayAction.Engine(0)

        // 译模式：译文紧跟首选，点它 / 回车即可替换原文
        if (translation != null) {
            out += Candidate(text = translation.display)
            actions += DisplayAction.LiveTranslation
        }

        // 日期 / 时间 / 星期：首选后面紧跟真实值（riqi → 日期、2026年11月14日、2026-11-14）
        smartSuggestions(engine.composing.toString()).forEach { suggestion ->
            out += Candidate(text = suggestion.display)
            actions += DisplayAction.Direct(suggestion.commit)
        }

        // 长期不用的学习词排在普通候选后面（首选位不动：打全了正好是词时仍排第一）
        val stale = staleWord
        val rest = (1 until base.size).toList()
        val ordered = if (stale == null) rest else rest.sortedBy { if (stale(base[it].text)) 1 else 0 }
        ordered.forEach { index ->
            out += base[index]
            actions += DisplayAction.Engine(index)
        }

        val emoji = emojiSuggestion(base)
        if (emoji != null) {
            val index = minOf(EMOJI_CANDIDATE_SLOT, out.size)
            out.add(index, Candidate(text = emoji.display))
            actions.add(index, DisplayAction.Direct(emoji.commit, emoji.emoji))
        }

        displayActions = actions
        return out
    }

    private fun emojiSuggestion(base: List<Candidate>): EmojiCandidate? {
        val suggest = emojiSuggest ?: return null
        val head = base.firstOrNull()?.text ?: return null
        if (head.isEmpty()) return null
        return suggest(head)
    }

    /**
     * 候选栏渲染与点击之间会各算一次；同一次组词必须拿到同一份结果，
     * 否则跨过整分钟时「显示 15:52、提交 15:53」。
     */
    private var smartCacheKey: String? = null
    private var smartCacheValue: List<SmartCandidate> = emptyList()

    private fun smartSuggestions(raw: String): List<SmartCandidate> {
        if (raw == smartCacheKey) return smartCacheValue
        val value = smartSuggest?.invoke(raw).orEmpty()
        smartCacheKey = raw
        smartCacheValue = value
        return value
    }

    val composing: CharSequence
        get() = expression ?: engine.composing

    val hasComposition: Boolean
        get() = expression != null || engine.composing.isNotEmpty()

    /** 当前应该显示的键盘布局 */
    val currentLayout: KeyboardLayout
        get() = when (mode) {
            InputMode.NUMBER -> KeyboardLayouts.numbers
            InputMode.SYMBOL -> when {
                symbolPage == 2 -> KeyboardLayouts.symbolsExtra
                symbolPage == 1 -> KeyboardLayouts.symbolsMore
                else -> KeyboardLayouts.symbolsMainWithRecent(recentSymbols())
            }
            // 字母层：九键（中文）用 t9，其余（中文 / 英文 / 日语）用全键盘 letters。
            // 九键切到英文时回全键盘（对齐微信九键），日语本身就是 QWERTY 键位。
            else -> if (keyboardKind == KeyboardKind.T9 && mode == InputMode.CHINESE) {
                KeyboardLayouts.t9
            } else {
                KeyboardLayouts.letters
            }
        }

    private fun recentSymbols(): List<String> = recentSymbolsProvider?.invoke().orEmpty()

    private var lastShiftTapAt = 0L

    // ------------------------------------------------------------ 生命周期

    /**
     * @param keepLayer 系统「重启本次输入」（onStartInputView restarting=true）时保留当前层，
     *   避免输数字 / 切到符号层后被弹回字母层。新进入输入框（restarting=false）仍回到字母层。
     */
    fun onStartInput(connection: InputConnection?, info: EditorInfo?, keepLayer: Boolean = false) {
        inputConnection = connection
        editorInfo = info
        engine.reset()
        expression = null
        clearModifiers()
        if (!keepLayer) {
            // 重新进入输入框时回到字母层，数字 / 符号层不跨输入框保留
            symbolPage = 0
            // 一律回到「用户偏好的字母层」：上一个输入框可能是密码框（被临时切成了英文），
            // 那个临时状态不能被带到下一个普通输入框。
            mode = letterMode
        }
        // 密码 / 验证码输入框：**本次会话**强制英文，不写回用户偏好。
        // 默认中文之后必须这么做，否则会在密码框里组拼音、甚至把中文当密码上屏。
        if (info != null && isPasswordEditor(info)) mode = InputMode.ENGLISH
        engine.mode = mode
        notifyStateChanged()
    }

    /** 密码 / 验证码类输入框（按 EditorInfo.inputType 判定） */
    private fun isPasswordEditor(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun onFinishInput() {
        inputConnection?.finishComposingText()
        inputConnection = null
        editorInfo = null
        expression = null
        engine.reset()
        clearModifiers()
        notifyStateChanged()
    }

    /**
     * 选区 / composing 区域变化。
     *
     * 当应用侧主动取消了 composing 区域（例如用户点到了别处、或光标被移走），
     * 同步清空引擎状态，避免 composing 与实际内容不一致。
     */
    fun onSelectionChanged(composingStart: Int, composingEnd: Int) {
        // 逐字选词上屏后由我们自己 setComposingText 留下未选完的拼音；
        // 紧随其后的 commit 选区回调不应把它清掉。
        if (SystemClock.uptimeMillis() < suppressSelectionResetUntil) return
        if (composingStart < 0 && composingEnd < 0 && engine.composing.isNotEmpty()) {
            engine.reset()
            notifyStateChanged()
        }
    }

    fun clearModifiers() {
        shiftActive = false
        capsLock = false
        lastShiftTapAt = 0L
    }

    /** 由设置页切换中 / 英时调用 */
    fun setLetterMode(value: InputMode) {
        val normalized = if (value.isLetterMode) value else InputMode.ENGLISH
        letterMode = normalized
        if (mode.isLetterMode && mode != normalized) {
            switchMode(normalized)
        }
    }

    /** 由候选栏的模式标签调用：切换底层语言并回到字母层 */
    fun toggleLanguage() {
        // 日语键盘的「日/EN」键只在 日语 ↔ 英文 之间切换；全键盘 / 九键的「中/EN」在中英之间切换。
        val next = when (keyboardKind) {
            KeyboardKind.JAPANESE ->
                if (letterMode == InputMode.JAPANESE) InputMode.ENGLISH else InputMode.JAPANESE

            else -> letterMode.toggled()
        }
        letterMode = next
        switchMode(next)
    }

    /**
     * 由工具栏「切换键盘」图标调用：切到指定键盘种类并回到对应字母层。
     *
     * 先按原样结束当前组词（避免跨键盘残留拼音 / 罗马字），再切换布局与语言。
     * 日语键盘默认回日语、九键默认回中文、全键盘保留当前中英。
     */
    fun applyKeyboardKind(kind: KeyboardKind) {
        if (keyboardKind == kind) return
        keyboardKind = kind
        val target = when (kind) {
            KeyboardKind.QWERTY ->
                if (letterMode == InputMode.JAPANESE) InputMode.CHINESE else letterMode

            KeyboardKind.T9 -> InputMode.CHINESE
            KeyboardKind.JAPANESE -> InputMode.JAPANESE
        }
        if (expression != null) {
            expression = null
            inputConnection?.setComposingText("", CURSOR_AT_END)
            inputConnection?.finishComposingText()
        } else {
            commitCompositionInternal(preferCandidate = true)
        }
        letterMode = target
        mode = target
        engine.mode = target
        clearModifiers()
        notifyStateChanged()
    }

    // ------------------------------------------------------------ 按键

    fun onKey(key: KeySpec) {
        when (key.type) {
            KeyType.CHARACTER -> onCharacter(key)
            KeyType.T9_DIGIT -> onT9Digit(key)
            KeyType.SYMBOL -> onLiteral(key.text)
            KeyType.SHIFT -> onShift()
            KeyType.BACKSPACE -> onBackspace()
            KeyType.SPACE -> onSpace()
            KeyType.ENTER -> onEnter()
            KeyType.COMMA -> onLiteral(commaText)
            KeyType.PERIOD -> onLiteral(periodText)
            KeyType.PUNCTUATION -> onLiteral(commaText)
            KeyType.ENTER_NEWLINE -> onEnterNewline()
            KeyType.NOT_IMPLEMENTED -> onHint?.invoke("该功能尚未开发")
            // 语言键：切换底层语言；若当前在字母层则同时切换布局
            KeyType.LANGUAGE -> toggleLanguage()
            KeyType.TO_NUMBERS -> if (!enterExpressionIfRequested()) switchMode(InputMode.NUMBER)
            KeyType.TO_LETTERS -> switchMode(letterMode)
            KeyType.TO_SYMBOLS -> showSymbolPage(0)
            KeyType.TO_MORE_SYMBOLS -> showSymbolPage(1)
            KeyType.TO_EXTRA_SYMBOLS -> showSymbolPage(2)
            KeyType.TO_MAIN_SYMBOLS -> showSymbolPage(0)
            KeyType.VOICE -> onVoice?.invoke()
            KeyType.RESET -> discardComposition()
        }
    }

    /** 快捷面板（v + 空格）里的「符号」：直接切到常用符号页 */
    fun showSymbols() {
        showSymbolPage(0)
    }

    /** 切换符号页；已经在符号层时只需要刷新，不能再走 [switchMode]（它会在模式相同时直接返回） */
    private fun showSymbolPage(page: Int) {
        symbolPage = page
        if (mode != InputMode.SYMBOL) {
            switchMode(InputMode.SYMBOL)
        } else {
            notifyStateChanged()
        }
    }

    /** 长按退格时由键盘重复调用 */
    fun onKeyRepeat(key: KeySpec) {
        if (key.type == KeyType.BACKSPACE) onBackspace()
    }

    /**
     * 语音等**外部输入**写入前调用：丢弃当前组词（不把拼音当文本上屏）。
     *
     * 只用引擎公开的 reset + InputConnection，不触碰 Rime 内部状态、不销毁会话。
     * 目的：避免“zhongguo + 你好”这类残留。
     */
    fun discardComposition() {
        val connection = inputConnection
        val hadComposition = expression != null || engine.composing.isNotEmpty()
        expression = null
        engine.reset()
        if (hadComposition) {
            connection?.setComposingText("", CURSOR_AT_END)
            connection?.finishComposingText()
        }
        clearModifiers()
        notifyStateChanged()
    }

    fun pickCandidate(index: Int) {
        // 先按当前组词重建一次显示列表，保证插入位置与候选栏渲染时一致
        candidates
        when (val action = displayActions.getOrNull(index)) {
            is DisplayAction.Direct -> commitDirect(action)
            is DisplayAction.Engine -> {
                val replaced = engine.composing.length
                applyEngineResult(engine.pickCandidate(action.index), replaced)
                clearModifiers()
                notifyStateChanged()
            }

            DisplayAction.LiveTranslation -> commitLiveTranslation()
            null -> Unit
        }
    }

    /**
     * 上屏译模式的实时译文（回车 / 点候选）。
     *
     * 只替换「这次翻译覆盖的那段原文」：先丢弃正在组词的拼音，
     * 再按 [LiveTranslation.committedChars] 删掉光标前的原文，最后提交译文。
     * 不动当前行以外的内容。
     */
    fun commitLiveTranslation(): Boolean {
        val translation = liveTranslation ?: return false
        val connection = inputConnection ?: return false
        liveTranslation = null
        expression = null
        engine.reset()
        runCatching {
            connection.setComposingText("", CURSOR_AT_END)
            connection.finishComposingText()
            if (translation.committedChars > 0) {
                connection.deleteSurroundingText(translation.committedChars, 0)
            }
        }.onFailure { Log.w(TAG, "译模式替换原文失败：${it.javaClass.simpleName}") }
        commitText(translation.commit)
        clearModifiers()
        notifyStateChanged()
        return true
    }

    /** 直接上屏的候选（日期 / 时间 / 金额 / emoji）：丢弃组词或表达式，再提交文本 */
    private fun commitDirect(action: DisplayAction.Direct) {
        engine.reset()
        inputConnection?.let { connection ->
            connection.setComposingText("", CURSOR_AT_END)
            connection.finishComposingText()
        }
        commitText(action.commit)
        action.emoji?.let { onEmojiCommitted?.invoke(it) }
        if (expression != null) {
            expression = null
            mode = letterMode
            engine.mode = letterMode
        }
        clearModifiers()
        notifyStateChanged()
    }

    // ------------------------------------------------------------ 表达式（v + 数字）

    /**
     * 中文下只输入了 `v` 时点 123：不切普通数字层，改为进入表达式模式
     * （`v1+2*3` → 7、`v12345.67` → 金额）。
     */
    private fun enterExpressionIfRequested(): Boolean {
        if (mode != InputMode.CHINESE) return false
        val composing = engine.composing.toString()
        // 朙月拼音里 `v` 是 `ü` 的键位，speller / translator 的 preedit_format 可能把它
        // 显示成 `ü` 或 `u`；三种都按 v 表达式处理，避免「打了 v 却进不了表达式模式」。
        val isV = composing == "v" || composing == "ü" || composing == "u"
        Log.d(TAG, "enterExpression: mode=$mode composing='$composing' isV=$isV")
        if (!isV) return false
        engine.reset()
        expression = "v"
        mode = InputMode.NUMBER
        engine.mode = InputMode.NUMBER
        inputConnection?.setComposingText("v", CURSOR_AT_END)
        clearModifiers()
        notifyStateChanged()
        return true
    }

    /** 表达式模式下追加字符；只接受数字和四则运算符，`=` 立刻结算 */
    private fun appendExpression(text: String) {
        var current = expression ?: return
        for (char in text) {
            if (char == '=') {
                commitExpressionResult()
                return
            }
            val mapped = when (char) {
                '×' -> '*'
                '÷' -> '/'
                else -> char
            }
            val allowed = mapped.isDigit() || mapped in "+-*/()."
            if (!allowed || current.length >= MAX_EXPRESSION_LENGTH) continue
            current += mapped
        }
        if (current == expression) return
        expression = current
        inputConnection?.setComposingText(current, CURSOR_AT_END)
        notifyStateChanged()
    }

    /** 空格 / 回车 / `=`：提交第一个计算结果（结果无效时什么都不做） */
    private fun commitExpressionResult() {
        val raw = expression ?: return
        val suggestion = smartSuggest?.invoke(raw)?.firstOrNull() ?: return
        expression = null
        inputConnection?.setComposingText("", CURSOR_AT_END)
        inputConnection?.finishComposingText()
        commitText(suggestion.commit)
        mode = letterMode
        engine.mode = letterMode
        clearModifiers()
        notifyStateChanged()
    }

    /** 放弃表达式（不把 v1+2 当文本上屏），回到字母层 */
    private fun cancelExpression() {
        if (expression == null) return
        expression = null
        inputConnection?.setComposingText("", CURSOR_AT_END)
        inputConnection?.finishComposingText()
        mode = letterMode
        engine.mode = letterMode
        clearModifiers()
        notifyStateChanged()
    }

    private fun expressionBackspace() {
        val current = expression ?: return
        if (current.length <= 1) {
            cancelExpression()
            return
        }
        expression = current.dropLast(1)
        inputConnection?.setComposingText(expression.orEmpty(), CURSOR_AT_END)
        notifyStateChanged()
    }

    /** 只输入一个 `v` 再按空格：打开快捷面板（回调未注入时按普通空格处理） */
    private fun openVSpaceToolboxIfRequested(): Boolean {
        val callback = onVSpaceToolbox ?: return false
        if (mode != InputMode.CHINESE || engine.composing.toString() != "v") return false
        engine.reset()
        inputConnection?.setComposingText("", CURSOR_AT_END)
        inputConnection?.finishComposingText()
        clearModifiers()
        callback()
        return true
    }

    /** 点击候选栏里的组词区域：按原样把组词内容上屏 */
    fun commitCompositionAsIs() {
        if (expression != null) {
            val raw = expression.orEmpty()
            expression = null
            inputConnection?.setComposingText("", CURSOR_AT_END)
            inputConnection?.finishComposingText()
            commitText(raw)
            mode = letterMode
            engine.mode = letterMode
            clearModifiers()
            notifyStateChanged()
            return
        }
        commitCompositionInternal(preferCandidate = false)
        notifyStateChanged()
    }

    /** 光标左右移动（工具栏 / 空格键滑动），delta 为 -1 / +1，steps 为连续移动的字符数 */
    fun moveCursor(delta: Int, steps: Int = 1) {
        val connection = inputConnection ?: return
        if (delta == 0 || steps <= 0) return
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(steps) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /**
     * 删除。优先级：
     * 1. 有选中文本 → 删除选中（并结束组词）
     * 2. 引擎组词（拼音 / 英文联想）→ 删除组词
     * 3. 前一个字符
     */
    fun onBackspace() {
        if (expression != null) {
            expressionBackspace()
            return
        }
        val connection = inputConnection
        val selected = connection?.getSelectedText(0)
        if (connection != null && !selected.isNullOrEmpty()) {
            engine.reset()
            connection.commitText("", CURSOR_AT_END)
            clearModifiers()
            notifyStateChanged()
            return
        }

        if (!dispatch(EngineKey.Backspace)) deleteBackward()
        notifyStateChanged()
    }

    fun onSpace() {
        if (expression != null) {
            commitExpressionResult()
            return
        }
        if (openVSpaceToolboxIfRequested()) {
            notifyStateChanged()
            return
        }
        if (!dispatch(EngineKey.Space)) commitText(" ")
        notifyStateChanged()
    }

    fun onEnter() {
        if (expression != null) {
            commitExpressionResult()
            return
        }
        // 引擎会先把未确认的组词内容按原样上屏（“回车确认”）
        dispatch(EngineKey.Enter)
        performEditorAction()
        notifyStateChanged()
    }

    /**
     * 只插入换行：先把未完成的组词按原样上屏，再提交一个换行。
     * 入口：长按回车键、数字 / 符号层底部的「换行」键。
     */
    fun onEnterNewline() {
        dispatch(EngineKey.Enter)
        commitText("\n")
        notifyStateChanged()
    }

    /**
     * 字母键按住下滑的快捷操作：
     * z = 全选、x = 剪切、c = 复制、v = 粘贴最新剪贴板内容。
     *
     * 剪切 / 复制交给编辑器自己的上下文菜单动作，选区语义与 App 保持一致；
     * 粘贴直接读取系统剪贴板并上屏（超长文本会分块提交）。
     */
    fun onLetterSwipeDown(letter: String) {
        val connection = inputConnection ?: return
        val action = letter.lowercase()
        // 先把未确认的组词按原样上屏，避免操作到 composing 区域
        dispatch(EngineKey.Enter)
        when (action) {
            "z" -> connection.performContextMenuAction(android.R.id.selectAll)
            "x", "c" -> {
                val menuAction = if (action == "x") android.R.id.cut else android.R.id.copy
                if (!connection.performContextMenuAction(menuAction)) {
                    onHint?.invoke("没有选中文字")
                }
            }

            "v" -> {
                val text = clipboardText?.invoke()?.toString().orEmpty()
                if (text.isEmpty()) onHint?.invoke("剪贴板为空") else commitText(text)
            }

            else -> return
        }
        clearModifiers()
        notifyStateChanged()
    }

    /** 「，。」键上滑 / 长按：句号（中文 / 日语「。」/ 英文「.」） */
    fun onPunctuationPeriod() {
        onLiteral(periodText)
        notifyStateChanged()
    }

    private fun onCharacter(key: KeySpec) {
        val text = key.text ?: return
        if (expression != null) {
            appendExpression(text)
            return
        }
        val output = if (shiftActive || capsLock) text.uppercase() else text
        applyEngineResult(engine.handleKey(EngineKey.Letter(output[0])))
        if (shiftActive && !capsLock) shiftActive = false
        notifyStateChanged()
    }

    /** 九键数字键：2–9 走 T9 拼音引擎；「1」为分词键（对齐大厂九键） */
    private fun onT9Digit(key: KeySpec) {
        val text = key.text ?: return
        if (text == "1") {
            onT9Segment()
            return
        }
        if (expression != null) {
            appendExpression(text)
            return
        }
        applyEngineResult(engine.handleKey(EngineKey.Digit(text[0])))
        notifyStateChanged()
    }

    /** 「1」分词：正在组词且已有候选时，把首选词上屏、剩余音节继续组词；无组词则进符号页 */
    private fun onT9Segment() {
        val composingLen = engine.composing.length
        if (composingLen > 0 && engine.candidates().isNotEmpty()) {
            applyEngineResult(engine.pickCandidate(0), composingLen)
        } else {
            showSymbolPage(0)
        }
        notifyStateChanged()
    }

    /** 逗号：中文「，」/ 日语「、」/ 其它「,」 */
    private val commaText: String
        get() = when (letterMode) {
            InputMode.CHINESE -> "，"
            InputMode.JAPANESE -> "、"
            else -> ","
        }

    /** 句号：中文 / 日语「。」/ 其它「.」 */
    private val periodText: String
        get() = when (letterMode) {
            InputMode.CHINESE, InputMode.JAPANESE -> "。"
            else -> "."
        }

    /** 符号层固定文本：先提交未完成的组词，再输出文本 */
    private fun onLiteral(text: String?) {
        val value = text ?: return
        if (value.isEmpty()) return
        if (expression != null) {
            appendExpression(value)
            return
        }
        if (!dispatch(EngineKey.Punctuation(value))) commitText(value)
        // 记录「最近使用符号」：只记单个可见字符（字母 / 数字键不走这里），
        // 置顶时再过滤掉第一页本就有的常用标点，避免重复
        if (value.length == 1 && !value[0].isWhitespace()) {
            onSymbolCommitted?.invoke(value)
        }
        notifyStateChanged()
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTapAt <= DOUBLE_TAP_TIMEOUT_MS
        lastShiftTapAt = now

        if (doubleTap) {
            // 双击 Shift：切换大写锁定
            capsLock = !capsLock
            shiftActive = false
        } else {
            shiftActive = !shiftActive
            if (!shiftActive) capsLock = false
        }
        notifyStateChanged()
    }

    private fun switchMode(newMode: InputMode) {
        if (mode == newMode) return
        if (expression != null) {
            // 回到字母层：表达式作废；数字 ↔ 符号之间切换则继续编辑
            if (newMode.isLetterMode) {
                cancelExpression()
                return
            }
            mode = newMode
            engine.mode = newMode
            clearModifiers()
            notifyStateChanged()
            return
        }
        commitCompositionInternal(preferCandidate = true)
        mode = newMode
        engine.mode = newMode
        clearModifiers()
        notifyStateChanged()
    }

    /**
     * 离开组词状态。
     *
     * - 中文：优先上屏第一个候选（拼音不该以字母形式留在输入框里）
     * - 英文 / 数字 / 符号：按原样上屏，**不额外补空格**
     *   （补空格是「点候选上屏」的行为，切层时补空格会让 "user" 变成 "user "）
     */
    private fun commitCompositionInternal(preferCandidate: Boolean) {
        val raw = engine.composing.toString()
        if (raw.isEmpty()) return

        val useCandidate = preferCandidate &&
            mode == InputMode.CHINESE &&
            engine.candidates().isNotEmpty()
        if (useCandidate) {
            applyEngineResult(engine.pickCandidate(0))
            return
        }

        engine.reset()
        commitWithSpacing(raw, 0)
    }

    /** @return true 表示引擎已经处理，false 表示需要走默认行为 */
    private fun dispatch(key: EngineKey): Boolean {
        val replaced = engine.composing.length
        val result = engine.handleKey(key)
        applyEngineResult(result, replaced)
        return result !is EngineResult.Ignored
    }

    /**
     * @param replacedComposing 本次上屏会替换掉的组词长度（用于找到光标前真正的
     *   已提交字符，中英文自动空格按它判断边界）
     */
    private fun applyEngineResult(result: EngineResult, replacedComposing: Int = 0) {
        when (result) {
            is EngineResult.Composing -> inputConnection?.setComposingText(result.text, CURSOR_AT_END)
            is EngineResult.Commit -> {
                if (result.text.isNotEmpty()) {
                    commitWithSpacing(result.text, replacedComposing)
                } else if (result.clearComposition) {
                    inputConnection?.finishComposingText()
                }
                if (result.composing.isNotEmpty()) {
                    inputConnection?.setComposingText(result.composing, CURSOR_AT_END)
                    suppressSelectionResetUntil = SystemClock.uptimeMillis() + SELECTION_RESET_GRACE_MS
                }
            }

            EngineResult.Handled, EngineResult.Ignored -> Unit
        }
    }

    // ------------------------------------------------------------ InputConnection 操作

    /**
     * 上屏。
     *
     * 短文本直接 `commitText`；**超长文本分块提交**。
     *
     * 原因：`commitText` 是进程间调用，Binder 事务缓冲是**进程级共享**的（约 1MB），
     * 一次塞进去几 MB 会抛 `TransactionTooLargeException`。分块之后每一笔事务都远小于上限，
     * Board 自己不再设置「人为的低字数上限」——真正能不能收下由目标 App 决定。
     *
     * @return 是否全部成功上屏（失败时会记日志，不抛异常）
     */
    fun commitText(text: CharSequence): Boolean {
        val connection = inputConnection ?: return false
        val value = text.toString()
        if (value.isEmpty()) return true
        return commitValue(connection, withAutoSpace(connection, value, 0))
    }

    /** 引擎上屏（会替换 [replacedComposing] 个字符的组词）时套用自动空格 */
    private fun commitWithSpacing(text: String, replacedComposing: Int) {
        val connection = inputConnection ?: return
        commitValue(connection, withAutoSpace(connection, text, replacedComposing))
        onWordCommitted?.invoke(text)
    }

    /**
     * 光标前真正的「已提交」字符：先读回组词 + 1 个字符，再把组词部分去掉。
     * 组词还没上屏时它同样占着输入框，直接读前一个字符会读到拼音。
     */
    private fun charBefore(connection: InputConnection, replacedComposing: Int): Char? {
        if (replacedComposing < 0) return null
        val before = runCatching { connection.getTextBeforeCursor(replacedComposing + 1, 0) }
            .getOrNull() ?: return null
        if (before.length <= replacedComposing) return null
        return before[before.length - replacedComposing - 1]
    }

    /** 按当前设置在中英文边界补空格；关闭时原样返回 */
    private fun withAutoSpace(
        connection: InputConnection,
        text: String,
        replacedComposing: Int,
    ): String {
        val config = autoSpace?.invoke() ?: return text
        if (!config.enabled || text.isEmpty()) return text
        val before = charBefore(connection, replacedComposing)
        val after = runCatching { connection.getTextAfterCursor(1, 0)?.firstOrNull() }
            .getOrNull()
        val leading = config.needsSpace(before, text.first())
        val trailing = config.needsSpace(text.last(), after)
        if (!leading && !trailing) return text
        return buildString(text.length + 2) {
            if (leading) append(' ')
            append(text)
            if (trailing) append(' ')
        }
    }

    private fun commitValue(connection: InputConnection, value: String): Boolean {
        if (value.isEmpty()) return true

        if (value.length <= COMMIT_CHUNK_CHARS) {
            return commitChunk(connection, value)
        }

        // 超长：按固定长度切块，逐块提交
        var index = 0
        var chunks = 0
        while (index < value.length) {
            val end = minOf(index + COMMIT_CHUNK_CHARS, value.length)
            if (!commitChunk(connection, value.substring(index, end))) {
                android.util.Log.w(
                    TAG,
                    "超长文本上屏中断：chars=${value.length} 已提交块=$chunks 位置=$index",
                )
                return false
            }
            chunks++
            index = end
        }
        android.util.Log.i(TAG, "超长文本已分块上屏：chars=${value.length} 块数=$chunks")
        return true
    }

    /** 提交一块；捕获 TransactionTooLargeException 等 IPC 异常并记录真实失败类型 */
    private fun commitChunk(connection: InputConnection, chunk: String): Boolean = try {
        connection.commitText(chunk, CURSOR_AT_END)
        true
    } catch (tooLarge: android.os.TransactionTooLargeException) {
        android.util.Log.w(TAG, "上屏失败：TransactionTooLargeException（chars=${chunk.length}）")
        false
    } catch (throwable: Throwable) {
        android.util.Log.w(
            TAG,
            "上屏失败：${throwable.javaClass.simpleName}: ${throwable.message}（chars=${chunk.length}）",
        )
        false
    }

    fun deleteBackward() {
        val connection = inputConnection ?: return
        // 1) 有选中内容：删除选中
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", CURSOR_AT_END)
            return
        }
        // 2) 删除前一个字符，失败时退化成发送删除键
        if (!connection.deleteSurroundingText(1, 0)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    /**
     * 执行 EditorInfo 声明的 editor action（搜索 / 发送 / 完成…）。
     *
     * 短按回车只在编辑器声明了动作时生效；没有声明就什么都不做，
     * 不再回退成 Enter 键事件——换行统一由长按回车触发（见 [onEnterNewline]）。
     */
    private fun performEditorAction() {
        val connection = inputConnection ?: return
        val info = editorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val hasAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (!noEnterAction && hasAction) connection.performEditorAction(action)
    }

    private fun notifyStateChanged() {
        onStateChanged?.invoke()
    }

    private companion object {
        const val CURSOR_AT_END = 1
        const val DOUBLE_TAP_TIMEOUT_MS = 300L

        /** 逐字选词后忽略「选区取消 composing」回调的时间窗 */
        const val SELECTION_RESET_GRACE_MS = 400L
        const val TAG = "BoardIme"

        /**
         * 单次 `commitText` 的最大字符数。
         *
         * 50,000 个字符（中文最多约 150KB UTF-8）加上 Parcel 开销后仍明显小于
         * Binder 约 1MB 的事务上限，所以一次提交是安全的；超过这个长度才切块。
         */
        const val COMMIT_CHUNK_CHARS = 50_000

        /** 表情推荐在候选栏里的位置（第三个候选，从 0 数起 = 2） */
        const val EMOJI_CANDIDATE_SLOT = 2

        /** 表达式最大长度（与 SmartInput 的上限保持一致） */
        const val MAX_EXPRESSION_LENGTH = 48
    }
}
