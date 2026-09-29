package com.board.ime.settings

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
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.ime.BoardInputMethodService
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        clipboardRepository = ClipboardRepository(
            boardDatabase.clipboardDao(),
            contentStore = ClipboardRepository.contentStoreFor(this),
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        setContentView(buildScreen())
    }

    private fun buildScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background())
        }

        // 标题栏：返回 + 分类名（键盘区域完全不受影响）
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(8f), dp(16f), dp(8f))
                addView(
                    TextView(this@SettingsSectionActivity).apply {
                        text = "‹"
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setPadding(dp(12f), 0, dp(12f), 0)
                        isClickable = true
                        setOnClickListener { finish() }
                    },
                )
                addView(
                    TextView(this@SettingsSectionActivity).apply {
                        text = if (showLogs) "日志详情" else section.title
                        textSize = 19f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    },
                )
            },
        )

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(SettingsUi.screen(this@SettingsSectionActivity))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

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
                SettingsSection.SYNC -> buildSync(content)
                SettingsSection.ADVANCED -> buildAdvanced(content)
            }
        }
        SettingsUi.applySystemBarInset(root)
        return root
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
        card.addView(SettingsUi.hint(this, "切换后立即生效，键盘上的空格键也会跟着变。"))
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
        engine.addView(SettingsUi.hint(this, "Rime 提供真正的拼音与用户学习；占位引擎只用于排查问题。"))
        content.addView(SettingsUi.cardView(this, engine))
    }

    // ---------------------------------------------------------------- 外观

    private fun buildAppearance(content: LinearLayout) {
        val appearance = settingsRepository.appearance()
        val resolvedTheme = KeyboardTheme.from(this, preferences.themeMode, preferences.candidateTextSize)

        val theme = SettingsUi.card(this)
        theme.addView(SettingsUi.heading(this, "主题"))
        theme.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("跟随系统", "浅色", "深色"),
                preferences.themeMode.ordinal,
            ) { index ->
                preferences.themeMode = ThemeMode.entries[index]
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, theme))

        val size = SettingsUi.card(this)
        size.addView(SettingsUi.heading(this, "候选词字号"))
        size.addView(
            SettingsUi.toggleGroup(
                this,
                CandidateTextSize.entries.map { it.label },
                preferences.candidateTextSize.ordinal,
            ) { index ->
                preferences.candidateTextSize = CandidateTextSize.entries[index]
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, size))

        val colors = SettingsUi.card(this)
        colors.addView(SettingsUi.heading(this, "颜色"))
        colors.addView(SettingsUi.hint(this, "点一行改一项；不设置就沿用默认（白 / 黑 / 灰 + 少量蓝）。"))
        AppearanceColor.entries.forEach { target ->
            colors.addView(colorRow(target, appearance.color(target), resolvedTheme))
        }
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
        radii.addView(SettingsUi.hint(this, "每项都有上下限，拖到头也不会出现畸形 UI。"))
        AppearanceRadius.entries.forEach { target ->
            radii.addView(
                SettingsUi.sliderRow(
                    this,
                    target.label,
                    appearance.radiusDp(target, target.defaultDp),
                    target.minDp,
                    target.maxDp,
                    "dp",
                ) { value -> settingsRepository.setRadius(target, value) },
            )
        }
        content.addView(SettingsUi.cardView(this, radii))
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
        buildPrivacy(content)
        buildAbout(content)
    }

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

        // ---- 键盘尺寸：竖屏 / 横屏 / 悬浮键盘各自一套参数 ----
        val sizeCard = SettingsUi.card(this)
        sizeCard.addView(SettingsUi.heading(this, "键盘尺寸"))
        val scopeGroup = SettingsUi.toggleGroup(
            this,
            KeyboardScope.entries.map { it.label },
            geometryScope.ordinal,
        ) { index ->
            geometryScope = KeyboardScope.entries[index]
            render()
        }
        sizeCard.addView(scopeGroup)
        sizeCard.addView(SettingsUi.hint(this, "竖屏 / 横屏 / 悬浮键盘分别保存，横屏默认更紧凑。"))
        content.addView(SettingsUi.cardView(this, sizeCard))

        renderGeometryControls(content, geometryScope)

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
        content.addView(SettingsUi.cardView(this, behaviour))

        val tryCard = SettingsUi.card(this)
        tryCard.addView(SettingsUi.heading(this, "试用"))
        val field = EditText(this).apply {
            hint = "在这里试打一段文字…"
            setSingleLine(false)
            minLines = 2
            gravity = Gravity.TOP
        }
        // 上屏测试按钮放在输入框**上面**：点输入框后键盘弹起也不会挡住它
        val imeOut = SettingsUi.value(this, "上屏测试：点一下下面的输入框，再点这个按钮")
        if (preferences.developerMode) {
            tryCard.addView(imeOut)
            tryCard.addView(
                SettingsUi.button(this, "测试上屏（10KB~2MB，走 Board 自己的 IME）") {
                    runImeCommitTest(field, imeOut)
                },
            )
        }
        tryCard.addView(field)
        content.addView(SettingsUi.cardView(this, tryCard))
    }

    /**
     * 当前作用域的键盘尺寸控件。
     *
     * **拖动即生效**：每次都写进 [SettingsRepository]，输入法服务订阅的是同一个
     * SharedPreferences 文件，收到变更后只重算尺寸 / 重绘键盘，
     * 不重启输入法、不重新部署 Rime、不动数据库。
     */
    private fun renderGeometryControls(content: LinearLayout, scope: KeyboardScope) {
        val geometry = settingsRepository.geometry(scope)

        val heightCard = SettingsUi.card(this)
        heightCard.addView(SettingsUi.heading(this, "键盘高度（${scope.label}）"))
        heightCard.addView(
            SettingsUi.toggleGroup(
                this,
                KeyboardHeightLevel.entries.map { it.label },
                geometry.level.ordinal,
            ) { index ->
                val level = KeyboardHeightLevel.entries[index]
                settingsRepository.updateGeometry(scope) { it.copy(level = level) }
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, heightCard))

        val fine = SettingsUi.card(this)
        fine.addView(SettingsUi.heading(this, "精细调整（${scope.label}）"))

        fine.addView(
            SettingsUi.sliderRow(
                this,
                "整体高度",
                value = geometry.customHeightFactor,
                min = KeyboardGeometry.MIN_CUSTOM_FACTOR,
                max = KeyboardGeometry.MAX_CUSTOM_FACTOR,
                unit = "x",
            ) { value ->
                // 拖「整体高度」自动切到自定义档，避免出现「档位是大、却拖了标准值」的困惑
                settingsRepository.updateGeometry(scope) {
                    it.copy(level = KeyboardHeightLevel.CUSTOM, customHeightFactor = value)
                }
            },
        )

        val gapDefault = 6f
        fine.addView(
            SettingsUi.sliderRow(
                this,
                "按键间距",
                value = if (geometry.hasCustomGap) geometry.keyGapDp else gapDefault,
                min = KeyboardGeometry.MIN_GAP_DP,
                max = KeyboardGeometry.MAX_GAP_DP,
                unit = "dp",
            ) { value ->
                settingsRepository.updateGeometry(scope) { it.copy(keyGapDp = value) }
            },
        )

        val radiusDefault = 10f
        fine.addView(
            SettingsUi.sliderRow(
                this,
                "按键圆角",
                value = if (geometry.hasCustomRadius) geometry.keyCornerRadiusDp else radiusDefault,
                min = 0f,
                max = 24f,
                unit = "dp",
            ) { value ->
                settingsRepository.updateGeometry(scope) { it.copy(keyCornerRadiusDp = value) }
            },
        )

        fine.addView(
            SettingsUi.sliderRow(
                this,
                "功能键宽度",
                value = geometry.functionKeyWidthRatio,
                min = KeyboardGeometry.MIN_FN_RATIO,
                max = KeyboardGeometry.MAX_FN_RATIO,
                unit = "x",
            ) { value ->
                settingsRepository.updateGeometry(scope) { it.copy(functionKeyWidthRatio = value) }
            },
        )

        fine.addView(
            SettingsUi.button(this, "恢复本作用域默认") {
                settingsRepository.resetGeometry(scope)
                Toast.makeText(this, "${scope.label}已恢复默认", Toast.LENGTH_SHORT).show()
                render()
            },
        )
        content.addView(SettingsUi.cardView(this, fine))
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
                "超过阈值的文本会存成文件（不占数据库行），列表与搜索不会因此变慢；\n" +
                    "其它 App 也可直接「分享给 Board」。",
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
                    "超过阈值的文本会存到 Board 私有目录的文件里；列表查询永远不读完整内容。\n" +
                        "系统剪贴板写入被拒是 Android 的 Binder 限制，不是 Board 的限制。",
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
        content.addView(SettingsUi.cardView(this, learned))

        // 导入词库（大型文件）：条目 / 大小 / 是否已接入 Rime
        val sourcesCard = SettingsUi.card(this)
        sourcesCard.addView(SettingsUi.heading(this, "导入词库（大型文件）"))
        val sourcesValue = SettingsUi.value(this, "读取中…")
        sourcesCard.addView(sourcesValue)
        val deployButton = SettingsUi.button(this, "部署到 Rime") {
            deployImportedSources(sourcesValue)
        }
        sourcesCard.addView(deployButton)
        sourcesCard.addView(
            SettingsUi.hint(
                this,
                "大型词库以文件形式保存，不占用户词表；「已接入 Rime」表示已生成词典配置并部署成功。",
            ),
        )
        content.addView(SettingsUi.cardView(this, sourcesCard))

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                // 首次打开设置也顺手完成「历史大词库 → 数据源」迁移（幂等、不丢数据）
                runCatching { dictionaryRepository.migrateLegacyImportedEntries() }
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

    // ---------------------------------------------------------------- 同步

    private fun buildSync(content: LinearLayout) {
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
            status.clipboard.label,
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
                clipboardValue.text = partText(live.clipboard)
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
        if (configured) {
            configCard.addView(SettingsUi.hint(this, "账号信息只在配置时展开；密码永远不回显明文以外的内容。"))
        }
        configCard.addView(
            SettingsUi.buttonRow(
                this,
                listOf(
                    SettingsUi.button(this, "测试连接") { testSync(urlEdit, userEdit, passEdit, dirEdit) },
                    SettingsUi.button(this, "保存配置") { saveSync(urlEdit, userEdit, passEdit, dirEdit) },
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
            SettingsUi.hint(this, "只有勾选的内容进入同步；账号密码、日志、调试信息永远不同步。"),
        )
        content.addView(SettingsUi.cardView(this, contentCard))
    }

    private fun readConfig(
        url: EditText,
        user: EditText,
        pass: EditText,
        dir: EditText,
        autoSync: Boolean = SyncEngineHolder.get(this).loadConfig().autoSync,
    ): WebDavConfig = WebDavConfig(
        baseUrl = url.text?.toString().orEmpty().trim(),
        username = user.text?.toString().orEmpty().trim(),
        password = pass.text?.toString().orEmpty(),
        syncDir = dir.text?.toString().orEmpty().trim().ifBlank { WebDavConfig.DEFAULT_DIR },
        autoSync = autoSync,
    )

    private fun saveSync(
        url: EditText,
        user: EditText,
        pass: EditText,
        dir: EditText,
        autoSync: Boolean = SyncEngineHolder.get(this).loadConfig().autoSync,
        toast: Boolean = true,
    ) {
        val config = readConfig(url, user, pass, dir, autoSync)
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
                "只有「同步 → 同步内容」里勾选的数据会被上传：" +
                    "不会同步输入法日志、调试信息、账号密码，也不会同步无关设备信息。",
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
                "Android 端核心 + librime 中文输入（朙月拼音 · 简体）+ 剪贴板 + SyncClipboard / WebDAV 文本同步。",
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
        card.addView(SettingsUi.hint(this, "日志只记录类型、哈希前 8 位、字节数与错误分类，不含剪贴板正文。"))
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

    private fun background(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private fun secondaryColor(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, value, true)
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
        private const val SYNC_UI_TIMEOUT_MS = 45_000L

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
