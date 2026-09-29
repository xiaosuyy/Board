package com.board.ime.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
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
        val boardDatabase = BoardDatabase.get(this)
        this.boardDatabase = boardDatabase
        dictionaryRepository = DictionaryRepository(
            boardDatabase.dictionaryDao(),
            this,
            boardDatabase.dictionarySourceDao(),
            boardDatabase,
        )
        clipboardRepository = ClipboardRepository(boardDatabase.clipboardDao())
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
                SettingsSection.APPEARANCE -> buildAppearance(content)
                SettingsSection.KEYBOARD -> buildKeyboard(content)
                SettingsSection.CLIPBOARD -> buildClipboard(content)
                SettingsSection.DICTIONARY -> buildDictionary(content)
                SettingsSection.SYNC -> buildSync(content)
                SettingsSection.PRIVACY -> buildPrivacy(content)
                SettingsSection.ABOUT -> buildAbout(content)
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
        val theme = SettingsUi.card(this)
        theme.addView(SettingsUi.heading(this, "键盘主题"))
        theme.addView(
            SettingsUi.toggleGroup(
                this,
                listOf("跟随系统", "浅色", "深色"),
                preferences.themeMode.ordinal,
            ) { index ->
                preferences.themeMode = ThemeMode.entries[index]
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
            },
        )
        size.addView(SettingsUi.hint(this, "拼音字号会按同一档位等比调整；面板高度不变。"))
        content.addView(SettingsUi.cardView(this, size))
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
                    SettingsUi.button(this, "清空全部历史") { confirmClearClipboard() },
                ),
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
            val out = SettingsUi.value(this, "10KB ~ 10MB 逐档：抓取 / 入库 / 全文 / 搜索 / 系统剪贴板")
            stress.addView(out)
            stress.addView(
                SettingsUi.button(this, "运行大文本压力测试（10KB~10MB）") {
                    runClipboardStress(out)
                },
            )
            content.addView(SettingsUi.cardView(this, stress))
        }

        lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { clipboardRepository.counts().total }
            counts.text = "共 $total 条"
        }
    }

    /** 开发者模式：逐档自测剪贴板大文本（真抓取 / 真入库 / 真写系统剪贴板） */
    private fun runClipboardStress(out: TextView) {
        out.text = "正在运行（10MB 档会比较慢）…"
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching {
                    ClipboardStressTest.run(
                        boardDatabase,
                        getSystemService(android.content.ClipboardManager::class.java),
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
            out.text = rows.joinToString("\n") { row ->
                val clip = when {
                    row.clipboardOk -> "系统剪贴板 ✓"
                    else -> "系统剪贴板 ✗ ${row.clipboardError.orEmpty()}"
                }
                val verdict = when {
                    row.error != null -> "✗ ${row.error}"
                    else -> "✓ 入库/全文/搜索通过"
                }
                "${row.label}（${row.bytes} B）: 哈希 ${row.hashMs}ms 抓取 ${row.captureMs}ms " +
                    "存 ${row.storedChars} 字 摘要 ${row.previewChars} 字\n  $verdict\n  $clip"
            }
            Toast.makeText(this@SettingsSectionActivity, "压力测试完成，结果见上方", Toast.LENGTH_SHORT).show()
        }
    }

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
    }
}
