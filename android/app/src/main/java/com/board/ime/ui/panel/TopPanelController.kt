package com.board.ime.ui.panel

import com.board.ime.voice.VoiceState

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.board.ime.clipboard.ClipboardCounts
import com.board.ime.clipboard.ClipboardSummary
import com.board.ime.engine.Candidate
import com.board.ime.engine.InputMode
import com.board.ime.keyboard.KeySpec
import com.board.ime.keyboard.KeyType
import com.board.ime.ui.KeyboardTheme

/**
 * 顶部面板控制器。
 *
 * 只做两件事：
 * 1. 决定 TopPanel 当前显示哪一种状态（四种互斥，**高度始终相同**）
 * 2. 把面板里的交互事件分发给宿主
 *
 * ```
 * 没有 composing  -> TOOLBAR
 * 有 composing    -> CANDIDATES
 * 点了工具栏入口  -> MORE（直到收起、或开始输入）
 * 点了剪贴板      -> CLIPBOARD（退出后按前两条回退）
 * 剪贴板里点 🔍   -> CLIPBOARD + 搜索（TopPanel 高度不变，结果区有高度上限）
 * 剪贴板里点 ☁    -> CLIPBOARD + 只看云端（同一块区域，不改变高度）
 * ```
 *
 * 性能：
 * - `applyState()` 只在「状态 / 布局模式真的变了」时才动 View（避免每次按键都走一遍
 *   setVisibility + 布局分发）
 * - 状态切换只给 TopPanel 里那块内容做一次很克制的 alpha + 4dp 位移渐变，
 *   **键盘主体完全不参与动画**，所以不会上下跳动
 * - 系统开启「移除动画」（animator duration scale = 0）时直接跳过动画
 */
