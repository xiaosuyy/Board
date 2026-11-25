package com.board.ime.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.board.ime.engine.Candidate
import com.board.ime.engine.InputMode
import com.board.ime.keyboard.KeyboardLayoutCalculator
import com.board.ime.keyboard.KeyboardLayoutMetrics
import com.board.ime.keyboard.KeyboardLayouts
import com.board.ime.ui.KeyboardTheme
import com.board.ime.ui.KeyboardView
import com.board.ime.ui.panel.CandidateBar
import com.board.ime.ui.panel.ToolId
import com.board.ime.ui.panel.ToolbarView
import kotlin.math.roundToInt

/**
 * 设置页底部的键盘实时预览。
 *
 * 结构**与真实键盘一致**：一块固定高度的顶部面板（工具栏 / 候选栏二选一，
 * 与 TopPanelController 相同）→ 分隔线 → 字母键盘。
 * 面板里用的是真实的 [ToolbarView] / [CandidateBar]，不是另画的示意条；
 * 所有颜色、圆角、字号、键高 / 间距调整都会立刻反映到这里。
 *
 * 尺寸走与输入法服务**同一条计算链路**（[KeyboardLayoutCalculator]）：
 * 竖屏预览按真实屏幕宽度 1:1 渲染，键高、底部留白、功能键宽度都与真键盘相同，
 * 不是缩小后的小卡片。横屏 / 悬浮作用域超出竖屏宽度时才按比例缩小塞进设置页。
 *
 * 点一下顶部面板可在「待机工具栏」和「输入候选」之间切换（两者在真实键盘里互斥）。
 */
class KeyboardPreviewView(context: Context) : FrameLayout(context) {

    /** 背后示例图：模拟「键盘背后的 App 内容」，毛玻璃强度决定它有多糊 */
    private val backdrop = BackdropView(context)
    /** 键盘内容本体（顶部面板 + 分隔线 + 字母键盘），叠在 backdrop 之上 */
    private val content = LinearLayout(context)
    private val panelHost = FrameLayout(context)
    private val toolbar = ToolbarView(context)
    private val candidateBar = CandidateBar(context)
    private val divider = View(context)
    private val keyboard = KeyboardView(context)

    /** true = 显示输入中的候选栏；false = 显示待机工具栏（真实键盘的默认状态） */
    private var showCandidates = false

    /** 预览候选是否带英文释义（跟随设置页开关） */
    private var sampleGloss = false

    /** 工具栏图标：跟随用户真实的自定义工具栏 */
    private var tools: List<ToolId> = SAMPLE_TOOLS

    /** 是否已经应用过主题（未应用时不能直接 render，候选栏内部还是默认主题） */
    private var themed = false

