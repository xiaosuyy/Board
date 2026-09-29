package com.board.ime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import com.board.ime.BuildConfig
import com.board.ime.clipboard.ClipboardCaptureResult
import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.database.BoardDatabase
import com.board.ime.database.entity.ClipboardItem
import com.board.ime.databinding.BoardKeyboardBinding
import com.board.ime.dictionary.DictionaryRepository
import com.board.ime.engine.BasicEngine
import com.board.ime.engine.InputEngine
import com.board.ime.engine.InputMode
import com.board.ime.engine.RimeEngine
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.keyboard.KeySpec
import com.board.ime.keyboard.KeyType
import com.board.ime.keyboard.KeyboardLayoutCalculator
import com.board.ime.keyboard.KeyboardLayoutMetrics
import com.board.ime.settings.BoardPreferences
import com.board.ime.settings.BoardSettingsActivity
import com.board.ime.settings.EngineKind
import com.board.ime.settings.LayoutMode
import com.board.ime.sync.SyncEngine
import com.board.ime.sync.SyncEngineHolder
import com.board.ime.sync.SyncScheduler
import com.board.ime.sync.SyncTrigger
import com.board.ime.sync.DeviceRepository
import com.board.ime.ui.KeyboardTheme
import com.board.ime.ui.panel.MorePanel
import com.board.ime.ui.panel.PanelLayoutMode
import com.board.ime.ui.panel.TopPanelController
import com.board.ime.ui.panel.TopPanelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Board 输入法服务。
 *
 * 负责把三块东西接起来：
 * - [InputController] / [InputEngine]：输入逻辑（中文交给 librime）
 * - [KeyboardView]：只负责按键
 * - [TopPanelController]：顶部动态面板（Toolbar / Candidate / Clipboard）
 *
 * 面板状态由 `TopPanelController` 推导，这里只分发事件（工具栏动作、剪贴板插入等）。
 */
class BoardInputMethodService : InputMethodService(), com.board.ime.ui.KeyboardView.OnKeyboardActionListener {

    private lateinit var preferences: BoardPreferences

    private var engine: InputEngine = BasicEngine(InputMode.ENGLISH)
    private var rimeEngine: RimeEngine? = null
    private var controller = InputController(engine)
    private var appliedEngineKind: EngineKind? = null

    private lateinit var dictionaryRepository: DictionaryRepository

    private var binding: BoardKeyboardBinding? = null
    private var panelController: TopPanelController? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rimePrepareStarted = false
    private var metrics: KeyboardLayoutMetrics? = null

    /** 本次 onFinishInputView 是否由「收起键盘」触发（触发时保留组词状态） */
    private var hidingForCollapse = false

