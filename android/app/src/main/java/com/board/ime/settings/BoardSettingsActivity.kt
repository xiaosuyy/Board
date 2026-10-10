package com.board.ime.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.board.ime.BuildConfig
import com.board.ime.R
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.database.BoardDatabase
import com.board.ime.dictionary.DictionaryRepository
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.ime.BoardInputMethodService
import com.board.ime.sync.SyncEngineHolder
import com.board.ime.sync.SyncStatusKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置首页。
 *
 * 只做三件事：
 * 1. 最上方显示**最重要的状态**（输入法 / Rime / 同步 / 数据库）—— 不显示日志、HTTP 状态码、堆栈
 * 2. 列出 8 个一级分类入口，详细设置进二级页面
 * 3. 最下方是版本号；**连续点 5 次**才开启开发者模式并露出「日志详情」入口
 */
class BoardSettingsActivity : AppCompatActivity() {

    private lateinit var preferences: BoardPreferences
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var clipboardRepository: ClipboardRepository

    private val statusRows = HashMap<String, LinearLayout>()
    private var tapCount = 0
    private var lastTapAt = 0L
    private var logEntryRow: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BoardPreferences(this)
        dictionaryRepository = DictionaryRepository(BoardDatabase.get(this).dictionaryDao(), this)
        // B1（D1）：与输入法服务 / SyncEngine 共用**同一个** repository 实例
        clipboardRepository = com.board.ime.sync.SyncEngineHolder.clipboardRepository(this)

        setContentView(buildHome())
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    // ---------------------------------------------------------------- 首页