class TopPanelController(
    private val toolbar: ToolbarView,
    private val candidateBar: CandidateBar,
    private val morePanel: MorePanel,
    private val clipboardHeader: ClipboardHeader,
    private val clipboardPanel: ClipboardPanel,
    private val voiceHeader: VoiceHeader,
    private val translationHeader: TranslationHeader,
    private val translationPanel: TranslationPanel,
) {

    var onMoreAction: ((MorePanel.Action) -> Unit)? = null
    var onCandidateSelected: ((Int) -> Unit)? = null
    var onComposingClicked: (() -> Unit)? = null

    /** 点剪贴板条目：直接插进输入框 */
    var onClipboardInsert: ((ClipboardSummary) -> Unit)? = null
    /** 「全文」按 id 取内容（IO 线程读，回主线程回调） */
    var onClipboardLoadContent: ((Long, (String?) -> Unit) -> Unit)? = null
    var onClipboardTogglePin: ((ClipboardSummary) -> Unit)? = null
    var onClipboardToggleFavorite: ((ClipboardSummary) -> Unit)? = null
    var onClipboardDelete: ((ClipboardSummary) -> Unit)? = null

    /** 清空全部历史；参数为 true 表示连固定 / 收藏一起删 */
    var onClipboardClearHistory: ((Boolean) -> Unit)? = null

    /** 面板下面那块区域怎么摆（键盘 / 内容区 / 两者） */
    var onPanelLayoutChanged: ((PanelLayoutMode) -> Unit)? = null

    /** 刚进入剪贴板：宿主可以趁机同步一次系统剪贴板 */
    var onClipboardOpened: (() -> Unit)? = null

    /** 搜索关键字变化：宿主据此重新过滤 Room 里的历史 */
    var onSearchQueryChanged: ((String) -> Unit)? = null

    /** 剪贴板筛选变化（只看云端 / 看全部） */
    var onClipboardFilterChanged: ((Boolean) -> Unit)? = null

    /** 「收起键盘」：宿主调用 InputMethodService 的隐藏能力 */
    var onCollapseKeyboard: (() -> Unit)? = null

    // ---------------------------------------------------------------- 语音

    /** 语音面板里的「←」：取消当前识别 */
    var onVoiceCancel: (() -> Unit)? = null

    /** 语音面板里的「✓」：结束收音（已返回的结果照常上屏） */
    var onVoiceStop: (() -> Unit)? = null

    /** 语音面板底部「系统语音识别 · 可能联网」被点击：查看完整隐私说明 */
    var onVoiceInfo: (() -> Unit)? = null

    /** 语音面板右侧麦克风被点击：**结束本次识别**（不是提交） */
    var onVoiceMicStop: (() -> Unit)? = null

    /** 打开语音面板（与其它状态共用同一个固定高度） */
    fun showVoice() {
        state = TopPanelState.VOICE
        applyState()
    }

    /**
     * 语音面板处于「异常提示」时为 true。
     *
     * 这类提示（没有识别服务 / 没有麦克风权限 / 服务暂不可用）由**用户明确退出**才消失：
     * 输入视图刷新、onStartInputView、layout 变化都不得把它冲掉，否则用户什么也看不到
     * （真机上 Toast 还会被系统抑制，等于完全静默失败）。
     */
    private var voiceSticky = false

    /** 刷新语音面板（partial 预览 / 状态文字） */
    fun updateVoice(state: VoiceState, text: String) {
        voiceSticky = state == VoiceState.ERROR
        voiceHeader.render(state, text)
    }

    /** 关闭语音面板（用户返回 / 识别成功 / 切换其它工具） */
    fun closeVoice() {
        voiceSticky = false
        if (this.state != TopPanelState.VOICE) return
        voiceHeader.render(VoiceState.IDLE, "")
        this.state = derivedState()
        applyState()
    }

    // ---------------------------------------------------------------- 翻译

    var onTranslationClose: (() -> Unit)? = null
    var onTranslationRun: (() -> Unit)? = null
    var onTranslationCopy: (() -> Unit)? = null
    var onTranslationReplace: (() -> Unit)? = null
    var onTranslationCycleSource: (() -> Unit)? = null
    var onTranslationCycleTarget: (() -> Unit)? = null
    var onTranslationOpenSettings: (() -> Unit)? = null
    var onTranslationInfo: (() -> Unit)? = null

    /** 打开翻译面板（固定高度不变，内容区占键盘位置） */
    fun showTranslation() {
        state = TopPanelState.TRANSLATION
        applyState()
    }

    /** 退出翻译面板：回到常规状态（**翻译数据由调用方负责清空**） */
    fun closeTranslation() {
        if (this.state != TopPanelState.TRANSLATION) return
        this.state = derivedState()
        applyState()
    }

    fun updateTranslation(
        state: com.board.ime.translation.TranslationUiState,
        sourceText: String,
        resultText: String,
        source: com.board.ime.translation.TranslationLanguage,
        target: com.board.ime.translation.TranslationLanguage,
        message: String,
    ) {
        translationPanel.render(state, sourceText, resultText, source, target, message)
    }

    /** 服务商透明：例如「百度翻译」 */
    fun setTranslationServiceName(name: String) {
        translationHeader.setServiceName(name)
    }

    /** 布局变化（宿主负责持久化） */
    var onToolLayoutChanged: ((ToolLayout) -> Unit)? = null

    /** 工具栏已满（最多 6 个），无法再加入 */
    var onToolbarFull: (() -> Unit)? = null

    /** 当前工具栏 / 「更多」布局；赋值即驱动两个面板重排 */
    var toolLayout: ToolLayout = ToolLayout.DEFAULT
        set(value) {
            field = value
            toolbar.setTools(value.toolbar)
            morePanel.setTools(value.more)
        }

    private fun publishToolLayout(layout: ToolLayout) {
        if (layout == toolLayout) return
        toolLayout = layout
        onToolLayoutChanged?.invoke(layout)
    }

    /** 工具栏里的工具点击：剪贴板就地打开，其余与「更多」走同一条路 */
    private fun dispatchToolAction(action: MorePanel.Action) {
        if (action == MorePanel.Action.CLIPBOARD) showClipboard() else onMoreAction?.invoke(action)
    }

    private var state: TopPanelState = TopPanelState.TOOLBAR
    private var composing: CharSequence = ""
    private var searching = false
    private var query = ""
    private var remoteOnly = false

    /** 上一次真正应用到 View 上的状态，用来跳过无意义的 setVisibility */
    private var appliedState: TopPanelState? = null
    private var appliedLayout: PanelLayoutMode? = null

    /** 状态切换动画的位移（取 token 里的最小间距，约 4dp） */
    private var enterOffsetPx: Float = 8f

    val currentState: TopPanelState
        get() = state

    val isSearching: Boolean
        get() = searching

    val searchQuery: String
        get() = query

    /** 是否只看云端（REMOTE 来源）剪贴板 */
    val isRemoteOnly: Boolean
        get() = remoteOnly

    init {
        toolbar.onAction = { action ->
            when (action) {
                ToolbarView.Action.OPEN_MORE -> showMore()
                ToolbarView.Action.COLLAPSE_KEYBOARD -> onCollapseKeyboard?.invoke()
            }
        }
        toolbar.onToolClick = { tool -> dispatchToolAction(tool.action) }
        toolbar.onReorder = { from, to -> publishToolLayout(toolLayout.reorderToolbar(from, to)) }
        toolbar.onMoveToMore = { tool -> publishToolLayout(toolLayout.moveToMore(tool)) }
        morePanel.onReorder = { from, to -> publishToolLayout(toolLayout.reorderMore(from, to)) }
        morePanel.onMoveToToolbar = { tool ->
            val next = toolLayout.moveToToolbar(tool)
            if (next == null) {
                onToolbarFull?.invoke()
            } else {
                publishToolLayout(next)
            }
        }
        morePanel.onAction = { action ->
            when (action) {
                MorePanel.Action.CLOSE -> closeMore()
                MorePanel.Action.CLIPBOARD -> showClipboard()
                else -> onMoreAction?.invoke(action)
            }
        }
        candidateBar.onCandidateSelected = { index -> onCandidateSelected?.invoke(index) }
        candidateBar.onComposingClicked = { onComposingClicked?.invoke() }

        clipboardHeader.onBack = { handleClipboardBack() }
        voiceHeader.onBack = { onVoiceCancel?.invoke() }
        voiceHeader.onPrimary = { onVoiceStop?.invoke() }
        voiceHeader.onInfo = { onVoiceInfo?.invoke() }
        voiceHeader.onStop = { onVoiceMicStop?.invoke() }

        translationHeader.onBack = { onTranslationClose?.invoke() }
        translationHeader.onInfo = { onTranslationInfo?.invoke() }
        translationPanel.onTranslate = { onTranslationRun?.invoke() }
        translationPanel.onCopy = { onTranslationCopy?.invoke() }
        translationPanel.onReplace = { onTranslationReplace?.invoke() }
        translationPanel.onCycleSource = { onTranslationCycleSource?.invoke() }
        translationPanel.onCycleTarget = { onTranslationCycleTarget?.invoke() }
        translationPanel.onOpenSettings = { onTranslationOpenSettings?.invoke() }
        clipboardHeader.onToggleEdit = { toggleClipboardEdit() }
        clipboardHeader.onToggleSearch = { toggleSearch() }
        clipboardHeader.onClearSearch = { stopSearch() }
        clipboardHeader.onToggleRemoteFilter = { toggleRemoteFilter() }

        clipboardPanel.onInsert = { item -> onClipboardInsert?.invoke(item) }
        clipboardPanel.onTogglePin = { item -> onClipboardTogglePin?.invoke(item) }
        clipboardPanel.onToggleFavorite = { item -> onClipboardToggleFavorite?.invoke(item) }
        clipboardPanel.onDelete = { item -> onClipboardDelete?.invoke(item) }
        clipboardPanel.onLoadContent = { id, onLoaded -> onClipboardLoadContent?.invoke(id, onLoaded) }
        clipboardPanel.onClearHistory = { includeProtected ->
            onClipboardClearHistory?.invoke(includeProtected)
        }
        applyState()
    }

    fun updateInput(
        candidates: List<Candidate>,
        mode: InputMode,
        composing: CharSequence,
        statusText: String?,
    ) {
        this.composing = composing
        candidateBar.render(candidates, mode, composing, statusText)

        when {
            // 剪贴板是用户显式打开的，不被输入状态打断
            // 剪贴板 / 语音都是用户显式打开的，不被输入状态打断
            state == TopPanelState.CLIPBOARD || state == TopPanelState.VOICE -> Unit
            // 一开始输入就切到候选栏
            composing.isNotEmpty() -> state = TopPanelState.CANDIDATES
            // 输入结束（composing 清空）后从候选栏退回工具栏
            state == TopPanelState.CANDIDATES -> state = TopPanelState.TOOLBAR
            else -> Unit
        }
        applyState()
    }

    fun showMore() {
        if (state == TopPanelState.MORE) return
        state = TopPanelState.MORE
        applyState()
    }

    fun closeMore() {
        if (state != TopPanelState.MORE) return
        state = derivedState()
        applyState()
    }

    fun showClipboard() {
        if (state == TopPanelState.CLIPBOARD) return
        setClipboardEditing(false)
        stopSearch(notify = false)
        remoteOnly = false
        clipboardHeader.setRemoteFilter(false)
        state = TopPanelState.CLIPBOARD
        applyState()
        onClipboardOpened?.invoke()
        onClipboardFilterChanged?.invoke(false)
    }

    fun closeClipboard() {
        if (state != TopPanelState.CLIPBOARD) return
        setClipboardEditing(false)
        stopSearch(notify = false)
        remoteOnly = false
        clipboardHeader.setRemoteFilter(false)
        state = derivedState()
        applyState()
        onClipboardFilterChanged?.invoke(false)
    }

    /**
     * 剪贴板里的返回：先退临时视图（动作条 / 全文 / 清空确认），
     * 再退搜索 / 云端筛选，最后才关闭剪贴板本身。
     */
    fun handleClipboardBack() {
        if (state != TopPanelState.CLIPBOARD) return
        if (clipboardPanel.handleBack()) return
        if (searching) {
            stopSearch()
            return
        }
        if (remoteOnly) {
            setRemoteOnly(false)
            return
        }
        closeClipboard()
    }

    /** 输入框切换 / 失去焦点：回到初始状态（语音异常提示除外，见 voiceSticky） */
    fun resetToIdle() {
        composing = ""
        candidateBar.clear()
        setClipboardEditing(false)
        stopSearch(notify = false)
        remoteOnly = false
        clipboardHeader.setRemoteFilter(false)
        // 语音不可用的提示必须留到用户明确退出为止，不能被输入视图刷新冲掉
        state = if (voiceSticky) TopPanelState.VOICE else TopPanelState.TOOLBAR
        applyState()
    }

    fun submitClipboard(items: List<ClipboardSummary>, counts: ClipboardCounts) {
        clipboardPanel.submit(items, counts)
    }

    fun applyTheme(theme: KeyboardTheme) {
        enterOffsetPx = theme.spaceS.toFloat()
        toolbar.applyTheme(theme)
        candidateBar.applyTheme(theme)
        morePanel.applyTheme(theme)
        clipboardHeader.applyTheme(theme)
        clipboardPanel.applyTheme(theme)
        voiceHeader.applyTheme(theme)
        translationHeader.applyTheme(theme)
        translationPanel.applyTheme(theme)
    }

    // ---------------------------------------------------------------- 搜索 / 筛选

    /** 打开 / 关闭搜索（同一个 TopPanel，只是原地换画法） */
    private fun toggleSearch() {
        if (searching) stopSearch() else startSearch()
    }

    private fun startSearch() {
        if (searching) return
        setClipboardEditing(false)
        searching = true
        query = ""
        clipboardHeader.setSearching(true)
        clipboardHeader.setQuery(query)
        clipboardPanel.setSearching(true)
        onSearchQueryChanged?.invoke(query)
        applyState()
    }

    private fun stopSearch(notify: Boolean = true) {
        if (!searching) return
        searching = false
        query = ""
        clipboardHeader.setSearching(false)
        clipboardHeader.setQuery(query)
        clipboardPanel.setSearching(false)
        if (notify) onSearchQueryChanged?.invoke(query)
        applyState()
    }

    /** 只看「其他设备同步过来」的剪贴板 */
    private fun toggleRemoteFilter() {
        if (searching) return
        setRemoteOnly(!remoteOnly)
    }

    private fun setRemoteOnly(value: Boolean) {
        if (remoteOnly == value) return
        remoteOnly = value
        clipboardHeader.setRemoteFilter(value)
        clipboardPanel.setRemoteOnly(value)
        onClipboardFilterChanged?.invoke(value)
    }

    /**
     * 搜索状态下的按键：算命中的键返回 true，宿主就不再把它交给输入引擎。
     * 这样键盘仍然摆在原位，TopPanel 也完全不变。
     */
    fun handleSearchKey(key: KeySpec): Boolean {
        if (!searching || state != TopPanelState.CLIPBOARD) return false
        return when (key.type) {
            KeyType.CHARACTER, KeyType.SYMBOL -> {
                val text = key.text ?: key.displayText
                if (text.isEmpty()) return false
                appendQuery(text)
                true
            }

            KeyType.SPACE -> {
                appendQuery(" ")
                true
            }

            KeyType.COMMA -> {
                appendQuery("，")
                true
            }

            KeyType.PERIOD -> {
                appendQuery("。")
                true
            }

            KeyType.BACKSPACE -> {
                if (query.isEmpty()) return false
                query = query.dropLast(1)
                onQueryChanged()
                true
            }

            KeyType.ENTER -> true
            else -> false
        }
    }

    private fun appendQuery(text: String) {
        if (query.length >= MAX_QUERY_LENGTH) return
        query += text
        onQueryChanged()
    }

    private fun onQueryChanged() {
        clipboardHeader.setQuery(query)
        onSearchQueryChanged?.invoke(query)
    }

    // ---------------------------------------------------------------- 内部

    private fun toggleClipboardEdit() {
        if (searching) return
        setClipboardEditing(!clipboardPanel.isEditing())
    }

    private fun setClipboardEditing(editing: Boolean) {
        clipboardPanel.setEditMode(editing)
        clipboardHeader.setEditing(editing)
    }

    private fun derivedState(): TopPanelState =
        if (composing.isNotEmpty()) TopPanelState.CANDIDATES else TopPanelState.TOOLBAR

    private fun applyState() {
        val previousState = appliedState
        val layout = when {
            state == TopPanelState.TRANSLATION -> PanelLayoutMode.TRANSLATION_ONLY
            state != TopPanelState.CLIPBOARD -> PanelLayoutMode.KEYBOARD_ONLY
            searching -> PanelLayoutMode.CLIPBOARD_WITH_KEYBOARD
            else -> PanelLayoutMode.CLIPBOARD_ONLY
        }

        if (previousState != state) {
            appliedState = state
            toolbar.visibility = visibleIf(state == TopPanelState.TOOLBAR)
            voiceHeader.visibility = visibleIf(state == TopPanelState.VOICE)
            candidateBar.visibility = visibleIf(state == TopPanelState.CANDIDATES)
            morePanel.visibility = visibleIf(state == TopPanelState.MORE)
            clipboardHeader.visibility = visibleIf(state == TopPanelState.CLIPBOARD)
            translationHeader.visibility = visibleIf(state == TopPanelState.TRANSLATION)
            animateIn(visiblePanelChild())
        }

        if (appliedLayout != layout) {
            appliedLayout = layout
            onPanelLayoutChanged?.invoke(layout)
        }
    }

    private fun visiblePanelChild(): View? = when (state) {
        TopPanelState.TOOLBAR -> toolbar
        TopPanelState.CANDIDATES -> candidateBar
        TopPanelState.MORE -> morePanel
        TopPanelState.CLIPBOARD -> clipboardHeader
        TopPanelState.VOICE -> voiceHeader
        TopPanelState.TRANSLATION -> translationHeader
    }

    /**
     * 状态切换动画：**只动 TopPanel 里刚进来的那块内容**，
     * 150ms 的 alpha 淡入 + 4dp 轻微上移；键盘主体完全不参与。
     * 系统开启「移除动画」时（duration scale = 0）直接跳过。
     */
    private fun animateIn(view: View?) {
        if (view == null) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = enterOffsetPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE

    private companion object {
        const val MAX_QUERY_LENGTH = 64
        const val ENTER_DURATION_MS = 150L
    }
}