    private var clipboardRepository: ClipboardRepository? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipboardObserver: Job? = null
    private var syncEngine: SyncEngine? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPreferences() }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboard()
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()

        preferences = BoardPreferences(this)
        dictionaryRepository = DictionaryRepository(BoardDatabase.get(this).dictionaryDao(), this)
        buildController(preferences.engineKind, preferences.mode)

        clipboardRepository = ClipboardRepository(
            dao = BoardDatabase.get(this).clipboardDao(),
            syncOperationDao = BoardDatabase.get(this).syncOperationDao(),
        )
        registerClipboardListener()
        startSync()

        preferences.registerListener(preferenceListener)
        bootstrapDatabase()
        prepareRimeIfNeeded()
    }

    override fun onCreateInputView(): View {
        val binding = BoardKeyboardBinding.inflate(layoutInflater)
        this.binding = binding

        binding.keyboardView.listener = this

        val panel = TopPanelController(
            toolbar = binding.toolbarView,
            candidateBar = binding.candidateBar,
            morePanel = binding.morePanel,
            clipboardHeader = binding.clipboardHeader,
            clipboardPanel = binding.clipboardPanel,
        )
        panel.onCandidateSelected = { index -> controller.pickCandidate(index) }
        panel.onComposingClicked = { controller.commitCompositionAsIs() }
        panel.onMoreAction = { action -> onMoreAction(action) }
        panel.onClipboardInsert = { item -> insertClipboardItem(item) }
        panel.onClipboardTogglePin = { item -> setClipboardPinned(item, !item.isPinned) }
        panel.onClipboardToggleFavorite = { item -> setClipboardFavorite(item, !item.isFavorite) }
        panel.onClipboardDelete = { item -> deleteClipboardItem(item) }
        panel.onClipboardClearHistory = { includeProtected -> clearClipboardHistory(includeProtected) }
        panel.onPanelLayoutChanged = { mode -> applyPanelLayout(mode) }
        panel.onClipboardOpened = { onClipboardOpened() }
        panel.onSearchQueryChanged = { query -> observeClipboard(query, panel.isRemoteOnly) }
        panel.onClipboardFilterChanged = { remoteOnly ->
            observeClipboard(panel.searchQuery, remoteOnly)
        }
        panel.onCollapseKeyboard = { collapseKeyboard() }
        panelController = panel

        applyTheme()
        refreshInputView()
        return binding.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 新的一次输入一定不是「收起键盘」造成的中断，清掉标记，避免残留
        hidingForCollapse = false
        controller.onStartInput(currentInputConnection, info)
        panelController?.resetToIdle()
        stopClipboardObservation()
        refreshBlockedWords()
        applyTheme()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 「收起键盘」是用户主动隐藏，不算结束输入：保留组词与面板状态，
        // 这样键盘再出来时还能接着刚才的输入（不清空 composing）。
        val collapseOnly = hidingForCollapse
        hidingForCollapse = false
        Log.d(TAG, "onFinishInputView finishing=$finishingInput collapse=$collapseOnly")
        if (!collapseOnly) {
            controller.onFinishInput()
            panelController?.resetToIdle()
        }
        stopClipboardObservation()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.onSelectionChanged(candidatesStart, candidatesEnd)
    }

    /** 旋转屏幕 / 分屏：重新算尺寸，不改动输入状态 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onDestroy() {
        preferences.unregisterListener(preferenceListener)
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        stopClipboardObservation()
        serviceScope.cancel()
        RimeRuntime.close()
        binding = null
        panelController = null
        super.onDestroy()
    }

    /** 键盘不需要全屏编辑模式 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = true

    // ------------------------------------------------------------ 键盘回调

    override fun onKey(key: KeySpec) {
        // 剪贴板搜索：按键先给搜索框，不打断、也不污染正在编辑的输入框
        val panel = panelController
        if (panel != null && panel.handleSearchKey(key)) {
            playKeyFeedback()
            return
        }
        playKeyFeedback()
        controller.onKey(key)
        if (key.type == KeyType.LANGUAGE) {
            persistLetterMode()
        }
    }

    override fun onKeyLongPress(key: KeySpec) {
        if (key.type == KeyType.LANGUAGE) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    override fun onKeyRepeat(key: KeySpec) {
        playKeyFeedback()
        controller.onKeyRepeat(key)
    }

    // ------------------------------------------------------------ 顶部面板

    private fun onMoreAction(action: MorePanel.Action) {
        when (action) {
            MorePanel.Action.CURSOR_LEFT -> controller.moveCursor(-1)
            MorePanel.Action.CURSOR_RIGHT -> controller.moveCursor(1)
            // 「主题」与「设置」都指向设置页（外观 / 主题在同一处）
            MorePanel.Action.THEME, MorePanel.Action.SETTINGS -> openSettings()
            // 尚未开发的功能：明确告知，不伪装成可用
            MorePanel.Action.TRANSLATE -> notifyNotImplemented("翻译")
            MorePanel.Action.VOICE -> notifyNotImplemented("语音")
            MorePanel.Action.PHRASES -> notifyNotImplemented("常用语")
            // CLOSE / CLIPBOARD 由 TopPanelController 内部处理
            MorePanel.Action.CLOSE, MorePanel.Action.CLIPBOARD -> Unit
        }
    }

    private fun notifyNotImplemented(name: String) {
        Toast.makeText(this, "$name 功能尚未开发", Toast.LENGTH_SHORT).show()
    }

    /** 打开剪贴板：同步一次系统剪贴板并开始观察 Room 数据 */
    private fun onClipboardOpened() {
        captureClipboard()
        observeClipboard(panelController?.searchQuery.orEmpty(), panelController?.isRemoteOnly == true)
    }

    /**
     * 收起键盘。
     *
     * 用 InputMethodService 自己的隐藏能力（`requestHideSelf`），
     * **不模拟返回键、不 finish Activity**：输入法与输入框的连接保持原样，
     * 组词内容不会被清掉，下次点回输入框仍然正常使用 Board。
     */
    private fun collapseKeyboard() {
        hidingForCollapse = true
        requestHideSelf(0)
    }

    private fun insertClipboardItem(item: ClipboardItem) {
        // 组词中的话先按原样结束组词，插入内容不会覆盖拼音
        if (controller.hasComposition) {
            controller.commitCompositionAsIs()
        }
        controller.commitText(item.content)
        // 用过的条目算一次「最近使用」，下次排在更前面
        serviceScope.launch { clipboardRepository?.markUsed(item.id) }
    }

    private fun setClipboardPinned(item: ClipboardItem, pinned: Boolean) {
        serviceScope.launch { clipboardRepository?.setPinned(item.id, pinned) }
    }

    private fun setClipboardFavorite(item: ClipboardItem, favorite: Boolean) {
        serviceScope.launch { clipboardRepository?.setFavorite(item.id, favorite) }
    }

    private fun deleteClipboardItem(item: ClipboardItem) {
        serviceScope.launch { clipboardRepository?.remove(item.id) }
    }

    /** 清空历史；默认保留固定 / 收藏，只有勾选后才一起删 */
    private fun clearClipboardHistory(includeProtected: Boolean) {
        serviceScope.launch {
            val removed = clipboardRepository?.clearHistory(includeProtected) ?: 0
            val message = if (includeProtected) {
                "已清空全部剪贴板历史（含固定 / 收藏）"
            } else {
                "已清空 $removed 条普通历史，固定 / 收藏保留"
            }
            mainHandler.post { Toast.makeText(this@BoardInputMethodService, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun observeClipboard(query: String = "", remoteOnly: Boolean = false) {
        val repository = clipboardRepository ?: return
        clipboardObserver?.cancel()
        clipboardObserver = serviceScope.launch {
            repository.search(query, CLIPBOARD_LIMIT, remoteOnly).collect { items ->
                val counts = repository.counts()
                mainHandler.post { panelController?.submitClipboard(items, counts) }
            }
        }
    }

    private fun stopClipboardObservation() {
        clipboardObserver?.cancel()
        clipboardObserver = null
    }

    private fun registerClipboardListener() {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        clipboardManager = manager
        manager.addPrimaryClipChangedListener(clipboardListener)
    }

    /** 把系统剪贴板里的文本记录到 Room（按内容哈希去重、敏感内容不入库） */
    private fun captureClipboard() {
        val manager = clipboardManager ?: return
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = runCatching { clip.getItemAt(0).coerceToText(this)?.toString() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return
        serviceScope.launch {
            val result = clipboardRepository?.capture(text)
            if (result == ClipboardCaptureResult.SKIPPED_SENSITIVE) {
                mainHandler.post {
                    Toast.makeText(
                        this@BoardInputMethodService,
                        "疑似验证码 / 密码，按策略不写入剪贴板历史",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else if (result == ClipboardCaptureResult.INSERTED) {
                requestAutoSync()
            }
        }
    }

    // ------------------------------------------------------------ 同步

    /**
     * 起同步：注册「远端内容 → 系统剪贴板」的落点，并按需触发一次同步。
     *
     * 明确一点：这里的自动同步是**启动检查 + 复制后去抖上传 + WorkManager 周期兜底**，
     * 不是实时推送（实时留给后续的局域网方案）。
     */
    private fun startSync() {
        val engine: SyncEngine = SyncEngineHolder.get(this)
        syncEngine = engine
        val config = engine.loadConfig()
        SyncScheduler.applyAutoSync(this, config.autoSync)

        serviceScope.launch {
            engine.applied.collect { content -> mainHandler.post { applyRemoteToClipboard(content) } }
        }

        if (config.isConfigured && config.autoSync) {
            serviceScope.launch {
                runCatching { engine.syncNow(SyncTrigger.IME_START, debounce = true) }
            }
        }
    }

    /** 复制了新内容后，去抖地推一次（配置了才会真的动网络） */
    private fun requestAutoSync() {
        val engine = syncEngine ?: return
        val config = engine.loadConfig()
        if (!config.isConfigured || !config.autoSync) return
        serviceScope.launch {
            runCatching { engine.syncNow(SyncTrigger.AFTER_CLIPBOARD_CHANGE, debounce = true) }
        }
    }

    /**
     * 远端拉下来的内容写进系统剪贴板。
     *
     * **先登记抑制窗口**，否则系统会回调 `OnPrimaryClipChangedListener`，
     * 又被当成「本机复制」上传一次 → WebDAV → 本地 → 回调 → 上传 的无限回环。
     */
    private fun applyRemoteToClipboard(content: String) {
        val manager = clipboardManager ?: return
        clipboardRepository?.suppressCapture(ClipboardHasher.hash(content))
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, content))
        }.onFailure {
            // Android 10+ 后台写剪贴板可能被忽略；本地历史里仍然有这条，用户可以从面板插入
            Log.w(TAG, "写入系统剪贴板被拒绝（后台限制）：${it.message}")
        }
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, BoardSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * TopPanel 下面的区域怎么摆。TopPanel 自己永远是 56dp，不参与这里的变化：
     * - 只有键盘：工具栏 / 候选 / 更多
     * - 只有内容区：普通剪贴板（内容区高度 = 键盘高度，所以总高度不变）
     * - 内容区 + 键盘：剪贴板搜索（内容区有高度上限，键盘照常在下面，可以打字）
     */
    private fun applyPanelLayout(mode: PanelLayoutMode) {
        val binding = binding ?: return
        val keyboardHeight = metrics?.keyboardHeightPx(binding.keyboardView.keyboardLayout.rowCount) ?: 0
        val theme = KeyboardTheme.from(this, preferences.themeMode, preferences.candidateTextSize)

        // 分隔线始终保留：面板与下方内容（键盘 / 剪贴板）的分界在四种状态下一致
        binding.panelDivider.visibility = View.VISIBLE

        when (mode) {
            PanelLayoutMode.KEYBOARD_ONLY -> {
                binding.keyboardView.visibility = View.VISIBLE
                binding.clipboardPanel.visibility = View.GONE
                binding.clipboardPanel.layoutParams = binding.clipboardPanel.layoutParams.apply {
                    height = keyboardHeight
                }
            }

            PanelLayoutMode.CLIPBOARD_ONLY -> {
                binding.keyboardView.visibility = View.GONE
                binding.clipboardPanel.visibility = View.VISIBLE
                binding.clipboardPanel.layoutParams = binding.clipboardPanel.layoutParams.apply {
                    height = keyboardHeight
                }
            }

            PanelLayoutMode.CLIPBOARD_WITH_KEYBOARD -> {
                binding.keyboardView.visibility = View.VISIBLE
                binding.clipboardPanel.visibility = View.VISIBLE
                binding.clipboardPanel.layoutParams = binding.clipboardPanel.layoutParams.apply {
                    height = theme.clipboardSearchMaxHeight
                }
            }
        }
    }

    /** 剪贴板里的返回键：先退临时视图 / 搜索，最后才交给系统关输入法 */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val panel = panelController
            if (panel != null && panel.currentState == TopPanelState.CLIPBOARD) {
                panel.handleClipboardBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ 引擎

    private fun buildController(kind: EngineKind, letterMode: InputMode) {
        val newEngine: InputEngine = when (kind) {
            EngineKind.RIME -> RimeEngine()
            EngineKind.BASIC -> BasicEngine(letterMode)
        }
        rimeEngine = newEngine as? RimeEngine
        controller.onStateChanged = null
        engine = newEngine
        controller = InputController(newEngine, letterMode).also { it.onStateChanged = { refreshInputView() } }
        appliedEngineKind = kind
    }

    private fun ensureEngineKind() {
        val desired = preferences.engineKind
        if (appliedEngineKind == desired) return
        buildController(desired, preferences.mode)
        if (desired == EngineKind.RIME) {
            prepareRimeIfNeeded()
        }
    }

    private fun prepareRimeIfNeeded() {
        if (rimePrepareStarted || preferences.engineKind != EngineKind.RIME) return
        rimePrepareStarted = true
        serviceScope.launch {
            val installed = RimeRuntime.initialize(
                this@BoardInputMethodService,
                BuildConfig.VERSION_NAME,
            )
            val deployed = installed && RimeRuntime.deploy(fullCheck = RimeRuntime.dataWasReinstalled)
            val opened = deployed && RimeRuntime.openSession()
            mainHandler.post {
                if (opened) {
                    Log.i(
                        TAG,
                        "Rime 就绪：librime=${RimeRuntime.librimeVersion} schema=${RimeRuntime.currentSchema()}",
                    )
                } else {
                    Log.w(TAG, "Rime 未就绪：${RimeRuntime.lastError}")
                }
                refreshInputView()
            }
        }
    }

    private fun persistLetterMode() {
        if (preferences.mode != controller.letterMode) {
            preferences.mode = controller.letterMode
        }
    }

    // ------------------------------------------------------------ 视图刷新

    private fun refreshInputView() {
        val binding = binding ?: return
        binding.keyboardView.keyboardLayout = controller.currentLayout
        binding.keyboardView.keyboardMode = controller.mode
        binding.keyboardView.setShiftState(active = controller.shiftActive, locked = controller.capsLock)

        panelController?.updateInput(
            candidates = controller.candidates,
            mode = controller.mode,
            composing = controller.composing,
            statusText = rimeStatusText(),
        )
    }

    /** 面板本身不再显示输入状态，这里只把「引擎暂时不可用」这类真实状态传下去 */
    private fun rimeStatusText(): String? {
        if (controller.mode != InputMode.CHINESE) return null
        if (appliedEngineKind != EngineKind.RIME) return null
        return RimeRuntime.statusText()
    }

    private fun applyTheme() {
        val resolved = KeyboardTheme.from(
            this,
            preferences.themeMode,
            preferences.candidateTextSize,
        )
        val binding = binding ?: return

        binding.panelDivider.setBackgroundColor(resolved.divider)
        binding.panelDivider.layoutParams = binding.panelDivider.layoutParams?.apply {
            height = resolved.dividerHeight
        }
        binding.keyboardView.applyTheme(resolved)
        panelController?.applyTheme(resolved)

        applyLayoutMetrics(resolved)
        refreshInputView()
    }

    /**
     * 竖屏铺满宽度；横屏 / 大屏使用悬浮布局。
     * 尺寸全部由 [KeyboardLayoutCalculator] 算出来，View 与布局里没有硬编码尺寸。
     */
    private fun applyLayoutMetrics(theme: KeyboardTheme) {
        val binding = binding ?: return
        val floating = when (preferences.layoutMode) {
            LayoutMode.AUTO -> KeyboardLayoutCalculator.prefersFloatingLayout(this)
            LayoutMode.FILL_WIDTH -> false
            LayoutMode.FLOATING -> true
        }
        val config = KeyboardLayoutCalculator.configFor(this, floating)
        val display = resources.displayMetrics

        val resolved = KeyboardLayoutCalculator.metrics(
            config = config,
            screenWidthPx = display.widthPixels,
            screenHeightPx = display.heightPixels,
            density = display.density,
            rowCount = binding.keyboardView.keyboardLayout.rowCount,
        )
        metrics = resolved
        binding.keyboardView.applyMetrics(resolved)

        val floatParams = binding.keyboardFloat.layoutParams as FrameLayout.LayoutParams
        if (resolved.floating) {
            floatParams.width = resolved.widthPx
            floatParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            floatParams.gravity = Gravity.BOTTOM or
                if (resolved.alignEnd) Gravity.END else Gravity.CENTER_HORIZONTAL
            val margin = resolved.edgeMarginPx
            floatParams.setMargins(margin, margin, margin, margin)
            binding.keyboardFloat.background = floatingCard(theme, resolved.cornerRadiusPx)
            binding.keyboardRoot.setBackgroundColor(Color.TRANSPARENT)
            window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else {
            floatParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            floatParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            floatParams.gravity = Gravity.NO_GRAVITY
            floatParams.setMargins(0, 0, 0, 0)
            binding.keyboardFloat.background = null
            binding.keyboardRoot.setBackgroundColor(theme.keyboardBackground)
        }
        binding.keyboardFloat.layoutParams = floatParams
        // 重新算尺寸后，按当前面板状态重排「键盘 / 剪贴板内容区」
        panelController?.let { panel ->
            val mode = when {
                panel.currentState != TopPanelState.CLIPBOARD -> PanelLayoutMode.KEYBOARD_ONLY
                panel.isSearching -> PanelLayoutMode.CLIPBOARD_WITH_KEYBOARD
                else -> PanelLayoutMode.CLIPBOARD_ONLY
            }
            applyPanelLayout(mode)
        }
    }

    private fun floatingCard(theme: KeyboardTheme, radius: Float) = GradientDrawable().apply {
        setColor(theme.keyboardBackground)
        cornerRadius = radius
    }

    private fun applyPreferences() {
        ensureEngineKind()
        controller.setLetterMode(preferences.mode)
        applyTheme()
    }

    private fun playKeyFeedback() {
        val view = binding?.keyboardView ?: return
        if (preferences.soundEnabled) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
        if (preferences.vibrateEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** 首次启动清理旧的英文种子词，并登记本机设备 */
    private fun bootstrapDatabase() {
        serviceScope.launch {
            runCatching {
                val database = BoardDatabase.get(this@BoardInputMethodService)
                dictionaryRepository.removeLegacySeededWords()
                // 老版本剪贴板没有内容哈希，补上并合并重复内容（同一文本只留一条）
                val merged = clipboardRepository?.dedupeExisting() ?: 0
                if (merged > 0) {
                    Log.i(TAG, "剪贴板历史去重：处理 $merged 条")
                }
                DeviceRepository(database.deviceDao()).registerCurrentDevice(this@BoardInputMethodService)
            }
        }
    }

    /** 屏蔽词由 Board 在候选层过滤，每次进入输入框时同步一次 */
    private fun refreshBlockedWords() {
        serviceScope.launch {
            val blocked = runCatching { dictionaryRepository.blockedWords() }.getOrDefault(emptySet())
            mainHandler.post { rimeEngine?.blockedWords = blocked }
        }
    }

    private companion object {
        const val TAG = "BoardIME"
        const val CLIPBOARD_LIMIT = 60
        /** 写系统剪贴板时的标签（只在系统的剪贴板浮层里显示） */
        const val CLIPBOARD_LABEL = "Board"
    }
}