    private fun buildHome(): LinearLayout {
        statusRows.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background())
        }
        root.addView(
            TextView(this).apply {
                text = "Board"
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimensionPixelSize(R.dimen.app_text_display).toFloat(),
                )
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(settingsPrimary())
                setPadding(dp(20f), dp(20f), dp(20f), 0)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "输入法设置"
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimensionPixelSize(R.dimen.app_text_callout).toFloat(),
                )
                setTextColor(settingsSecondary())
                setPadding(dp(20f), dp(2f), dp(20f), dp(4f))
            },
        )

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(SettingsUi.screen(this@BoardSettingsActivity))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val content = scroll.getChildAt(0) as LinearLayout

        // ---- 重要状态（只显示状态，不显示账号 / 服务器 / 日志）----
        val statusCard = SettingsUi.card(this)
        statusCard.addView(SettingsUi.heading(this, "状态"))
        listOf("Board", "Rime", "同步", "数据库").forEach { name ->
            val row = SettingsUi.statusRow(this, name, "检查中…", ok = true)
            statusRows[name] = row
            statusCard.addView(row)
        }
        statusCard.setOnClickListener { showInputMethodPicker() }
        content.addView(SettingsUi.cardView(this, statusCard))

        // ---- 八个一级分类 ----
        val menu = SettingsUi.card(this)
        SettingsSection.entries.forEachIndexed { index, section ->
            if (index > 0) menu.addView(SettingsUi.divider(this))
            menu.addView(
                SettingsUi.entryRow(this, section.icon, section.title, section.summary) {
                    openSection(section)
                },
            )
        }
        content.addView(SettingsUi.cardView(this, menu))

        // ---- 开发者模式才出现的日志入口 ----
        if (preferences.developerMode) {
            val dev = SettingsUi.card(this)
            dev.addView(SettingsUi.heading(this, "开发者"))
            val row = SettingsUi.entryRow(
                this,
                R.drawable.ic_board_search,
                "日志详情",
                "同步与输入法运行日志（不含剪贴板正文）",
            ) {
                openSection(SettingsSection.ADVANCED, showLogs = true)
            }
            logEntryRow = row
            dev.addView(row)
            content.addView(SettingsUi.cardView(this, dev))
        }

        // ---- 版本号：连续点 5 次开开发者模式 ----
        val footer = SettingsUi.card(this)
        footer.addView(
            TextView(this).apply {
                text = "Board ${BuildConfig.VERSION_NAME}"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(settingsSecondary())
                setPadding(0, dp(10f), 0, dp(10f))
                isClickable = true
                // 铺满整行，方便连续点击（宽度不够会点不中）
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener { onVersionTapped() }
            },
        )
        content.addView(footer)

        if (preferences.developerMode) {
            content.addView(
                SettingsUi.value(this, "开发者模式已开启"),
            )
        }
        SettingsUi.applySystemBarInset(root)
        return root
    }

    private fun onVersionTapped() {
        val now = System.currentTimeMillis()
        tapCount = if (now - lastTapAt > TAP_WINDOW_MS) 1 else tapCount + 1
        lastTapAt = now
        if (tapCount >= TAP_TARGET) {
            tapCount = 0
            val enabled = !preferences.developerMode
            preferences.developerMode = enabled
            Toast.makeText(
                this,
                if (enabled) "已开启开发者模式：首页出现「日志详情」" else "已关闭开发者模式",
                Toast.LENGTH_SHORT,
            ).show()
            // 重建首页（入口显示 / 隐藏）
            setContentView(buildHome())
            refreshStatuses()
        }
    }

    private fun openSection(section: SettingsSection, showLogs: Boolean = false) {
        startActivity(
            Intent(this, SettingsSectionActivity::class.java)
                .putExtra(SettingsSectionActivity.EXTRA_SECTION, section.name)
                .putExtra(SettingsSectionActivity.EXTRA_SHOW_LOGS, showLogs),
        )
    }

    private fun showInputMethodPicker() {
        runCatching {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    // ---------------------------------------------------------------- 状态

    private fun refreshStatuses() {
        val enabled = isImeEnabled()
        setStatus("Board", if (enabled) "输入法正常运行" else "未启用", enabled)

        val rimeReady = runCatching { RimeRuntime.isReady }.getOrDefault(false)
        setStatus(
            "Rime",
            runCatching {
                when {
                    !RimeRuntime.isNativeAvailable -> "不可用"
                    rimeReady -> RimeRuntime.currentSchema().ifBlank { RimeRuntime.DEFAULT_SCHEMA }
                    else -> "未初始化（打开键盘后自动初始化）"
                }
            }.getOrDefault("不可用"),
            rimeReady,
        )

        // 同步引擎的构造包含加密存储与数据库，任何异常都退化成「未配置」
        val syncKind = runCatching { SyncEngineHolder.get(this).status.value.kind }
            .getOrDefault(SyncStatusKind.NOT_CONFIGURED)
        setStatus(
            "同步",
            when (syncKind) {
                SyncStatusKind.NOT_CONFIGURED -> "未配置"
                SyncStatusKind.CONNECTED -> "已连接"
                SyncStatusKind.SYNCING -> "同步中…"
                SyncStatusKind.SUCCESS -> "已连接"
                SyncStatusKind.FAILED -> "同步失败"
            },
            syncKind != SyncStatusKind.NOT_CONFIGURED && syncKind != SyncStatusKind.FAILED,
        )

        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { dictionaryRepository.countAll() to clipboardRepository.counts().total }
                    .getOrDefault(0 to 0)
            }
            runCatching {
                setStatus("数据库", "正常 · 词条 ${counts.first} · 剪贴板 ${counts.second}", ok = true)
            }
        }
    }

    private fun setStatus(name: String, value: String, ok: Boolean) {
        val row = statusRows[name] ?: return
        row.removeAllViews()
        val rebuilt = SettingsUi.statusRow(this, name, value, ok)
        while (rebuilt.childCount > 0) {
            val child = rebuilt.getChildAt(0)
            rebuilt.removeViewAt(0)
            row.addView(child)
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

    private fun settingsPrimary(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private fun settingsSecondary(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAP_TARGET = 5
        const val TAP_WINDOW_MS = 1_500L
    }
}
