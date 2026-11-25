package com.board.ime.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.board.ime.BuildConfig
import com.board.ime.R
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.clipboard.ClipboardStressTest
import com.board.ime.database.BoardDatabase
import com.board.ime.dictionary.DictionaryFormat
import com.board.ime.dictionary.DictionaryFormatException
import com.board.ime.dictionary.DictionaryRepository
import com.board.ime.dictionary.ExportScope
import com.board.ime.dictionary.WordType
import com.board.ime.engine.InputMode
import com.board.ime.engine.rime.RimeFuzzyGroup
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.engine.rime.ShuangpinScheme
import com.board.ime.ime.BoardInputMethodService
import com.board.ime.keyboard.KeyboardLayoutCalculator
import com.board.ime.ui.KeyboardTheme
import com.board.ime.sync.LearnedWordBridge
import com.board.ime.sync.SyncContent
import com.board.ime.sync.SyncEngineHolder
import com.board.ime.sync.SyncEvent
import com.board.ime.sync.SyncLog
import com.board.ime.sync.SyncPart
import com.board.ime.sync.SyncPartState
import com.board.ime.sync.SyncScheduler
import com.board.ime.sync.SyncStatusKind
import com.board.ime.sync.SyncTrigger
import com.board.ime.sync.webdav.WebDavConfig
import com.board.ime.update.UpdateChecker
import com.board.ime.update.UpdateDownloadSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 二级设置页。
 *
 * 一个 Activity 承载 8 个分类：标题栏 + 按 [SettingsSection] 生成内容。
 * 具体控件全部由 [SettingsUi] 生成，避免再堆 XML，也让「首页只放入口」这件事更好维护。
 */
class SettingsSectionActivity : AppCompatActivity() {

    private lateinit var preferences: BoardPreferences
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var clipboardRepository: ClipboardRepository
    private lateinit var boardDatabase: BoardDatabase
    private lateinit var settingsRepository: SettingsRepository

    /** 键盘尺寸页当前编辑的作用域（竖屏 / 横屏 / 悬浮） */
    private var geometryScope: KeyboardScope = KeyboardScope.PORTRAIT

    private var section: SettingsSection = SettingsSection.INPUT
    private var showLogs = false

    private var pendingImportFormat: DictionaryFormat = DictionaryFormat.TXT
    private var pendingExportScope: ExportScope = ExportScope.ALL
    private var pendingExportFormat: DictionaryFormat = DictionaryFormat.TXT

    /** 本次「立即同步」是否已经超时（UI 结束等待、后台继续） */
    private var syncTimedOut = false

    /** 同步状态实时订阅（每次重建内容视图时先取消旧的，避免越积越多） */
    private var statusJob: Job? = null

    /** 检查更新的协程：重复点击时取消上一次 */
    private var updateCheckJob: Job? = null

    /** 当前页面的滚动容器：重建视图后恢复位置，避免改一项设置就跳回顶部 */
    private var currentScroll: android.widget.ScrollView? = null

    /** 外观页底部的键盘实时预览 */
    private var appearancePreview: KeyboardPreviewView? = null

    /** 预览对应的作用域 / 屏幕尺寸：刷新时按同一套参数重算，不能只改颜色 */
    private var previewFloating = false
    private var previewKeyboardLift = true
    private var previewScreenWidthPx = 0
    private var previewScreenHeightPx = 0

    /** 「剪贴板卡片圆角」的示例卡片（不在键盘预览里，单独展示） */
    private var clipboardCardSample: TextView? = null

    /** 配色主题横条的横向滚动位置：换主题会重建页面，位置要接着上次的 */
    private var paletteStripScrollX = 0
    private var paletteStripView: android.widget.HorizontalScrollView? = null

