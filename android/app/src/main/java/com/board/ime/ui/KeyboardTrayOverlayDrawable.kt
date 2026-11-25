package com.board.ime.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * 键盘托盘的前景覆盖层：只负责画「顶部高光」。
 *
 * 关键点：工具栏 / 候选栏 / 键盘都是不透明的子 View，会把背景 drawable 完全盖住，
 * 所以高光必须作为 **foreground** 叠在最上层，才能保证看得见。
 *
 * 绘制内容（克制但清晰可见）：
 *  1. **顶部清晰高光描边**：贴着托盘上沿（跟随圆角）的一条细亮线；
 *  2. **顶部软高光带**：从最上沿向下渐隐的一层受光晕，模拟斜面被光打亮。
 *
 * 不再画底部 / 两侧阴影，避免停靠键盘出现突兀的暗边。
 *
 * [docked] 为 true 时只圆上面两角，false 时四角全圆。
 */
class KeyboardTrayOverlayDrawable(
    @ColorInt surfaceColor: Int,
    private val isDark: Boolean,
    private val radiusPx: Float,
    density: Float,
    private val docked: Boolean,
) : Drawable() {

    /** 顶部软高光带高度 */
    private val glowBand: Float = 4f * density
    /** 顶部高光描边线宽 */
    private val strokeWidth: Float = 1.4f * density

    private val highlightRgb: Int
    private val baseAlpha: Int = Color.alpha(surfaceColor)

    init {
        val base = surfaceColor or 0xFF000000.toInt()
        highlightRgb = shade(base, if (isDark) 2.0f else 1.16f)
    }

    /** 把覆盖层的强度同步到「键盘不透明度」：背景越透明，高光也越淡 */
    private fun scaledAlpha(alpha: Int): Int =
        (alpha * (baseAlpha / 255f)).toInt().coerceIn(0, 255)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val r = radiusPx.coerceAtMost(min(w, h) / 2f)
        val bottomRadius = if (docked) 0f else r

        // 1. 顶部软高光带（裁剪到托盘形状，跟随顶部圆角）
        if (glowBand > 0f) {
            path.reset()
            addRounded(path, 0f, 0f, w, h, r, r, bottomRadius, bottomRadius)
            val save = canvas.save()
            canvas.clipPath(path)
            glowPaint.shader = LinearGradient(
                0f, 0f, 0f, glowBand,
                intArrayOf(
                    ColorUtils.setAlphaComponent(highlightRgb, scaledAlpha(if (isDark) 0x55 else 0x78)),
                    ColorUtils.setAlphaComponent(highlightRgb, 0x00),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, glowBand, glowPaint)
            glowPaint.shader = null
            canvas.restoreToCount(save)
        }

        // 2. 顶部清晰高光描边（内缩半个线宽，保证整条线都在托盘内）
        val half = strokeWidth / 2f
        val insetR = (r - half).coerceAtLeast(0f)
        val insetBottomR = (bottomRadius - half).coerceAtLeast(0f)
        path.reset()
        addRounded(
            path,
            half, half, w - half, h - half,
            insetR, insetR, insetBottomR, insetBottomR,
        )
        strokePaint.color = ColorUtils.setAlphaComponent(highlightRgb, scaledAlpha(if (isDark) 0x4A else 0x8C))
        strokePaint.strokeWidth = strokeWidth
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        glowPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glowPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun addRounded(
        p: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
    ) {
        p.addRoundRect(
            left, top, right, bottom,
            floatArrayOf(topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft),
            Path.Direction.CW,
        )
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )
}
