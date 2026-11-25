package com.board.ime.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * 键盘背景板：仿 iOS 原生键盘的「实体圆角托盘」。
 *
 * 这里只铺**一层纯色**（= 键盘背景色，已含不透明度），保证候选栏 / 工具栏 / 键盘
 * 三块区域的底色完全一致、没有任何渐变造成的分界感。
 *
 * 顶部的高光由 [KeyboardTrayOverlayDrawable] 以 foreground 形式叠在最上层完成，
 * 与底色解耦，不会破坏背景的统一。
 *
 * [docked] 为 true 时只圆上面两角（停靠铺满屏底），false 时四角全圆（悬浮键盘）。
 */
class KeyboardTrayDrawable(
    @ColorInt surfaceColor: Int,
    private val isDark: Boolean,
    private val radiusPx: Float,
    density: Float,
    private val docked: Boolean,
) : Drawable() {

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surfaceColor
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val r = radiusPx.coerceAtMost(min(w, h) / 2f)
        val bottomRadius = if (docked) 0f else r

        path.reset()
        addRounded(path, 0f, 0f, w, h, r, r, bottomRadius, bottomRadius)
        canvas.drawPath(path, surfacePaint)
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = radiusPx.coerceAtMost(min(w, h) / 2f)
        path.reset()
        val bottomRadius = if (docked) 0f else r
        addRounded(path, 0f, 0f, w, h, r, r, bottomRadius, bottomRadius)
        outline.setConvexPath(path)
    }

    override fun setAlpha(alpha: Int) {
        surfacePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        surfacePaint.colorFilter = colorFilter
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
}