    init {
        keyboard.keyboardLayout = KeyboardLayouts.letters
        keyboard.keyboardMode = InputMode.CHINESE
        // 真实键盘待机时 Shift 不高亮，预览也不高亮，避免「预览里有、真键盘没有」
        keyboard.setShiftState(false, false)
        // 预览不参与输入，也不拦截触摸：让手势交给外层设置页 ScrollView 滚动
        keyboard.touchEnabled = false

        content.orientation = LinearLayout.VERTICAL
        // 子 View 的背景是不透明矩形，不裁剪的话外层圆角会被完全盖住，「键盘圆角」拖了没反应
        content.clipToOutline = true

        panelHost.addView(toolbar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panelHost.addView(candidateBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 透明覆盖层：只负责「点一下切换面板状态」，预览本身不参与真实输入
        panelHost.addView(
            View(context).apply {
                isClickable = true
                setOnClickListener {
                    showCandidates = !showCandidates
                    applyPanelState()
                }
            },
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        content.addView(panelHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)))
        content.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)))
        content.addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 先垫背景示例图、再叠键盘本体：键盘底色半透明时透出它，模糊强度决定它多糊
        addView(backdrop, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // outline 依赖实际宽高，尺寸变化后要重算，否则裁剪圆角还是旧的
        content.invalidateOutline()
    }

    fun applyTheme(
        theme: KeyboardTheme,
        geometry: KeyboardGeometry = KeyboardGeometry.DEFAULT,
        appearance: AppearanceConfig = AppearanceConfig.DEFAULT,
        floating: Boolean = false,
        keyboardLift: Boolean = true,
        screenWidthPx: Int = resources.displayMetrics.widthPixels,
        screenHeightPx: Int = resources.displayMetrics.heightPixels,
    ) {
        val radius = theme.radiusLarge
        if (floating) {
            // 悬浮键盘：四角都圆（与 BoardInputMethodService.floatingCard 一致）
            content.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(theme.keyboardBackground)
            }
            content.outlineProvider = ViewOutlineProvider.BACKGROUND
        } else {
            // 停靠键盘：只圆上面两角，下面贴边直角（与真实键盘的模拟方式一致）
            content.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(theme.keyboardBackground)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            content.outlineProvider = object : ViewOutlineProvider() {
                private val path = android.graphics.Path()
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    val r = radius.coerceAtMost(minOf(v.width, v.height) / 2f)
                    path.reset()
                    path.addRoundRect(
                        0f, 0f, v.width.toFloat(), v.height.toFloat(),
                        floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
                        android.graphics.Path.Direction.CW,
                    )
                    outline.setConvexPath(path)
                }
            }
        }
        content.clipToOutline = true
        // 换了背景 drawable 后必须重新计算 outline，否则 clipToOutline 还用旧圆角
        content.invalidateOutline()

        val panelParams = panelHost.layoutParams as LinearLayout.LayoutParams
        panelParams.height = theme.topPanelHeight
        panelHost.layoutParams = panelParams
        val dividerParams = divider.layoutParams as LinearLayout.LayoutParams
        dividerParams.height = theme.dividerHeight.coerceAtLeast(1)
        divider.layoutParams = dividerParams
        divider.setBackgroundColor(theme.divider)

        toolbar.applyTheme(theme)
        toolbar.setTools(tools)
        candidateBar.applyTheme(theme)
        candidateBar.render(sampleCandidates(), InputMode.CHINESE, "ni hao", null)

        keyboard.applyTheme(theme)
        keyboard.applyMetrics(metricsFor(geometry, appearance, floating, keyboardLift, screenWidthPx, screenHeightPx))

        // 毛玻璃：对背后示例图应用模糊，强度跟着「键盘背景毛玻璃」滑块走
        applyBackdropBlur(appearance.keyboardBlurStrength)

        themed = true
        applyPanelState()
        requestLayout()
        invalidate()
    }

    /** 对背后示例图应用模糊（Android 12+）；低版本降级为不模糊、只透出半透明底色 */
    private fun applyBackdropBlur(strength: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(null)
            return
        }
        val maxRadius = MAX_BLUR_RADIUS_DP * resources.displayMetrics.density
        val radius = strength.coerceIn(0f, 1f) * maxRadius
        backdrop.setRenderEffect(
            if (radius <= 0f) {
                null
            } else {
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /** 工具栏图标与真实键盘保持一致（读取用户自己的工具栏配置） */
    fun setTools(tools: List<ToolId>) {
        if (this.tools == tools) return
        this.tools = tools
        if (!themed) return
        toolbar.setTools(tools)
    }

    /** 切到候选栏（调整候选字号 / 释义 / 颜色时让预览直接展示对应状态） */
    fun showCandidates(show: Boolean) {
        if (showCandidates == show) return
        showCandidates = show
        applyPanelState()
    }

    /** 候选英文释义开关：预览里的示例候选跟着变（对应真实候选栏的两行模式） */
    fun setSampleEnglishGloss(enabled: Boolean) {
        if (sampleGloss == enabled) return
        sampleGloss = enabled
        if (!themed) return
        candidateBar.render(sampleCandidates(), InputMode.CHINESE, "ni hao", null)
    }

    private fun sampleCandidates(): List<Candidate> = listOf(
        Candidate("你好", englishGloss = if (sampleGloss) "hello" else null),
        Candidate("尼好"),
        Candidate("拟好", englishGloss = if (sampleGloss) "fit" else null),
        Candidate("你号"),
        Candidate("尼"),
        Candidate("泥"),
    )

    private fun applyPanelState() {
        toolbar.visibility = if (showCandidates) GONE else VISIBLE
        candidateBar.visibility = if (showCandidates) VISIBLE else GONE
    }

    /**
     * 与 [com.board.ime.ime.BoardInputMethodService.applyLayoutMetrics] 完全同一条链路：
     * 设计基准 → 几何参数 → 屏幕约束，算出键高 / 留白 / 间距 / 圆角。
     * 这样预览里的底部留白、行距、功能键宽度和真键盘一致，不是另算一套。
     */
    private fun metricsFor(
        geometry: KeyboardGeometry,
        appearance: AppearanceConfig,
        floating: Boolean,
        keyboardLift: Boolean,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): KeyboardLayoutMetrics {
        val density = resources.displayMetrics.density
        val resolved = KeyboardLayoutCalculator.metrics(
            config = KeyboardLayoutCalculator.configFor(context, floating, geometry, keyboardLift),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            density = density,
            rowCount = keyboard.keyboardLayout.rowCount,
        )
        val keyRadiusPx = appearance.radii[AppearanceRadius.KEY]?.let { radiusDp ->
            radiusDp.coerceIn(AppearanceRadius.KEY.minDp, AppearanceRadius.KEY.maxDp) * density
        }
        return if (keyRadiusPx != null) resolved.copy(cornerRadiusPx = keyRadiusPx) else resolved
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        /** 兜底工具栏：只在调用方没注入真实工具栏配置时使用 */
        private val SAMPLE_TOOLS = listOf(ToolId.EMOJI, ToolId.CLIPBOARD, ToolId.VOICE, ToolId.TRANSLATE)

        /** 预览背后示例图的最大模糊半径（dp），与输入法服务保持一致 */
        private const val MAX_BLUR_RADIUS_DP = 25f

        /**
         * 生成缩小密度的 Context：dpi 乘以 [scale]，dp / sp 资源全部等比缩小。
         *
         * 只有横屏 / 悬浮这类「真实宽度超过竖屏」的预览才需要；竖屏预览直接用原 Context，
         * 1:1 渲染。不能只缩放几个尺寸字段——候选栏私有尺寸、文字测量、圆角都依赖 density。
         */
        fun scaledContext(base: Context, scale: Float): Context {
            val config = android.content.res.Configuration(base.resources.configuration)
            val metrics = base.resources.displayMetrics
            config.densityDpi = (metrics.densityDpi * scale).roundToInt()
            return base.createConfigurationContext(config)
        }
    }
}

/**
 * 预览专用的「背后 App 内容」示例：多彩渐变 + 色块 + 文字行。
 * 模糊前能看清文字与色块，模糊后糊成一片 —— 拖动毛玻璃强度时对比非常直观。
 */
private class BackdropView(context: android.content.Context) : android.view.View(context) {

    private val backgroundPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val blockPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        backgroundPaint.shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                0xFF6FA8DC.toInt(), 0xFF93C5FD.toInt(), 0xFFF6A6B2.toInt(),
                0xFFFFD3A5.toInt(), 0xFF9BE8C8.toInt(),
            ),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        backgroundPaint.shader = null

        // 模拟 App 里的卡片（白色圆角矩形）
        blockPaint.color = 0xE6FFFFFF.toInt()
        val unit = height / 5f
        for (row in 0 until 4) {
            val top = row * unit + unit * 0.22f
            val blockHeight = unit * 0.55f
            canvas.drawRoundRect(
                width * 0.06f, top, width * 0.42f, top + blockHeight,
                unit * 0.12f, unit * 0.12f, blockPaint,
            )
            canvas.drawRoundRect(
                width * 0.52f, top, width * 0.94f, top + blockHeight,
                unit * 0.12f, unit * 0.12f, blockPaint,
            )
        }

        // 模拟文字行（粗细不一的深色横条）
        val lineColors = intArrayOf(
            0xB32B2B2B.toInt(), 0x99555555.toInt(), 0x80333333.toInt(), 0xB3222222.toInt(),
        )
        for (row in 0 until 4) {
            linePaint.color = lineColors[row]
            val top = row * unit + unit * 0.30f
            canvas.drawRoundRect(
                width * 0.09f, top, width * 0.38f, top + unit * 0.06f,
                unit * 0.03f, unit * 0.03f, linePaint,
            )
            canvas.drawRoundRect(
                width * 0.09f, top + unit * 0.09f, width * 0.32f, top + unit * 0.15f,
                unit * 0.03f, unit * 0.03f, linePaint,
            )
            canvas.drawRoundRect(
                width * 0.55f, top, width * 0.90f, top + unit * 0.06f,
                unit * 0.03f, unit * 0.03f, linePaint,
            )
        }
    }
}
