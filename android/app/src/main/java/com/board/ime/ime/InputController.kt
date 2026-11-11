package com.board.ime.ime

import android.os.SystemClock
import android.text.InputType
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

    var shiftActive: Boolean = false
        private set

    var capsLock: Boolean = false
        private set

    /** 当前输入状态（四种之一） */
    var mode: InputMode = InputMode.ENGLISH
        private set

    /** 数字 / 符号层背后“记住的字母层”，按 ABC 时回到这里 */
    var letterMode: InputMode = InputMode.ENGLISH
        private set

    init {
        letterMode = if (initialLetterMode.isLetterMode) initialLetterMode else InputMode.ENGLISH
        mode = letterMode
        engine.mode = mode
    }

    /** 符号层页码：0 = 常用符号，1 = 更多符号 */
    private var symbolPage: Int = 0

    /** 数字层页码：0 = 微信「123」，1 = 微信「34」九宫格小键盘 */
    private var numberPage: Int = 0

    val candidates: List<Candidate>
        get() = engine.candidates()

    val composing: CharSequence
        get() = engine.composing

    val hasComposition: Boolean
        get() = engine.composing.isNotEmpty()

    /** 当前应该显示的键盘布局 */
    val currentLayout: KeyboardLayout
        get() = when (mode) {
            InputMode.ENGLISH, InputMode.CHINESE -> KeyboardLayouts.letters
            InputMode.NUMBER -> if (numberPage == 1) KeyboardLayouts.numbersPad else KeyboardLayouts.numbers
            InputMode.SYMBOL -> when {
                symbolPage == 1 -> KeyboardLayouts.symbolsMore
                letterMode == InputMode.CHINESE -> KeyboardLayouts.symbolsCjk
                else -> KeyboardLayouts.symbolsAscii
            }
        }

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
        val next = letterMode.toggled()
        letterMode = next
        switchMode(next)
    }

    // ------------------------------------------------------------ 按键

    fun onKey(key: KeySpec) {
        when (key.type) {
            KeyType.CHARACTER -> onCharacter(key)
            KeyType.SYMBOL -> onLiteral(key.text)
            KeyType.SHIFT -> onShift()
            KeyType.BACKSPACE -> onBackspace()
            KeyType.SPACE -> onSpace()
            KeyType.ENTER -> onEnter()
            KeyType.COMMA -> onLiteral(if (letterMode == InputMode.CHINESE) "，" else ",")
            KeyType.PERIOD -> onLiteral(if (letterMode == InputMode.CHINESE) "。" else ".")
            KeyType.PUNCTUATION -> onLiteral(if (letterMode == InputMode.CHINESE) "，" else ",")
            KeyType.ENTER_NEWLINE -> onEnterNewline()
            KeyType.NUMBER_PAGE -> onNumberPage()
            KeyType.NOT_IMPLEMENTED -> onHint?.invoke("该功能尚未开发")
            // 语言键：切换底层语言；若当前在字母层则同时切换布局
            KeyType.LANGUAGE -> toggleLanguage()
            KeyType.TO_NUMBERS -> switchMode(InputMode.NUMBER)
            KeyType.TO_LETTERS -> switchMode(letterMode)
            KeyType.TO_SYMBOLS -> showSymbolPage(0)
            KeyType.TO_MORE_SYMBOLS -> showSymbolPage(1)
            KeyType.TO_MAIN_SYMBOLS -> showSymbolPage(0)
        }
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
        val hadComposition = engine.composing.isNotEmpty()
        engine.reset()
        if (hadComposition) {
            connection?.setComposingText("", CURSOR_AT_END)
            connection?.finishComposingText()
        }
        clearModifiers()
        notifyStateChanged()
    }

    fun pickCandidate(index: Int) {
        applyEngineResult(engine.pickCandidate(index))
        clearModifiers()
        notifyStateChanged()
    }

    /** 点击候选栏里的组词区域：按原样把组词内容上屏 */
    fun commitCompositionAsIs() {
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
        if (!dispatch(EngineKey.Space)) commitText(" ")
        notifyStateChanged()
    }

    fun onEnter() {
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

    /** 「，。」键长按：句号（中文「。」/ 英文「.」） */
    fun onPunctuationLongPress() {
        onLiteral(if (letterMode == InputMode.CHINESE) "。" else ".")
        notifyStateChanged()
    }

    /** 数字层第二页（九宫格数字小键盘）切换：微信数字层底部的「12/34」 */
    fun onNumberPage() {
        numberPage = if (numberPage == 1) 0 else 1
        notifyStateChanged()
    }

    private fun onCharacter(key: KeySpec) {
        val text = key.text ?: return
        val output = if (shiftActive || capsLock) text.uppercase() else text
        applyEngineResult(engine.handleKey(EngineKey.Letter(output[0])))
        if (shiftActive && !capsLock) shiftActive = false
        notifyStateChanged()
    }

    /** 符号层固定文本：先提交未完成的组词，再输出文本 */
    private fun onLiteral(text: String?) {
        val value = text ?: return
        if (value.isEmpty()) return
        if (!dispatch(EngineKey.Punctuation(value))) commitText(value)
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
        commitCompositionInternal(preferCandidate = true)
        mode = newMode
        engine.mode = newMode
        // 切层时数字层回到第一页（微信「123」）
        numberPage = 0
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
        inputConnection?.commitText(raw, CURSOR_AT_END)
    }

    /** @return true 表示引擎已经处理，false 表示需要走默认行为 */
    private fun dispatch(key: EngineKey): Boolean {
        val result = engine.handleKey(key)
        applyEngineResult(result)
        return result !is EngineResult.Ignored
    }

    private fun applyEngineResult(result: EngineResult) {
        when (result) {
            is EngineResult.Composing -> inputConnection?.setComposingText(result.text, CURSOR_AT_END)
            is EngineResult.Commit -> {
                if (result.text.isNotEmpty()) {
                    inputConnection?.commitText(result.text, CURSOR_AT_END)
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
    }
}