    /** 拖动「整体高度」时把档位同步到「自定义」，避免分段控件选中项与实际值对不上 */
    private var syncingHeightLevel = false

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handleImport(it) }
        }

    private val importTextFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handleTextFileImport(it) }
        }

    private val exportJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { handleExport(it) }
        }

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { handleExport(it) }
        }

    private val exportTextLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { handleExport(it) }
        }

    /** 账号页：从相册选头像（复制到应用私有目录，不申请存储权限） */
    private val avatarPickLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    com.board.ime.profile.AvatarStore.saveFromUri(this@SettingsSectionActivity, uri)
                }
                if (!saved) {
                    Toast.makeText(this@SettingsSectionActivity, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                Toast.makeText(this@SettingsSectionActivity, "头像已更新", Toast.LENGTH_SHORT).show()
                render()
                val uploaded = withContext(Dispatchers.IO) {
                    runCatching { SyncEngineHolder.get(this@SettingsSectionActivity).syncProfileOnly() }
                        .getOrDefault(false)
                }
                if (uploaded) {
                    // 云端可能比本地新（另一台设备刚换过）→ 重新读一次本地头像
                    render()
                } else {
                    Toast.makeText(
                        this@SettingsSectionActivity,
                        "头像已保存，将在下次同步时上传",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = SettingsSection.fromName(intent.getStringExtra(EXTRA_SECTION))
        showLogs = intent.getBooleanExtra(EXTRA_SHOW_LOGS, false)
        preferences = BoardPreferences(this)
        settingsRepository = SettingsRepository(this)
        val boardDatabase = BoardDatabase.get(this)
        this.boardDatabase = boardDatabase
        dictionaryRepository = DictionaryRepository(
            boardDatabase.dictionaryDao(),
            this,
            boardDatabase.dictionarySourceDao(),
            boardDatabase,
        )
        // B1（D1）：与输入法服务 / SyncEngine 共用**同一个** repository 实例
        clipboardRepository = com.board.ime.sync.SyncEngineHolder.clipboardRepository(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val restoreScroll = currentScroll?.scrollY ?: 0
        setContentView(buildScreen())
        if (restoreScroll > 0) {
            currentScroll?.post { currentScroll?.scrollTo(0, restoreScroll) }
        }
    }

    private fun buildScreen(): LinearLayout {
        appearancePreview = null
        clipboardCardSample = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background())
        }

        // 标题栏：裸返回箭头 + 下方大标题（Lyra 二级页的排版）
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20f), dp(8f), dp(20f), 0)
                addView(
                    android.widget.FrameLayout(this@SettingsSectionActivity).apply {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { finish() }
                        addView(
                            android.widget.ImageView(this@SettingsSectionActivity).apply {
                                setImageResource(R.drawable.ic_board_back)
                                setColorFilter(themeColor(com.google.android.material.R.attr.colorOnSurface))
                                layoutParams = android.widget.FrameLayout.LayoutParams(
                                    dp(24f),
                                    dp(24f),
                                    Gravity.CENTER_VERTICAL or Gravity.START,
                                )
                            },
                        )
                        layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
                    },
                )
                addView(
                    TextView(this@SettingsSectionActivity).apply {
                        text = if (showLogs) "日志详情" else section.title
                        setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_PX,
                            resources.getDimensionPixelSize(R.dimen.app_text_display).toFloat(),
                        )
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                        setPadding(0, dp(12f), 0, dp(4f))
                    },
                )
            },
        )

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(SettingsUi.screen(this@SettingsSectionActivity))
        }
        currentScroll = scroll

        val content = scroll.getChildAt(0) as LinearLayout
        if (showLogs) {
            buildLogs(content)
        } else {
            when (section) {
                SettingsSection.INPUT -> buildInput(content)
                SettingsSection.KEYBOARD -> buildKeyboard(content)
                SettingsSection.APPEARANCE -> buildAppearance(content)
                SettingsSection.DICTIONARY -> buildDictionary(content)
                SettingsSection.CLIPBOARD -> buildClipboard(content)
                SettingsSection.ACCOUNT -> buildAccount(content)
                SettingsSection.TRANSLATION -> buildTranslation(content)
                SettingsSection.VOICE -> buildVoice(content)
                SettingsSection.ADVANCED -> buildAdvanced(content)
                SettingsSection.LIBRARY -> buildLibrary(content)
                SettingsSection.ABOUT -> buildAboutPage(content)
            }
        }
        if (!showLogs && section == SettingsSection.APPEARANCE) {
            // 外观页：上半屏滚动设置项，下半屏常驻键盘预览（边调边看）
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(
                buildAppearancePreview(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        SettingsUi.applySystemBarInset(root)
        return root
    }

    /**
     * 外观页的键盘实时预览：竖屏按真实键盘 1:1 渲染，横屏 / 悬浮按比例缩小。
     * 作为滚动区里的最后一个区块展示，拖动上面的滑块 / 圆角 / 配色时实时联动。
     */
    private fun buildAppearancePreview(): View {
        val tools = com.board.ime.ui.panel.ToolLayout.parse(
            preferences.toolbarTools,
            preferences.moreTools,
        ).toolbar
        val display = resources.displayMetrics
        val scope = geometryScope
        val landscape = scope == KeyboardScope.LANDSCAPE
        // 与输入法服务相同的判定：悬浮作用域，或横屏且「键盘布局 = 自动」
        val floating = scope == KeyboardScope.FLOATING ||
            (landscape && preferences.layoutMode == LayoutMode.AUTO)
        // 竖屏 1:1；横屏按「竖屏宽 / 横屏宽」缩小，保证整块横屏键盘能放进设置页
        val scale = if (landscape) display.widthPixels.toFloat() / display.heightPixels else 1f
        val previewContext =
            if (scale >= 0.999f) this else KeyboardPreviewView.scaledContext(this, scale)
        // 目标方向的屏幕尺寸，同样按预览比例换算
        val screenWidthPx = ((if (landscape) display.heightPixels else display.widthPixels) * scale).roundToInt()
        val screenHeightPx = ((if (landscape) display.widthPixels else display.heightPixels) * scale).roundToInt()
        previewFloating = floating
        previewKeyboardLift = preferences.keyboardLiftEnabled
        previewScreenWidthPx = screenWidthPx
        previewScreenHeightPx = screenHeightPx

        val geometry = settingsRepository.geometry(scope)
        val appearance = settingsRepository.appearance()
        val preview = KeyboardPreviewView(previewContext).apply {
            setTools(tools)
            applyTheme(
                appearanceTheme(previewContext),
                geometry,
                appearance,
                floating = floating,
                keyboardLift = previewKeyboardLift,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                minimal = preferences.minimalMode,
            )
            setSampleEnglishGloss(preferences.candidateEnglishGlossEnabled)
        }
        appearancePreview = preview

        // 宽度与输入法服务用同一套计算：铺满 = 屏宽；悬浮 = 按比例夹取后的宽度
        val previewMetrics = KeyboardLayoutCalculator.metrics(
            config = KeyboardLayoutCalculator.configFor(
                previewContext,
                floating,
                geometry,
                previewKeyboardLift,
            ),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            density = previewContext.resources.displayMetrics.density,
            rowCount = com.board.ime.keyboard.KeyboardLayouts.letters.rowCount,
        )
        val previewWidth = if (floating) previewMetrics.widthPx else screenWidthPx

        // 下半屏常驻：标题钉在顶部，键盘预览钉在底部（贴底）。
        // 调键盘高度时，键盘底部始终贴住屏幕底、向上生长，不会出现「下面越来越空」。
        val dock = android.widget.FrameLayout(this)

        dock.addView(
            TextView(this@SettingsSectionActivity).apply {
                text = "键盘预览（${scope.label}）· 点按顶部切换工具栏 / 候选栏"
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimensionPixelSize(R.dimen.app_text_caption).toFloat(),
                )
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(20f), dp(8f), dp(20f), dp(6f))
            },
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        dock.addView(
            preview,
            android.widget.FrameLayout.LayoutParams(
                previewWidth,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or if (floating) Gravity.END else Gravity.CENTER_HORIZONTAL,
            ).apply {
                if (floating) {
                    marginEnd = previewMetrics.edgeMarginPx
                    bottomMargin = previewMetrics.edgeMarginPx
                }
            },
        )
        return dock
    }

    // ---------------------------------------------------------------- 输入

    private fun buildInput(content: LinearLayout) {
        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "当前输入模式"))
        card.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("English", "中文"),
                if (preferences.mode == InputMode.CHINESE) 1 else 0,
            ) { index ->
                preferences.mode = if (index == 1) InputMode.CHINESE else InputMode.ENGLISH
            },
        )
        card.addView(SettingsUi.hint(this, "切换后立即生效，空格键随之变化。"))
        content.addView(SettingsUi.cardView(this, card))

        val engine = SettingsUi.card(this)
        engine.addView(SettingsUi.heading(this, "中文输入引擎"))
        engine.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("Rime 朙月拼音", "内置占位引擎"),
                if (preferences.engineKind == EngineKind.RIME) 0 else 1,
            ) { index ->
                preferences.engineKind = if (index == 0) EngineKind.RIME else EngineKind.BASIC
            },
        )
        engine.addView(SettingsUi.hint(this, "Rime 提供完整拼音与学习；占位引擎仅用于排查问题。"))
        content.addView(SettingsUi.cardView(this, engine))

        // ---- 字形：简体 / 繁体 ----
        val script = SettingsUi.card(this)
        script.addView(SettingsUi.heading(this, "字形"))
        script.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("简体字", "繁体字"),
                if (preferences.traditionalOutput) 1 else 0,
            ) { index ->
                preferences.traditionalOutput = index == 1
            },
        )
        script.addView(
            SettingsUi.hint(
                this,
                "只改基础词库字形，用户词保持不变。",
            ),
        )
        content.addView(SettingsUi.cardView(this, script))

        // ---- 双拼方案 ----
        val shuangpin = SettingsUi.card(this)
        shuangpin.addView(SettingsUi.heading(this, "双拼"))
        val schemeButton = SettingsUi.button(
            this,
            "当前：" + preferences.shuangpin.label,
        ) {
            val options = ShuangpinScheme.entries.map { it.label }
            MaterialAlertDialogBuilder(this)
                .setTitle("选择双拼方案")
                .setSingleChoiceItems(
                    options.toTypedArray(),
                    preferences.shuangpin.ordinal,
                ) { dialog, which ->
                    preferences.shuangpin = ShuangpinScheme.entries[which]
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        shuangpin.addView(schemeButton)
        shuangpin.addView(
            SettingsUi.hint(
                this,
                "微软 / 搜狗 / 紫光方案中，ing 可用 y 输入（键盘没有分号键）。",
            ),
        )
        content.addView(SettingsUi.cardView(this, shuangpin))

        // ---- 模糊音 / 简拼 / 纠错：12 组单独开关 ----
        val fuzzy = SettingsUi.card(this)
        fuzzy.addView(SettingsUi.heading(this, "模糊音与容错（12 组）"))
        RimeFuzzyGroup.entries.forEach { group ->
            fuzzy.addView(
                SettingsUi.switchRow(
                    this,
                    group.label,
                    group.key in preferences.rimeFuzzyGroups,
                ) { checked ->
                    val current = preferences.rimeFuzzyGroups.toMutableSet()
                    if (checked) current += group.key else current -= group.key
                    preferences.rimeFuzzyGroups = current
                },
            )
        }
        fuzzy.addView(
            SettingsUi.hint(
                this,
                "模糊音默认开启，精确输入不受影响。",
            ),
        )
        content.addView(SettingsUi.cardView(this, fuzzy))
    }

    // ---------------------------------------------------------------- 外观

    /** 预览与设置页共用同一套主题解析（含颜色 / 圆角 / 候选字号 / 当前作用域尺寸） */
    private fun appearanceTheme(context: Context = this): KeyboardTheme = KeyboardTheme.from(
        context,
        preferences.themeMode,
        preferences.candidateTextSize,
        geometry = settingsRepository.geometry(geometryScope),
        appearance = settingsRepository.appearance(),
        palette = settingsRepository.palette,
    )

    private fun refreshAppearancePreview() {
        // 横屏 / 悬浮预览用的是缩小密度的 Context，主题也必须用它解析，尺寸才是同一比例
        val theme = appearanceTheme(appearancePreview?.context ?: this)
        appearancePreview?.applyTheme(
            theme,
            settingsRepository.geometry(geometryScope),
            settingsRepository.appearance(),
            floating = previewFloating,
            keyboardLift = previewKeyboardLift,
            screenWidthPx = previewScreenWidthPx,
            screenHeightPx = previewScreenHeightPx,
            minimal = preferences.minimalMode,
        )
        clipboardCardSample?.apply {
            (background as? android.graphics.drawable.GradientDrawable)?.cornerRadius =
                theme.clipboardCardRadius
            setTextColor(theme.candidateText)
        }
    }

    private fun buildAppearance(content: LinearLayout) {
        val appearance = settingsRepository.appearance()
        val resolvedTheme = KeyboardTheme.from(
            this,
            preferences.themeMode,
            preferences.candidateTextSize,
            palette = settingsRepository.palette,
        )

        val theme = SettingsUi.card(this)
        theme.addView(SettingsUi.heading(this, "主题"))
        theme.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("跟随系统", "浅色", "深色"),
                preferences.themeMode.ordinal,
            ) { index ->
                preferences.themeMode = ThemeMode.entries[index]
                // 让设置页本身也切到对应夜间模式（会重建 Activity；键盘颜色由 KeyboardTheme 单独解析）
                AppTheme.apply(preferences.themeMode)
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, theme))

        // ---- 配色主题：六套配色，每套自带浅 / 深两版，跟随上面的「主题」切换 ----
        val palettes = SettingsUi.card(this)
        palettes.addView(SettingsUi.heading(this, "配色主题"))
        palettes.addView(paletteStrip(resolvedTheme.isDark))
        palettes.addView(
            SettingsUi.hint(
                this,
                "每套含浅色 / 深色两版，跟随上方「主题」切换。",
            ),
        )
        content.addView(SettingsUi.cardView(this, palettes))

        // ---- 候选词：字号 + 英文释义（调哪项，预览就切到候选栏展示哪项） ----
        val candidates = SettingsUi.card(this)
        candidates.addView(SettingsUi.heading(this, "候选词"))
        candidates.addView(
            SettingsUi.toggleGroup(
                this,
                CandidateTextSize.entries.map { it.label },
                preferences.candidateTextSize.ordinal,
            ) { index ->
                preferences.candidateTextSize = CandidateTextSize.entries[index]
                appearancePreview?.showCandidates(true)
                refreshAppearancePreview()
            },
        )
        candidates.addView(
            SettingsUi.switchRow(
                this,
                "在候选下方显示英文释义",
                preferences.candidateEnglishGlossEnabled,
            ) { enabled ->
                preferences.candidateEnglishGlossEnabled = enabled
                appearancePreview?.showCandidates(true)
                appearancePreview?.setSampleEnglishGloss(enabled)
            },
        )
        candidates.addView(
            SettingsUi.hint(
                this,
                "只使用内置本地词表，不联网、不保存输入内容。",
            ),
        )
        content.addView(SettingsUi.cardView(this, candidates))

        // ---- 简洁模式：待机只留收起键，输入只留候选词（无拼音 / 无选中色 / 无分割线） ----
        val minimal = SettingsUi.card(this)
        minimal.addView(SettingsUi.heading(this, "简洁模式"))
        minimal.addView(
            SettingsUi.switchRow(
                this,
                "开启简洁模式",
                preferences.minimalMode,
            ) { enabled ->
                preferences.minimalMode = enabled
                refreshAppearancePreview()
            },
        )
        minimal.addView(
            SettingsUi.hint(
                this,
                "待机时只显示收起键盘图标；输入时不显示拼音，候选无选中色、无分割线。",
            ),
        )
        content.addView(SettingsUi.cardView(this, minimal))

        // ---- 键盘尺寸与精细调整：和颜色 / 圆角一样属于「外观」 ----
        buildAppearanceGeometryCard(content)

        val colors = SettingsUi.card(this)
        colors.addView(SettingsUi.heading(this, "颜色"))
        // 键盘背景 / 工具栏背景 / 候选栏背景已合并为「整体键盘背景」，工具栏与候选栏不再单独调色
        AppearanceColor.entries
            .filterNot { it == AppearanceColor.TOOLBAR || it == AppearanceColor.CANDIDATE_BAR }
            .forEach { target ->
                colors.addView(colorRow(target, appearance.color(target), resolvedTheme))
            }
        colors.addView(
            SettingsUi.sliderRow(
                this,
                "键盘背景不透明度",
                appearance.keyboardBackgroundAlpha * 100f,
                AppearanceConfig.MIN_KEYBOARD_BACKGROUND_ALPHA * 100f,
                AppearanceConfig.MAX_KEYBOARD_BACKGROUND_ALPHA * 100f,
                "%",
            ) { percent ->
                settingsRepository.setKeyboardBackgroundAlpha(percent / 100f)
                refreshAppearancePreview()
            },
        )
        colors.addView(
            SettingsUi.hint(
                this,
                "键盘底色半透明时，背后内容会自动模糊成毛玻璃（需 Android 12+；低版本退化为纯半透明）。",
            ),
        )
        colors.addView(
            SettingsUi.button(this, "恢复默认外观") {
                settingsRepository.resetAppearance()
                Toast.makeText(this, "外观已恢复默认", Toast.LENGTH_SHORT).show()
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, colors))

        val radii = SettingsUi.card(this)
        radii.addView(SettingsUi.heading(this, "圆角"))
        AppearanceRadius.entries.forEach { target ->
            val row = SettingsUi.sliderRow(
                this,
                target.label,
                appearance.radiusDp(target, target.defaultDp),
                target.minDp,
                target.maxDp,
                "dp",
            ) { value ->
                settingsRepository.setRadius(target, value)
                refreshAppearancePreview()
            }
            if (target == AppearanceRadius.CLIPBOARD_CARD) {
                // 剪贴板卡片不在键盘预览里，单独放一张示例卡片，否则这一项拖了没任何反馈
                val sampleTheme = appearanceTheme()
                val sample = TextView(this).apply {
                    text = "剪贴板卡片"
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimensionPixelSize(R.dimen.app_text_body).toFloat(),
                    )
                    setTextColor(sampleTheme.candidateText)
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(sampleTheme.panelSurface)
                        cornerRadius = sampleTheme.clipboardCardRadius
                    }
                }
                clipboardCardSample = sample
                row.addView(
                    sample,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8f) },
                )
            }
            radii.addView(row)
        }
        content.addView(SettingsUi.cardView(this, radii))
    }

    /**
     * 配色主题横条：每张卡就是一套配色的迷你示意（候选栏 + 键帽 + 强调色）。
     *
     * 预览用的颜色就是当前深浅模式下真正会上键盘的颜色；点一下立即生效并重建页面。
     */
    private fun paletteStrip(isDark: Boolean): View {
        // 「标准」的预览取资源默认色（不含用户手改项），与其它主题一样展示主题本身
        val standard = KeyboardTheme.from(
            this,
            preferences.themeMode,
            preferences.candidateTextSize,
            palette = KeyboardPalette.STANDARD,
        )
        val selected = settingsRepository.palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2f), 0, dp(2f))
        }
        KeyboardPalette.entries.forEach { palette ->
            val colors = palette.colors(isDark)
            val preview = if (colors == null) {
                PalettePreview(
                    background = standard.keyboardBackground,
                    key = standard.keyBackground,
                    functionKey = standard.functionKeyBackground,
                    panel = standard.panelBackground,
                    accent = standard.accent,
                    text = standard.keyText,
                )
            } else {
                PalettePreview(
                    background = colors.keyboardBackground,
                    key = colors.keyBackground,
                    functionKey = colors.functionKeyBackground,
                    panel = colors.panelBackground,
                    accent = colors.accent,
                    text = colors.keyText,
                )
            }
            row.addView(paletteCard(palette, preview, palette == selected))
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row)
            paletteStripView = this
            post { scrollTo(paletteStripScrollX, 0) }
        }
    }

    private fun paletteCard(
        palette: KeyboardPalette,
        preview: PalettePreview,
        selected: Boolean,
    ): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(132f), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(10f) }
            radius = dp(18f).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(preview.background)
            strokeWidth = dp(if (selected) 2f else 1f)
            strokeColor = if (selected) {
                preview.accent
            } else {
                androidx.core.graphics.ColorUtils.setAlphaComponent(preview.text, 40)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                paletteStripScrollX = paletteStripView?.scrollX ?: 0
                settingsRepository.palette = palette
                render()
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(10f), dp(10f), dp(9f))
        }
        // 候选栏示意条
        content.addView(
            View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(preview.panel)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(10f),
                )
            },
        )
        // 三个迷你键帽（普通键 + 功能键）
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30f),
            ).apply { topMargin = dp(8f) }
        }
        repeat(3) { index ->
            keys.addView(
                TextView(this).apply {
                    text = "Aa"
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(preview.text)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(7f).toFloat()
                        setColor(if (index == 0) preview.key else preview.functionKey)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f,
                    ).apply { if (index > 0) marginStart = dp(6f) }
                },
            )
        }
        content.addView(keys)
        // 名称 + 一句说明 + 强调色圆点
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(9f) }
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(
            TextView(this).apply {
                text = palette.label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(preview.text)
                if (selected) typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        textColumn.addView(
            TextView(this).apply {
                text = palette.caption
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        preview.text,
                        (0.62f * 255).toInt(),
                    ),
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        labels.addView(textColumn)
        labels.addView(
            View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(preview.accent)
                }
                layoutParams = LinearLayout.LayoutParams(dp(12f), dp(12f)).apply { marginStart = dp(6f) }
            },
        )
        content.addView(labels)
        card.addView(content)
        return card
    }

    /** 配色主题卡片上需要的最小颜色集合 */
    private data class PalettePreview(
        val background: Int,
        val key: Int,
        val functionKey: Int,
        val panel: Int,
        val accent: Int,
        val text: Int,
    )

    /**
     * 键盘尺寸与精细调整（高度档位 / 整体高度 / 按键间距 / 功能键宽度）。
     *
     * 全部是**拖动即生效**：写进 [SettingsRepository] 后立刻刷新下方预览；
     * 输入法服务订阅同一个 SharedPreferences，收到变更后只重算尺寸 / 重绘键盘。
     */
    private fun buildAppearanceGeometryCard(content: LinearLayout) {
        val scope = geometryScope
        val geometry = settingsRepository.geometry(scope)

        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "键盘尺寸与间距"))
        card.addView(
            SettingsUi.hint(
                this,
                "拖动即生效，预览同步变化；竖屏 / 横屏 / 悬浮分别保存。",
            ),
        )
        card.addView(
            SettingsUi.toggleGroup(
                this,
                KeyboardScope.entries.map { it.label },
                scope.ordinal,
            ) { index ->
                geometryScope = KeyboardScope.entries[index]
                render()
            },
        )

        val levelGroup = SettingsUi.toggleGroup(
            this,
            KeyboardHeightLevel.entries.map { it.label },
            geometry.level.ordinal,
        ) { index ->
            if (syncingHeightLevel) return@toggleGroup
            settingsRepository.updateGeometry(scope) {
                it.copy(level = KeyboardHeightLevel.entries[index])
            }
            render()
        }
        card.addView(levelGroup)

        val customId = levelGroup.getChildAt(KeyboardHeightLevel.CUSTOM.ordinal)?.id
        fun syncCustomLevel() {
            if (customId == null || levelGroup.checkedButtonId == customId) return
            syncingHeightLevel = true
            levelGroup.check(customId)
            syncingHeightLevel = false
        }

        card.addView(
            SettingsUi.sliderRow(
                this,
                "整体高度",
                // 显示「当前实际高度」：大 / 特大 档位也各有倍率，显示自定义值会让人以为档位没生效
                value = geometry.heightFactor,
                min = KeyboardGeometry.MIN_CUSTOM_FACTOR,
                max = KeyboardGeometry.MAX_CUSTOM_FACTOR,
                unit = "x",
            ) { value ->
                // 拖「整体高度」自动切到自定义档，避免出现「档位是大、却拖了标准值」的困惑
                settingsRepository.updateGeometry(scope) {
                    it.copy(level = KeyboardHeightLevel.CUSTOM, customHeightFactor = value)
                }
                syncCustomLevel()
                refreshAppearancePreview()
            },
        )

        card.addView(
            SettingsUi.sliderRow(
                this,
                "按键间距",
                value = if (geometry.hasCustomGap) geometry.keyGapDp else 6f,
                min = KeyboardGeometry.MIN_GAP_DP,
                max = KeyboardGeometry.MAX_GAP_DP,
                unit = "dp",
            ) { value ->
                settingsRepository.updateGeometry(scope) { it.copy(keyGapDp = value) }
                refreshAppearancePreview()
            },
        )

        card.addView(
            SettingsUi.button(this, "恢复${scope.label}默认尺寸") {
                settingsRepository.resetGeometry(scope)
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, card))
    }

    /** 一行颜色项：名称 + 当前值 + 色块；点击选色 */
    private fun colorRow(
        target: AppearanceColor,
        current: Int?,
        theme: KeyboardTheme,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10f), 0, dp(10f))
        isClickable = true
        setOnClickListener { pickColor(target, current, theme) }
        addView(
            TextView(this@SettingsSectionActivity).apply {
                text = target.label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(
            TextView(this@SettingsSectionActivity).apply {
                text = if (current == null) "默认" else "#%06X".format(0xFFFFFF and current)
                textSize = 13f
                setTextColor(secondaryColor())
            },
        )
        addView(
            View(this@SettingsSectionActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(current ?: defaultColorOf(target, theme))
                    cornerRadius = dp(5f).toFloat()
                    setStroke(dp(1f), 0x33000000)
                }
                layoutParams = LinearLayout.LayoutParams(dp(30f), dp(20f)).apply { marginStart = dp(10f) }
            },
        )
    }

    /** 某一项在「默认」状态下实际使用的颜色（用于色块预览） */
    private fun defaultColorOf(target: AppearanceColor, theme: KeyboardTheme): Int = when (target) {
        AppearanceColor.ACCENT -> theme.accent
        AppearanceColor.KEYBOARD_BACKGROUND -> theme.keyboardBackground
        AppearanceColor.KEY_BACKGROUND -> theme.keyBackground
        AppearanceColor.KEY_TEXT -> theme.keyText
        AppearanceColor.CANDIDATE_BAR -> theme.panelBackground
        AppearanceColor.CANDIDATE_TEXT -> theme.candidateText
        AppearanceColor.CANDIDATE_SELECTED -> theme.candidateSelectedBackground
        AppearanceColor.TOOLBAR -> theme.toolbarBackground
    }

    /** 选色：预设色板 / 手输 #RRGGBB / 恢复默认 */
    private fun pickColor(target: AppearanceColor, current: Int?, theme: KeyboardTheme) {
        val names = COLOR_PRESETS.map { (name, value) -> "$name  #%06X".format(0xFFFFFF and value) } +
            listOf("自定义（输入 #RRGGBB）", "恢复默认")
        MaterialAlertDialogBuilder(this)
            .setTitle(target.label)
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < COLOR_PRESETS.size -> {
                        settingsRepository.setColor(target, COLOR_PRESETS[which].second)
                        render()
                    }

                    which == COLOR_PRESETS.size -> promptHexColor(target, current ?: defaultColorOf(target, theme))
                    else -> {
                        settingsRepository.setColor(target, null)
                        render()
                    }
                }
            }
            .show()
    }

    private fun promptHexColor(target: AppearanceColor, initial: Int) {
        val input = EditText(this).apply {
            hint = "#RRGGBB"
            setText("#%06X".format(0xFFFFFF and initial))
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${target.label} · 自定义颜色")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val parsed = parseHexColor(input.text?.toString().orEmpty())
                if (parsed == null) {
                    Toast.makeText(this, "格式不对，请用 #RRGGBB", Toast.LENGTH_SHORT).show()
                } else {
                    settingsRepository.setColor(target, parsed)
                    render()
                }
            }
            .show()
    }

    /** 接受 `#RRGGBB` / `RRGGBB`（大小写均可） */
    private fun parseHexColor(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if (hex.length != 6 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return runCatching { 0xFF000000.toInt() or hex.toLong(16).toInt() }.getOrNull()
    }

    private fun buildAdvanced(content: LinearLayout) {
        buildStorageCard(content)
        buildPrivacy(content)
        buildAbout(content)
    }

    /** 高级 → 存储空间：先看清楚占用，再分别清理（绝不动词库/学习数据/收藏） */
    private fun buildStorageCard(content: LinearLayout) {
        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "存储空间"))
        val out = SettingsUi.value(this, "统计中…")
        card.addView(out)

        fun cleanWith(label: String, action: suspend () -> com.board.ime.storage.CleanResult) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { action() } }
                val message = result.fold(
                    onSuccess = { "$label：${it.summary}" },
                    onFailure = { "$label 失败：${it.javaClass.simpleName}" },
                )
                Toast.makeText(this@SettingsSectionActivity, message, Toast.LENGTH_LONG).show()
                out.text = renderStorage(auditStorage())
            }
        }

        card.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "清理缓存") {
                        cleanWith("清理缓存") { storageManager().cleanCache() }
                    },
                    SettingsUi.button(this, "清理同步临时文件") {
                        cleanWith("清理同步临时文件") { storageManager().cleanSyncTemp() }
                    },
                ),
            ),
        )
        card.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "清理旧词库") {
                        cleanWith("清理旧词库") { storageManager().cleanOldDictionaries() }
                    },
                    SettingsUi.button(this, "回收已删除剪贴板") {
                        cleanWith("回收已删除剪贴板") { storageManager().cleanDeletedClipboard() }
                    },
                ),
            ),
        )
        card.addView(
            SettingsUi.button(this, "检查重复词库") {
                lifecycleScope.launch {
                    val groups = withContext(Dispatchers.IO) {
                        runCatching { storageManager().duplicateReport() }.getOrDefault(emptyList())
                    }
                    val text = if (groups.isEmpty()) {
                        "没有发现内容重复的词库文件 ✓"
                    } else {
                        buildString {
                            append("发现 ${groups.size} 组重复：\n")
                            groups.take(5).forEach { group ->
                                append("· ${group.label}，可省 ${group.wastedBytes / 1024 / 1024} MB\n")
                            }
                        }
                    }
                    MaterialAlertDialogBuilder(this@SettingsSectionActivity)
                        .setTitle("重复词库检查")
                        .setMessage(text)
                        .setPositiveButton("好", null)
                        .show()
                }
            },
        )
        card.addView(
            SettingsUi.hint(
                this,
                "只清理缓存、无引用词库文件与已删除剪贴板；学习数据、用户词库、收藏不动。",
            ),
        )
        content.addView(SettingsUi.cardView(this, card))

        lifecycleScope.launch { out.text = renderStorage(auditStorage()) }
    }

    private suspend fun auditStorage() = withContext(Dispatchers.IO) {
        runCatching { storageManager().audit() }.getOrNull()
    }

    private fun renderStorage(report: com.board.ime.storage.StorageReport?): String {
        if (report == null) return "统计失败（见日志）"
        fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)
        return buildString {
            append("总占用：${mb(report.totalBytes)}（APK ${mb(report.apkBytes)} + 数据 ${mb(report.dataBytes)}）\n")
            report.items.forEach { append("├─ ${it.label}：${it.bytes / 1024 / 1024} MB\n") }
            append("可回收：${mb(report.reclaimableBytes)}")
            if (report.duplicates.isNotEmpty()) {
                append("（另有重复 ${mb(report.duplicateBytes)}）")
            }
        }
    }

    /**
     * 合并重复词库：本地按内容 hash 只留一条 source，并尽力清理云端多余目录。
     * 会联网（DELETE + 重写 index.json），失败不影响本地结果。
     */
    private fun mergeDuplicateSources(out: TextView) {
        val engine = SyncEngineHolder.get(this)
        val config = engine.loadConfig()
        if (!config.isConfigured) {
            Toast.makeText(this, "请先在「同步」里配置 WebDAV", Toast.LENGTH_SHORT).show()
            return
        }
        out.text = "正在合并重复词库…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val client = com.board.ime.sync.OkHttpWebDavClientFactory.create(config)
                    com.board.ime.sync.DictionarySourceSync(
                        client = client,
                        sources = boardDatabase.dictionarySourceDao(),
                        install = { false },
                    ).mergeDuplicateSources(config.normalizedDir)
                }.getOrNull()
            }
            Toast.makeText(
                this@SettingsSectionActivity,
                report?.summary ?: "合并失败（见日志）",
                Toast.LENGTH_LONG,
            ).show()
            render()
        }
    }
    /**
     * 扫描云端 `dictionary/sources/`，删除没有任何本地数据源引用的孤儿目录。
     * 会联网（PROPFIND + DELETE），属于破坏性操作 —— 因此只删「目录名不是任何存活数据源
     * 内容 hash」的目录，活的目录一律不碰。
     */
    private fun sweepRemoteOrphans(out: TextView) {
        val engine = SyncEngineHolder.get(this)
        val config = engine.loadConfig()
        if (!config.isConfigured) {
            Toast.makeText(this, "请先在「同步」里配置 WebDAV", Toast.LENGTH_SHORT).show()
            return
        }
        out.text = "正在扫描云端目录…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val client = com.board.ime.sync.OkHttpWebDavClientFactory.create(config)
                    com.board.ime.sync.DictionarySourceSync(
                        client = client,
                        sources = boardDatabase.dictionarySourceDao(),
                        install = { false },
                    ).sweepRemoteOrphans(config.normalizedDir)
                }.getOrNull()
            }
            Toast.makeText(
                this@SettingsSectionActivity,
                report?.summary ?: "扫描失败（见日志）",
                Toast.LENGTH_LONG,
            ).show()
            render()
        }
    }
    /** 剪贴板通道的 5 态文案：**只有真实 HTTP 2xx 才显示「已同步」** */
    private fun clipboardStateText(status: com.board.ime.sync.SyncStatus): String =
        when (status.clipboardState) {
            com.board.ime.sync.ClipboardSyncState.IDLE -> "无需同步"
            com.board.ime.sync.ClipboardSyncState.PENDING -> "待同步"
            com.board.ime.sync.ClipboardSyncState.SYNCING -> "同步中…"
            com.board.ime.sync.ClipboardSyncState.SYNCED ->
                "已同步" + (status.lastPushedHash?.take(8)?.let { " · $it" } ?: "")

            com.board.ime.sync.ClipboardSyncState.FAILED ->
                "同步失败" + (status.clipboardError?.let { " · $it" } ?: "")
        }
    private fun storageManager() = com.board.ime.storage.StorageManager(
        this,
        boardDatabase.dictionarySourceDao(),
        boardDatabase.clipboardDao(),
    )

    // ---------------------------------------------------------------- 键盘

    private fun buildKeyboard(content: LinearLayout) {
        val layout = SettingsUi.card(this)
        layout.addView(SettingsUi.heading(this, "键盘布局"))
        layout.addView(
            SettingsUi.toggleGroup(
                this,
                LayoutMode.entries.map { it.label },
                preferences.layoutMode.ordinal,
            ) { index ->
                preferences.layoutMode = LayoutMode.entries[index]
            },
        )
        layout.addView(SettingsUi.hint(this, "自动：竖屏铺满、横屏 / 大屏悬浮。"))
        content.addView(SettingsUi.cardView(this, layout))

        // 高度 / 间距 / 功能键宽度都在「外观」里实时调整，这一页只保留布局与行为

        // ---- 键盘位置：键盘抬升（只影响底部留白） ----
        val positionCard = SettingsUi.card(this)
        positionCard.addView(SettingsUi.heading(this, "键盘位置"))
        positionCard.addView(
            SettingsUi.switchRow(this, "键盘抬升", preferences.keyboardLiftEnabled) {
                preferences.keyboardLiftEnabled = it
            },
        )
        positionCard.addView(
            SettingsUi.hint(
                this,
                "只调整底部留白：开启 92px，关闭 24px；不动键高。",
            ),
        )
        content.addView(SettingsUi.cardView(this, positionCard))

        val behaviour = SettingsUi.card(this)
        behaviour.addView(SettingsUi.heading(this, "按键行为"))
        behaviour.addView(
            SettingsUi.switchRow(this, "按键音效", preferences.soundEnabled) {
                preferences.soundEnabled = it
            },
        )
        behaviour.addView(
            SettingsUi.switchRow(this, "按键震动", preferences.vibrateEnabled) {
                preferences.vibrateEnabled = it
            },
        )
        behaviour.addView(
            SettingsUi.switchRow(this, "字母与汉字之间自动加空格", preferences.autoSpaceLetters) {
                preferences.autoSpaceLetters = it
            },
        )
        behaviour.addView(
            SettingsUi.switchRow(this, "数字与汉字之间自动加空格", preferences.autoSpaceDigits) {
                preferences.autoSpaceDigits = it
            },
        )
        behaviour.addView(
            SettingsUi.hint(
                this,
                "开启后自动在中英文、数字与汉字之间补空格。",
            ),
        )
        content.addView(SettingsUi.cardView(this, behaviour))

        val tryCard = SettingsUi.card(this)
        tryCard.addView(SettingsUi.heading(this, "试用"))
        val field = EditText(this).apply {
            hint = "在这里试打一段文字…"
            setSingleLine(false)
            minLines = 2
            gravity = Gravity.TOP
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.app_radius_md)
                setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
            }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.app_text_body),
            )
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setHintTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        // 上屏测试按钮放在输入框**上面**：点输入框后键盘弹起也不会挡住它
        val imeOut = SettingsUi.value(this, "上屏测试：点一下下面的输入框，再点这个按钮")
        if (preferences.developerMode) {
            tryCard.addView(imeOut)
            tryCard.addView(
                SettingsUi.button(this, "测试上屏") {
                    runImeCommitTest(field, imeOut)
                },
            )
        }
        tryCard.addView(field)
        content.addView(SettingsUi.cardView(this, tryCard))
    }

    /**
     * 上屏测试：**走 Board 自己真实的 InputConnection**（不是 adb 注入）。
     *
     * 目标就是上面这个输入框，所以测的是「Board → 目标 App」这一跳，
     * 与「复制 → 入库」是两个不同的问题。
     */
    private fun runImeCommitTest(field: EditText, out: TextView) {
        val service = BoardInputMethodService.active
        if (service == null || !service.debugHasConnection()) {
            out.text = "上屏测试：Board 输入法当前没有输入连接（先点一下下面的输入框）"
            return
        }
        val sizes = listOf(10_000, 100_000, 500_000, 1_000_000, 2_000_000)
        out.text = "正在测试上屏…"
        lifecycleScope.launch(Dispatchers.Main) {
            val lines = ArrayList<String>()
            sizes.forEach { size ->
                val text = ClipboardStressTest.generate(size, "ime")
                field.setText("")
                val ok = service.debugCommitText(text)
                val actual = field.text?.length ?: -1
                lines += when {
                    !ok -> "${size / 1000}KB: ✗ 提交失败（IPC 异常）"
                    actual == size -> "${size / 1000}KB: ✓ 目标输入框已收下"
                    else -> "${size / 1000}KB: △ 提交返回 true，实际收下 $actual 字"
                }
                field.setText("")
            }
            out.text = "上屏测试结果：\n" + lines.joinToString("\n")
        }
    }

    // ---------------------------------------------------------------- 剪贴板

    // ================================================================
    // 云端资料库（1B）
    // ================================================================

    /**
     * Activity 生命周期内**只创建一次**（by lazy）。
     * 不建常驻 Service / 常驻线程；不碰 Clipboard / B1；deviceId 沿用项目现有 ANDROID_ID 表达式。
     */
    private val libraryRepository: com.board.ime.cloudlibrary.CloudLibraryRepository by lazy {
        val config = com.board.ime.sync.webdav.WebDavConfigStore(applicationContext).load()
        val client = com.board.ime.sync.OkHttpWebDavClientFactory.create(config)
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ).orEmpty()
        com.board.ime.cloudlibrary.CloudLibraryRepository(
            remote = com.board.ime.cloudlibrary.CloudLibraryRemote(client, deviceId),
            sink = com.board.ime.cloudlibrary.AndroidLibraryFileSink(applicationContext),
            uploadSource = com.board.ime.cloudlibrary.AndroidLibraryUploadSource(contentResolver),
            deviceId = deviceId,
        )
    }

    private var libraryPanel: com.board.ime.cloudlibrary.LibraryPanel? = null

    /** SAF 单文件上传（与既有 OpenDocument 用法一致；单文件、不接受文件夹） */
    private val libraryUploadLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) libraryPanel?.upload(uri)
    }

    private fun buildLibrary(content: LinearLayout) {
        val panel = com.board.ime.cloudlibrary.LibraryPanel(
            this,
            libraryRepository,
            com.board.ime.cloudlibrary.LibraryCacheStore(this),
        )
        libraryPanel = panel
        content.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.onPickUpload = {
            libraryUploadLauncher.launch(arrayOf("image/*", "application/*", "*/*"))
        }
        // 本机隐藏（只存 hash；只影响本机 UI，不动云端）
        panel.hiddenHashesProvider = { preferences.hiddenLibraryHashes }
        panel.onHide = { hash -> preferences.hideLibraryHash(hash) }
        panel.onUnhide = { hash -> preferences.unhideLibraryHash(hash) }
        // 打开即显示 UI；refresh/prune 在后台；页面 RESUMED 时每 60s 自动刷新
        panel.bindLifecycle(this)
        panel.start()
    }

    override fun onDestroy() {
        libraryPanel?.release()
        libraryPanel = null
        super.onDestroy()
    }


    private fun buildClipboard(content: LinearLayout) {
        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "剪贴板历史"))
        val counts = SettingsUi.value(this, "统计中…")
        card.addView(counts)
        card.addView(SettingsUi.hint(this, "在键盘顶部的剪贴板面板里查看、搜索、收藏与固定。"))
        card.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "导入文本文件") { importTextFileLauncher.launch(arrayOf("text/*")) },
                    SettingsUi.button(this, "清空全部历史") { confirmClearClipboard() },
                ),
            ),
        )
        card.addView(
            SettingsUi.hint(
                this,
                "大文本存成文件，不影响列表与搜索速度。",
            ),
        )
        content.addView(SettingsUi.cardView(this, card))

        val sensitive = SettingsUi.card(this)
        sensitive.addView(SettingsUi.heading(this, "敏感内容"))
        sensitive.addView(
            SettingsUi.switchRow(this, "验证码 / 密码不写入历史", preferences.skipSensitiveClipboard) {
                preferences.skipSensitiveClipboard = it
            },
        )
        sensitive.addView(SettingsUi.hint(this, "启发式判定，命中时会在键盘上提示并且不入库。"))
        content.addView(SettingsUi.cardView(this, sensitive))

        if (preferences.developerMode) {
            val stress = SettingsUi.card(this)
            stress.addView(SettingsUi.heading(this, "大文本自测（开发者）"))
            val out = SettingsUi.value(this, "100KB ~ 10MB 逐档：抓取 / 列表 / 搜索 / 全文 / 删除 / 系统剪贴板")
            stress.addView(out)
            // 阈值实测：256KB vs 512KB，分别跑一遍比较
            stress.addView(
                SettingsUi.buttonRow(
                    this,
                    listOf(
                        SettingsUi.button(this, "跑测试 · 内联阈值 256KB") { runClipboardStress(out, 256L * 1024L) },
                        SettingsUi.button(this, "跑测试 · 内联阈值 512KB") { runClipboardStress(out, 512L * 1024L) },
                    ),
                ),
            )
            stress.addView(
                SettingsUi.hint(
                    this,
                    "超过阈值的文本存到私有文件；系统剪贴板写入可能被 Android 拒绝。",
                ),
            )
            content.addView(SettingsUi.cardView(this, stress))
        }

        lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) {
                // 老版本把超长内容直接写在 content 列里；这里把能搬的搬进文件，
                // 之后「全文」也能稳定打开（列表本来就已经不读 content 了）
                runCatching { clipboardRepository.migrateLargeInlineRows() }
                runCatching { clipboardRepository.rebuildSearchIndex() }
                clipboardRepository.counts().total
            }
            counts.text = "共 $total 条"
        }
    }

    /** 「导入文本文件」：几十 MB 的纯文本也能进入 Board 自己的剪贴板系统 */
    private fun handleTextFileImport(uri: Uri) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    } ?: throw IllegalStateException("无法读取文件")
                    clipboardRepository.importText(text)
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    val message = when (result) {
                        com.board.ime.clipboard.ClipboardCaptureResult.INSERTED -> "已导入 Board 剪贴板"
                        com.board.ime.clipboard.ClipboardCaptureResult.UPDATED -> "这段内容已经在剪贴板里了"
                        com.board.ime.clipboard.ClipboardCaptureResult.SKIPPED_SENSITIVE -> "疑似敏感内容，未导入"
                        com.board.ime.clipboard.ClipboardCaptureResult.SKIPPED_EMPTY -> "文件里没有文本"
                    }
                    Toast.makeText(this@SettingsSectionActivity, message, Toast.LENGTH_SHORT).show()
                    render()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SettingsSectionActivity,
                        "导入失败：${error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    /** 开发者模式：逐档自测剪贴板大文本（真抓取 / 真分流 / 真列表 / 真全文 / 真删除） */
    private fun runClipboardStress(out: TextView, inlineMaxBytes: Long) {
        out.text = "正在运行（10MB 档会比较慢）…"
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching {
                    ClipboardStressTest.run(
                        boardDatabase,
                        getSystemService(android.content.ClipboardManager::class.java),
                        filesDir,
                        inlineMaxBytes,
                    )
                }.getOrElse { throwable ->
                    Log.w("BoardClipboard", "压力测试失败：${throwable.message}")
                    emptyList()
                }
            }
            if (rows.isEmpty()) {
                out.text = "压力测试失败（见日志）"
                return@launch
            }
            out.text = buildString {
                append("内联阈值 ${inlineMaxBytes / 1024}KB\n")
                rows.forEach { row ->
                    val verdict = when {
                        row.error != null -> "✗ ${row.error}"
                        row.boardOk -> "Board 全链路 ✓"
                        else -> "△ 部分环节未通过"
                    }
                    val clip = when {
                        row.clipboardOk -> "系统剪贴板 ✓"
                        else -> "系统限制：${row.clipboardError.orEmpty()}"
                    }
                    append(
                        "${row.label}（${row.bytes}B，${row.storage}）: " +
                            "哈希 ${row.hashMs}ms 抓取 ${row.captureMs}ms 存 ${row.storedChars} 字\n" +
                            "  列表 ${ok(row.listHit)} 搜索 ${ok(row.searchHit)} " +
                            "全文 ${ok(row.contentIntact)} 删除 ${ok(row.deletedOk)}\n" +
                            "  $verdict · $clip\n",
                    )
                }
            }
            Toast.makeText(this@SettingsSectionActivity, "压力测试完成", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ok(value: Boolean): String = if (value) "✓" else "✗"

    private fun confirmClearClipboard() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空全部历史？")
            .setMessage("固定和收藏默认保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) { clipboardRepository.clearHistory(false) }
                    Toast.makeText(this@SettingsSectionActivity, "已清空 $removed 条", Toast.LENGTH_SHORT)
                        .show()
                    render()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- 词库

    private fun buildDictionary(content: LinearLayout) {
        val counts = SettingsUi.card(this)
        counts.addView(SettingsUi.heading(this, "我的词库"))
        val value = SettingsUi.value(this, "统计中…")
        counts.addView(value)
        counts.addView(
            SettingsUi.button(this, "管理我的词库") {
                startActivity(Intent(this, DictionaryActivity::class.java))
            },
        )
        content.addView(SettingsUi.cardView(this, counts))

        val io = SettingsUi.card(this)
        io.addView(SettingsUi.heading(this, "导入 / 导出"))
        io.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "导入") { chooseImportFormat() },
                    SettingsUi.button(this, "导出") { chooseExportScope() },
                ),
            ),
        )
        io.addView(SettingsUi.hint(this, "TXT / CSV / JSON / Rime 词库；导入会去重、不覆盖已有词条。"))
        content.addView(SettingsUi.cardView(this, io))

        val learned = SettingsUi.card(this)
        learned.addView(SettingsUi.heading(this, "学习词"))
        learned.addView(
            SettingsUi.button(this, "清空学习记录") { confirmClearLearned() },
        )
        learned.addView(SettingsUi.hint(this, "会删除 librime 用户词典与本地镜像；自定义词不受影响。"))
        learned.addView(
            SettingsUi.hint(
                this,
                "30 天未用的学习词会靠后，60 天后不再加成；使用时间只存本机。",
            ),
        )
        content.addView(SettingsUi.cardView(this, learned))

        // 导入词库（大型文件）：条目 / 大小 / 是否已接入 Rime
        val sourcesCard = SettingsUi.card(this)
        sourcesCard.addView(SettingsUi.heading(this, "导入词库（大型文件）"))
        val sourcesValue = SettingsUi.value(this, "读取中…")
        sourcesCard.addView(sourcesValue)
        sourcesCard.addView(
            SettingsUi.button(this, "合并重复词库") {
                mergeDuplicateSources(sourcesValue)
            },
        )
        sourcesCard.addView(
            SettingsUi.hint(
                this,
                "按内容去重，只保留一条数据源；云端清理失败不影响本地结果。",
            ),
        )
        val deployButton = SettingsUi.button(this, "部署到 Rime") {
            deployImportedSources(sourcesValue)
        }
        sourcesCard.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "清理云端孤儿目录") { sweepRemoteOrphans(sourcesValue) },
                    deployButton,
                ),
            ),
        )
        sourcesCard.addView(
            SettingsUi.hint(
                this,
                "大型词库以文件保存，不占用户词表。",
            ),
        )
        content.addView(SettingsUi.cardView(this, sourcesCard))

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                // 首次打开设置也顺手完成「历史大词库 → 数据源」迁移（幂等、不丢数据）
                runCatching { dictionaryRepository.migrateLegacyImportedEntries() }
                runCatching { dictionaryRepository.migrateToCompressedStore() }
                runCatching { dictionaryRepository.ensureSourceIds() }
                runCatching { dictionaryRepository.refreshLearnedFromRime() }
                listOf(
                    dictionaryRepository.countAll(),
                    dictionaryRepository.countByType(WordType.LEARNED),
                    dictionaryRepository.countByType(WordType.CUSTOM),
                    dictionaryRepository.countByType(WordType.PHRASE),
                    dictionaryRepository.countByType(WordType.PINNED),
                    dictionaryRepository.countByType(WordType.BLOCKED),
                    dictionaryRepository.sourceCount(),
                )
            }
            value.text = buildString {
                append("系统词库：未接入\n")
                append("导入词库：${data[6]} 个数据源（文件形式，不占用户词表）\n")
                append("我的词：自定义 ${data[2]} · 固定 ${data[4]}\n")
                append("学习词：${data[1]} · 屏蔽词：${data[5]} · 常用语：${data[3]}")
            }
            renderSources(sourcesValue, deployButton)
        }
    }

    /** 列出导入词库数据源：名字 / 条数 / 大小 / Rime 是否可用 */
    private fun renderSources(value: TextView, deployButton: android.widget.Button) {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { dictionaryRepository.importedSources() }
            if (sources.isEmpty()) {
                value.text = "暂无导入的大型词库。"
                deployButton.isEnabled = false
                return@launch
            }
            deployButton.isEnabled = true
            value.text = sources.joinToString("\n") { source ->
                val rime = if (source.ready) "已接入 Rime" else "未接入 Rime"
                "${source.name}\n  ${source.entryCount} 条 · ${source.sizeLabel} · v${source.version} · $rime"
            }
        }
    }

    /** 手动把导入词库部署到 Rime（大词库不自动部署，避免原生侧内存不可控） */
    private fun deployImportedSources(value: TextView) {
        value.text = "正在生成词典配置并部署…（大词库可能需要一会儿）"
        lifecycleScope.launch {
            val (ready, total) = withContext(Dispatchers.IO) {
                runCatching { dictionaryRepository.deployImportedSources() }.getOrDefault(0 to 0)
            }
            Toast.makeText(
                this@SettingsSectionActivity,
                if (total == 0) "没有可部署的导入词库" else "已接入 Rime：$ready / $total",
                Toast.LENGTH_LONG,
            ).show()
            render()
        }
    }

    private fun confirmClearLearned() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空学习记录？")
            .setMessage("将删除 librime 用户词典与本地镜像。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dictionaryRepository.clearLearned() }
                    Toast.makeText(this@SettingsSectionActivity, "学习记录已清空", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .show()
    }

    private fun chooseImportFormat() {
        val formats = DictionaryFormat.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("按哪种格式解析？")
            .setItems(formats.map { it.label }.toTypedArray()) { _, which ->
                pendingImportFormat = formats[which]
                importLauncher.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw DictionaryFormatException("无法读取文件")
                    // 大词库走「数据源文件」，小词表才进 Room（避免几十万行灌进数据库）
                    val result = dictionaryRepository.importIntoStore(
                        text,
                        pendingImportFormat,
                        WordType.CUSTOM,
                        sourceName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                            ?: "imported",
                    )
                    dictionaryRepository.syncCustomPhrasesToRime()
                    result
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    MaterialAlertDialogBuilder(this@SettingsSectionActivity)
                        .setTitle("导入结果")
                        .setMessage(result.summary)
                        .setPositiveButton("好", null)
                        .show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SettingsSectionActivity,
                        "导入失败：${error.message}",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }

    private fun chooseExportScope() {
        val scopes = ExportScope.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("导出范围")
            .setItems(scopes.map { it.label }.toTypedArray()) { _, which ->
                pendingExportScope = scopes[which]
                chooseExportFormat()
            }
            .show()
    }

    private fun chooseExportFormat() {
        val formats = DictionaryFormat.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("导出格式")
            .setItems(formats.map { it.label }.toTypedArray()) { _, which ->
                pendingExportFormat = formats[which]
                val name = "board_dictionary"
                when (pendingExportFormat) {
                    DictionaryFormat.JSON -> exportJsonLauncher.launch(name)
                    DictionaryFormat.CSV -> exportCsvLauncher.launch(name)
                    else -> exportTextLauncher.launch(name)
                }
            }
            .show()
    }

    private fun handleExport(uri: Uri) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val content = dictionaryRepository.export(pendingExportScope, pendingExportFormat)
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                dictionaryRepository.exportCount(pendingExportScope)
            }
            Toast.makeText(this@SettingsSectionActivity, "已导出 $count 条", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 账号

    private fun buildAccount(content: LinearLayout) {
        val engine = SyncEngineHolder.get(this)
        val config = engine.loadConfig()
        val configured = config.isConfigured
        val avatarStore = com.board.ime.profile.AvatarStore

        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "账号"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(6f))
        }
        val avatar = com.board.ime.ui.AvatarView(this).apply {
            setAvatar(avatarStore.loadBitmap(this@SettingsSectionActivity))
            isClickable = true
            isFocusable = true
            contentDescription = "设置头像"
            setOnClickListener { requestAvatarPick(configured) }
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(72f), dp(72f)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, 0, 0)
        }
        info.addView(
            TextView(this).apply {
                text = if (configured) "WebDAV 已登录" else "未登录"
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimensionPixelSize(R.dimen.app_text_body).toFloat(),
                )
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            },
        )
        info.addView(
            TextView(this).apply {
                text = if (configured) {
                    config.username.ifBlank { "已连接" }
                } else {
                    "点击头像或下方「从相册选择」前，请先登录"
                }
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimensionPixelSize(R.dimen.app_text_caption).toFloat(),
                )
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(2f), 0, 0)
            },
        )
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        card.addView(
            SettingsUi.hint(
                this,
                if (configured) {
                    "头像会自动居中裁成正方形，并随 WebDAV 同步到其它设备。"
                } else {
                    "登录 WebDAV 后即可设置头像；头像随同步上传。"
                },
            ),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        fun addAction(button: MaterialButton) {
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (actions.childCount > 0) marginStart = dp(12f) }
            actions.addView(button)
        }
        addAction(SettingsUi.button(this, "从相册选择") { requestAvatarPick(configured) })
        if (avatarStore.hasAvatar(this)) {
            addAction(
                SettingsUi.button(this, "清除头像") {
                    avatarStore.clear(this)
                    Toast.makeText(this, "已清除头像", Toast.LENGTH_SHORT).show()
                    render()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { SyncEngineHolder.get(this@SettingsSectionActivity).syncProfileOnly() }
                        }
                    }
                },
            )
        }
        card.addView(actions)
        content.addView(SettingsUi.cardView(this, card))

        // 登录与同步设置：服务器、同步开关、同步内容全部收在这一页
        buildSync(content, profileSyncOnSave = true)
    }

    private fun requestAvatarPick(configured: Boolean) {
        if (!configured) {
            Toast.makeText(this, "请先在下方填写并保存 WebDAV 配置", Toast.LENGTH_SHORT).show()
            return
        }
        avatarPickLauncher.launch("image/*")
    }

    // ---------------------------------------------------------------- 同步

    private fun buildSync(content: LinearLayout, profileSyncOnSave: Boolean = false) {
        val engine = SyncEngineHolder.get(this)
        val config = engine.loadConfig()
        val status = engine.status.value

        // 状态：已连接就**不显示**账号 / 服务器 / 路径
        val statusCard = SettingsUi.card(this)
        statusCard.addView(SettingsUi.heading(this, "同步状态"))
        val overallRow = SettingsUi.statusRow(
            this,
            "WebDAV",
            when (status.kind) {
                SyncStatusKind.NOT_CONFIGURED -> "未配置"
                SyncStatusKind.CONNECTED -> "已连接"
                SyncStatusKind.SYNCING ->
                    if (syncTimedOut) "同步超时（后台继续）" else "同步中…"

                SyncStatusKind.SUCCESS -> "刚刚同步"
                SyncStatusKind.FAILED -> "同步失败"
            },
            status.kind == SyncStatusKind.CONNECTED || status.kind == SyncStatusKind.SUCCESS,
        )
        // 「剪贴板」和「词库」各自独立显示：大型词库下载不会再让整页只显示「同步中」
        val clipboardRow = SettingsUi.statusRow(
            this,
            "剪贴板",
            clipboardStateText(status),
            status.clipboard.ok,
        )
        val dictionaryRow = SettingsUi.statusRow(
            this,
            "词库",
            status.dictionary.label,
            status.dictionary.ok,
        )
        statusCard.addView(overallRow)
        statusCard.addView(clipboardRow)
        statusCard.addView(dictionaryRow)

        val overallValue = overallRow.getChildAt(2) as TextView
        val clipboardValue = clipboardRow.getChildAt(2) as TextView
        val dictionaryValue = dictionaryRow.getChildAt(2) as TextView
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            SyncEngineHolder.get(this@SettingsSectionActivity).status.collect { live ->
                overallValue.text = when (live.kind) {
                    SyncStatusKind.NOT_CONFIGURED -> "未配置"
                    SyncStatusKind.CONNECTED -> "已连接"
                    SyncStatusKind.SYNCING -> if (syncTimedOut) "同步超时（后台继续）" else "同步中…"
                    SyncStatusKind.SUCCESS -> "刚刚同步"
                    SyncStatusKind.FAILED -> "同步失败"
                }
                clipboardValue.text = clipboardStateText(live)
                dictionaryValue.text = partText(live.dictionary)
            }
        }
        val last = status.lastSyncAt?.let { timeFormat.format(Date(it)) } ?: "—"
        statusCard.addView(SettingsUi.value(this, "最近同步：$last"))
        status.lastSummary?.takeIf { it.isNotBlank() }?.let { statusCard.addView(SettingsUi.value(this, it)) }
        status.lastError?.takeIf { it.isNotBlank() }?.let { statusCard.addView(SettingsUi.value(this, it)) }
        content.addView(SettingsUi.cardView(this, statusCard))

        // 服务器配置：只有「未配置」或「正在编辑」时才展开
        val configured = config.isConfigured
        val configCard = SettingsUi.card(this)
        configCard.addView(SettingsUi.heading(this, if (configured) "服务器（已配置）" else "服务器"))
        val (urlLayout, urlEdit) = SettingsUi.textField(this, "WebDAV 地址", config.baseUrl)
        val (userLayout, userEdit) = SettingsUi.textField(this, "用户名", config.username)
        val (passLayout, passEdit) = SettingsUi.textField(this, "密码", config.password, password = true)
        val (dirLayout, dirEdit) = SettingsUi.textField(this, "同步目录", config.syncDir)
        listOf(urlLayout, userLayout, passLayout, dirLayout).forEach { configCard.addView(it) }
        configCard.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "测试连接") { testSync(urlEdit, userEdit, passEdit, dirEdit) },
                    SettingsUi.button(this, if (profileSyncOnSave) "保存并登录" else "保存配置") {
                        saveSync(urlEdit, userEdit, passEdit, dirEdit, syncProfile = profileSyncOnSave)
                    },
                ),
            ),
        )
        configCard.addView(
            SettingsUi.button(this, "立即同步") { runSyncNow(urlEdit, userEdit, passEdit, dirEdit) },
        )
        configCard.addView(
            SettingsUi.switchRow(this, "自动同步（周期检查，非实时推送）", config.autoSync) { checked ->
                if (checked) {
                    saveSync(urlEdit, userEdit, passEdit, dirEdit, autoSync = true, toast = false)
                } else {
                    SyncEngineHolder.get(this).setAutoSync(false)
                    SyncScheduler.applyAutoSync(this, false)
                }
            },
        )
        configCard.addView(
            SettingsUi.switchRow(
                this,
                "允许未加密的 HTTP（仅限可信局域网）",
                config.allowCleartextHttp,
            ) { checked ->
                saveSync(
                    urlEdit,
                    userEdit,
                    passEdit,
                    dirEdit,
                    allowCleartextHttp = checked,
                    toast = false,
                )
            },
        )
        configCard.addView(
            SettingsUi.hint(
                this,
                "HTTP 未加密，仅建议用于可信局域网；公网 HTTP 地址会被直接拒绝。",
            ),
        )
        content.addView(SettingsUi.cardView(this, configCard))

        // 同步内容：用户单独勾选
        val contentCard = SettingsUi.card(this)
        contentCard.addView(SettingsUi.heading(this, "同步内容"))
        SyncContent.entries.forEach { item ->
            contentCard.addView(
                SettingsUi.switchRow(this, item.label, preferences.isEnabled(item)) { checked ->
                    preferences.setEnabled(item, checked)
                    SyncEngineHolder.get(this).saveConfig(SyncEngineHolder.get(this).loadConfig())
                },
            )
        }
        contentCard.addView(
            SettingsUi.hint(this, "只同步勾选的内容；头像随账号同步；账号密码、日志、调试信息永不同步。"),
        )
        content.addView(SettingsUi.cardView(this, contentCard))
    }

    // ---------------------------------------------------------------- 语音

    private fun buildVoice(content: LinearLayout) {
        val store = com.board.ime.voice.VoiceCredentialStore(this)
        val current = store.load()

        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "语音识别服务"))
        card.addView(SettingsUi.value(this, "服务：百度实时语音识别（WebSocket，边录边发）"))
        val (appIdLayout, appIdEdit) = SettingsUi.textField(this, "语音 AppID", current.appId)
        val (keyLayout, keyEdit) = SettingsUi.textField(this, "语音 API Key", current.apiKey, password = true)
        card.addView(appIdLayout)
        card.addView(keyLayout)
        card.addView(
            SettingsUi.hint(
                this,
                "在百度智能云「语音技术」应用查看 AppID 与 API Key；与翻译凭据不是同一套。" +
                    "凭据保存在 Android 加密存储中。",
            ),
        )
        card.addView(
            SettingsUi.button(this, "保存") {
                store.save(
                    com.board.ime.voice.VoiceCredentials(
                        appId = appIdEdit.text?.toString().orEmpty(),
                        apiKey = keyEdit.text?.toString().orEmpty(),
                    ),
                )
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                render()
            },
        )
        card.addView(
            SettingsUi.button(this, "清空凭据") {
                store.clear()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                render()
            },
        )
        card.addView(
            SettingsUi.value(
                this,
                if (current.isComplete) "当前状态：已配置" else "当前状态：未配置",
            ),
        )
        card.addView(
            SettingsUi.hint(
                this,
                "额度以百度控制台为准。",
            ),
        )
        if (!store.secureStorageAvailable) {
            card.addView(
                SettingsUi.hint(this, "注意：本机加密存储不可用，凭据只保留在本次会话内存中。"),
            )
        }
        content.addView(SettingsUi.cardView(this, card))

        val privacy = SettingsUi.card(this)
        privacy.addView(SettingsUi.heading(this, "隐私说明"))
        privacy.addView(
            SettingsUi.hint(
                this,
                "· 语音输入需要联网（WSS 加密连接）\n" +
                    "· 录音会边录边发给百度语音服务处理\n" +
                    "· Board 不在本地保存录音、识别文本或识别历史\n" +
                    "· 百度侧的数据处理与留存以其服务协议和隐私政策为准",
            ),
        )
        content.addView(SettingsUi.cardView(this, privacy))
    }

    // ---------------------------------------------------------------- 翻译

    private fun buildTranslation(content: LinearLayout) {
        val store = com.board.ime.translation.TranslationCredentialStore(this)
        val current = store.load()

        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "翻译服务"))
        card.addView(
            SettingsUi.value(
                this,
                "13 种目标语言：中文、English、日本語、한국어、Français、Deutsch、" +
                    "Español、Русский、Português、Italiano、العربية、ไทย、Tiếng Việt",
            ),
        )
        card.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("百度翻译", "OpenAI 格式接口"),
                if (current.kind == com.board.ime.translation.TranslationProviderKind.BAIDU) 0 else 1,
            ) { index ->
                val kind = if (index == 0) {
                    com.board.ime.translation.TranslationProviderKind.BAIDU
                } else {
                    com.board.ime.translation.TranslationProviderKind.OPENAI
                }
                if (kind != store.load().kind) {
                    store.saveProviderKind(kind)
                    render()
                }
            },
        )

        if (current.kind == com.board.ime.translation.TranslationProviderKind.BAIDU) {
            val (appIdLayout, appIdEdit) = SettingsUi.textField(this, "APPID（数字）", current.appId)
            val (secretLayout, secretEdit) = SettingsUi.textField(this, "密钥", current.secret, password = true)
            card.addView(appIdLayout)
            card.addView(secretLayout)
            card.addView(
                SettingsUi.hint(
                    this,
                    "在「百度翻译开放平台 → 开发者信息」查看 APPID（数字）与密钥；" +
                        "还需开通通用文本翻译服务，否则报 52003。",
                ),
            )
            card.addView(
                SettingsUi.button(this, "保存百度凭据") {
                    val appId = appIdEdit.text?.toString().orEmpty().trim()
                    store.save(
                        store.load().copy(
                            appId = appId,
                            secret = secretEdit.text?.toString().orEmpty(),
                        ),
                    )
                    // APPID 在百度翻译开放平台是一串数字；填成名称/密钥时几乎必然是填错了
                    if (appId.isNotEmpty() && appId.any { !it.isDigit() }) {
                        Toast.makeText(
                            this,
                            "已保存。但 APPID 通常是一串数字 —— 请确认这里填的是 APPID，不是密钥名称或 API Key",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    }
                    render()
                },
            )
        } else {
            val (baseLayout, baseEdit) = SettingsUi.textField(
                this,
                "接口地址（…/v1）",
                current.baseUrl,
            )
            val (keyLayout, keyEdit) = SettingsUi.textField(
                this,
                "API Key（本地 Ollama 可留空）",
                current.apiKey,
                password = true,
            )
            val (modelLayout, modelEdit) = SettingsUi.textField(this, "模型名", current.model)
            card.addView(baseLayout)
            card.addView(keyLayout)
            card.addView(modelLayout)
            card.addView(
                SettingsUi.hint(
                    this,
                    "兼容 OpenAI /chat/completions；远程地址须 HTTPS，本机 / 内网可用 HTTP。",
                ),
            )
            card.addView(
                SettingsUi.button(this, "保存接口配置") {
                    val baseUrl = baseEdit.text?.toString().orEmpty().trim()
                    val model = modelEdit.text?.toString().orEmpty().trim()
                    if (baseUrl.isNotEmpty() &&
                        com.board.ime.translation.OpenAiCompatibleProvider
                            .chatCompletionsUrl(baseUrl) == null
                    ) {
                        Toast.makeText(
                            this,
                            "接口地址需以 https:// 开头（本机 / 内网可用 http://）",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@button
                    }
                    if (baseUrl.isNotEmpty() && model.isEmpty()) {
                        Toast.makeText(this, "请填写模型名", Toast.LENGTH_SHORT).show()
                        return@button
                    }
                    store.save(
                        store.load().copy(
                            baseUrl = baseUrl,
                            apiKey = keyEdit.text?.toString().orEmpty(),
                            model = model,
                        ),
                    )
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    render()
                },
            )
        }

        card.addView(
            SettingsUi.button(this, "清空凭据") {
                store.clear()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                render()
            },
        )
        card.addView(
            SettingsUi.value(
                this,
                if (current.isConfigured) "当前状态：已配置" else "当前状态：未配置",
            ),
        )
        if (!store.secureStorageAvailable) {
            card.addView(
                SettingsUi.hint(this, "注意：本机加密存储不可用，凭据只保留在本次会话内存中。"),
            )
        }
        content.addView(SettingsUi.cardView(this, card))

        val modeCard = SettingsUi.card(this)
        modeCard.addView(SettingsUi.heading(this, "译模式（边打边译）"))
        modeCard.addView(
            SettingsUi.hint(
                this,
                "长按 Shift 开关「译」；停顿约 0.45 秒自动翻译当前行，译文出现在候选栏。" +
                    "工具栏「翻译」可打开整块翻译面板。",
            ),
        )
        content.addView(SettingsUi.cardView(this, modeCard))

        val offline = SettingsUi.card(this)
        offline.addView(SettingsUi.heading(this, "离线翻译（混元 HY-MT）"))
        offline.addView(
            SettingsUi.hint(
                this,
                "暂不提供离线翻译模型，译文均来自上面配置的在线服务。",
            ),
        )
        content.addView(SettingsUi.cardView(this, offline))

        val privacy = SettingsUi.card(this)
        privacy.addView(SettingsUi.heading(this, "隐私说明"))
        privacy.addView(
            SettingsUi.hint(
                this,
                "· 翻译需要联网，原文会发送给你配置的服务商（译模式停顿后自动发送当前行）\n" +
                    "· Board 不在本地保存原文、译文或历史；关闭译模式即停止自动发送\n" +
                    "· 服务商的数据处理与留存以其服务协议和隐私政策为准",
            ),
        )
        content.addView(SettingsUi.cardView(this, privacy))
    }

    private fun readConfig(
        url: EditText,
        user: EditText,
        pass: EditText,
        dir: EditText,
        autoSync: Boolean = SyncEngineHolder.get(this).loadConfig().autoSync,
        allowCleartextHttp: Boolean = SyncEngineHolder.get(this).loadConfig().allowCleartextHttp,
    ): WebDavConfig = WebDavConfig(
        baseUrl = url.text?.toString().orEmpty().trim(),
        username = user.text?.toString().orEmpty().trim(),
        password = pass.text?.toString().orEmpty(),
        syncDir = dir.text?.toString().orEmpty().trim().ifBlank { WebDavConfig.DEFAULT_DIR },
        autoSync = autoSync,
        allowCleartextHttp = allowCleartextHttp,
    )

    private fun saveSync(
        url: EditText,
        user: EditText,
        pass: EditText,
        dir: EditText,
        autoSync: Boolean = SyncEngineHolder.get(this).loadConfig().autoSync,
        allowCleartextHttp: Boolean = SyncEngineHolder.get(this).loadConfig().allowCleartextHttp,
        toast: Boolean = true,
        syncProfile: Boolean = false,
    ) {
        val config = readConfig(url, user, pass, dir, autoSync, allowCleartextHttp)
        if (!config.isConfigured) {
            Toast.makeText(this, "请先填写 WebDAV 地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (!config.hasValidScheme) {
            Toast.makeText(this, "地址要以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
            return
        }
        val engine = SyncEngineHolder.get(this)
        engine.saveConfig(config)
        SyncScheduler.applyAutoSync(this, config.autoSync)
        if (toast) Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        render()
        if (syncProfile) {
            // 登录后拉一次云端头像；失败不影响配置保存
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { engine.syncProfileOnly() }
                }
                render()
            }
        }
    }

    private fun testSync(url: EditText, user: EditText, pass: EditText, dir: EditText) {
        val config = readConfig(url, user, pass, dir)
        if (!config.isConfigured) {
            Toast.makeText(this, "请先填写 WebDAV 地址", Toast.LENGTH_SHORT).show()
            return
        }
        val engine = SyncEngineHolder.get(this)
        engine.saveConfig(config)
        lifecycleScope.launch {
            Toast.makeText(this@SettingsSectionActivity, "正在测试连接…", Toast.LENGTH_SHORT).show()
            val response = withContext(Dispatchers.IO) { engine.testConnection(config) }
            Toast.makeText(
                this@SettingsSectionActivity,
                if (response.isOk) "连接成功" else "连接失败：${response.message ?: response.error.name}",
                Toast.LENGTH_SHORT,
            ).show()
            render()
        }
    }

    private fun runSyncNow(url: EditText, user: EditText, pass: EditText, dir: EditText) {
        val config = readConfig(url, user, pass, dir)
        if (!config.isConfigured) {
            Toast.makeText(this, "请先填写 WebDAV 地址", Toast.LENGTH_SHORT).show()
            return
        }
        val engine = SyncEngineHolder.get(this)
        engine.saveConfig(config)
        syncTimedOut = false
        lifecycleScope.launch {
            Toast.makeText(this@SettingsSectionActivity, "正在同步…", Toast.LENGTH_SHORT).show()
            // 后台任务放在子 job：UI 到点就结束「同步中」，但任务本身不取消
            val job = launch {
                withContext(Dispatchers.IO) { runCatching { engine.syncNow(SyncTrigger.MANUAL) } }
            }
            val finished = withTimeoutOrNull(SYNC_UI_TIMEOUT_MS) { job.join() } != null
            if (!finished) {
                syncTimedOut = true
                SyncLog.event(
                    SyncEvent.NETWORK_ERROR,
                    "同步超过 ${SYNC_UI_TIMEOUT_MS / 1000}s，UI 先结束等待（后台任务继续）",
                )
                Toast.makeText(
                    this@SettingsSectionActivity,
                    "同步超时：UI 已结束等待，后台仍会继续",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            render()
        }
    }

    // ---------------------------------------------------------------- 隐私

    private fun buildPrivacy(content: LinearLayout) {
        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "敏感剪贴板"))
        card.addView(
            SettingsUi.switchRow(this, "验证码 / 密码不写入历史", preferences.skipSensitiveClipboard) {
                preferences.skipSensitiveClipboard = it
            },
        )
        content.addView(SettingsUi.cardView(this, card))

        val scope = SettingsUi.card(this)
        scope.addView(SettingsUi.heading(this, "同步数据范围"))
        scope.addView(
            SettingsUi.hint(
                this,
                "只上传「同步内容」中勾选的数据，不同步日志、密码与设备信息。",
            ),
        )
        SyncContent.entries.filter { preferences.isEnabled(it) }.forEach {
            scope.addView(SettingsUi.value(this, "· ${it.label}"))
        }
        content.addView(SettingsUi.cardView(this, scope))
    }

    // ---------------------------------------------------------------- 关于

    private fun buildAbout(content: LinearLayout) {
        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "输入法状态"))
        val enabled = isImeEnabled()
        card.addView(SettingsUi.statusRow(this, "Board", if (enabled) "已启用" else "未启用", enabled))
        card.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "启用 / 切换输入法") {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }
                    },
                    SettingsUi.button(this, "切换输入法") {
                        runCatching {
                            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                        }
                    },
                ),
            ),
        )
        content.addView(SettingsUi.cardView(this, card))

        val about = SettingsUi.card(this)
        about.addView(SettingsUi.heading(this, "关于 Board"))
        about.addView(SettingsUi.value(this, "版本 ${BuildConfig.VERSION_NAME}"))
        about.addView(
            SettingsUi.hint(
                this,
                "librime 中文输入（朙月拼音 · 简体）· 剪贴板 · SyncClipboard / WebDAV 同步。",
            ),
        )
        if (preferences.developerMode) {
            about.addView(
                SettingsUi.button(this, "日志详情") {
                    showLogs = true
                    render()
                },
            )
        }
        content.addView(SettingsUi.cardView(this, about))
    }

    // ---------------------------------------------------------------- 关于（介绍 / 更新）

    private fun buildAboutPage(content: LinearLayout) {
        val intro = SettingsUi.card(this)
        intro.addView(SettingsUi.heading(this, "项目介绍"))
        intro.addView(
            SettingsUi.value(
                this,
                "Board 是一款轻量的 Android 输入法：布局简洁、按键跟手，" +
                    "支持拼音、双拼与模糊音，提供多套可跟随系统深浅色的配色主题。\n" +
                    "输入内容与词库数据只保存在本机。",
            ),
        )
        intro.addView(SettingsUi.value(this, "版本 ${BuildConfig.VERSION_NAME}"))
        content.addView(SettingsUi.cardView(this, intro))

        val update = SettingsUi.card(this)
        update.addView(SettingsUi.heading(this, "更新"))
        update.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("GitHub", "gh-proxy"),
                if (preferences.updateDownloadSource == UpdateDownloadSource.GH_PROXY) 1 else 0,
            ) { index ->
                preferences.updateDownloadSource =
                    if (index == 1) UpdateDownloadSource.GH_PROXY else UpdateDownloadSource.GITHUB
            },
        )
        update.addView(
            SettingsUi.hint(this, "gh-proxy 经 Cloudflare 加速下载更新包；检查更新始终直连。"),
        )
        val status = SettingsUi.value(this, "").apply { visibility = View.GONE }
        val download = SettingsUi.primaryButton(this, "立即下载") { }.apply {
            visibility = View.GONE
        }
        update.addView(
            SettingsUi.button(this, "检查更新") { checkForUpdate(status, download) },
        )
        update.addView(status)
        update.addView(download)
        content.addView(SettingsUi.cardView(this, update))
    }

    /** 检查更新；确认有新版才露出「立即下载」 */
    private fun checkForUpdate(status: TextView, download: MaterialButton) {
        updateCheckJob?.cancel()
        status.visibility = View.VISIBLE
        status.text = "正在检查…"
        download.visibility = View.GONE
        updateCheckJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check() }
            result.fold(
                onSuccess = { info ->
                    if (info == null) {
                        status.text = "已是最新版本（${BuildConfig.VERSION_NAME}）"
                    } else {
                        status.text = buildString {
                            append("发现新版本 ${info.versionLabel}")
                            if (info.sizeBytes > 0L) append(" · ${formatUpdateSize(info.sizeBytes)}")
                            if (info.notes.isNotBlank()) append("\n").append(info.notes.take(600))
                        }
                        download.visibility = View.VISIBLE
                        download.setOnClickListener {
                            val opened = UpdateChecker.openDownload(
                                this@SettingsSectionActivity,
                                info,
                                preferences.updateDownloadSource,
                            )
                            if (!opened) {
                                Toast.makeText(
                                    this@SettingsSectionActivity,
                                    "没有可用的浏览器",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
                onFailure = { error ->
                    status.text = "检查失败：${error.message ?: error.javaClass.simpleName}"
                },
            )
        }
    }

    private fun formatUpdateSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---------------------------------------------------------------- 日志（开发者模式）

    private fun buildLogs(content: LinearLayout) {
        val card = SettingsUi.card(this)
        card.addView(SettingsUi.heading(this, "最近同步日志"))
        val lines = SyncLog.recent()
        val body = TextView(this).apply {
            // 单条日志截断：异常栈 / 表结构 dump 可能几千字，原样渲染会把日志页撑爆
            text = if (lines.isEmpty()) "（暂无）" else {
                lines.joinToString("\n") { line ->
                    if (line.length <= 300) line else line.take(300) + "…（已截断）"
                }
            }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(secondaryColor())
        }
        card.addView(body)
        card.addView(SettingsUi.hint(this, "日志只记录类型、哈希前缀与错误分类，不含正文。"))
        content.addView(SettingsUi.cardView(this, card))

        val learned = SettingsUi.card(this)
        learned.addView(SettingsUi.heading(this, "学习词同步自检"))
        val info = SettingsUi.value(this, "点击下面的按钮，把 librime 学习记录导出成同步模型看看")
        learned.addView(info)
        learned.addView(
            SettingsUi.button(this, "导出学习词（同步模型）") {
                val records = LearnedWordBridge.parseUserDictText(exportLearnedText())
                info.text = "共 ${records.size} 条（前 3 条：" +
                    records.take(3).joinToString { "${it.word}/${it.code.orEmpty()}×${it.frequency}" } + "）"
            },
        )
        content.addView(SettingsUi.cardView(this, learned))
    }

    private fun exportLearnedText(): String {
        val dir = RimeRuntime.userDataDir() ?: return ""
        val file = java.io.File(dir, "board_learned_debug.txt")
        return try {
            val count = RimeRuntime.exportUserDict(file)
            if (count > 0 && file.exists()) file.readText() else ""
        } catch (throwable: Throwable) {
            ""
        } finally {
            file.delete()
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 一条通道的显示文案：进行中 / 失败时才补细节（例如「同步中 37% · names.dict」） */
    private fun partText(part: SyncPart): String {
        val detail = part.detail?.takeIf { it.isNotBlank() } ?: return part.label
        return when (part.state) {
            SyncPartState.SYNCING, SyncPartState.FAILED -> "${part.label} · $detail"
            else -> part.label
        }
    }

    private fun isImeEnabled(): Boolean = ImeStatus.isBoardEnabled(this)

    private fun background(): Int = themeColor(android.R.attr.colorBackground)

    private fun secondaryColor(): Int =
        themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    /** 统一从主题属性取色（设置页不写死黑白）。 */
    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_SHOW_LOGS = "show_logs"

        /** UI 等待同步的最长时间：到点就结束「同步中」，后台任务不取消 */
        private const val SYNC_UI_TIMEOUT_MS = 180_000L

        /** 自定义颜色的预设色板（默认项是 Board 现有的那点蓝） */
        private val COLOR_PRESETS = listOf(
            "默认蓝" to 0xFF2F6BFF.toInt(),
            "深蓝" to 0xFF1E4FD8.toInt(),
            "青" to 0xFF00A3A3.toInt(),
            "绿" to 0xFF2E9B57.toInt(),
            "橙" to 0xFFE07A20.toInt(),
            "红" to 0xFFD8453C.toInt(),
            "紫" to 0xFF7B4BD8.toInt(),
            "白" to 0xFFFFFFFF.toInt(),
            "浅灰" to 0xFFF2F3F5.toInt(),
            "中灰" to 0xFFD3D7DC.toInt(),
            "深灰" to 0xFF3C4248.toInt(),
            "近黑" to 0xFF14161A.toInt(),
        )
    }
}
