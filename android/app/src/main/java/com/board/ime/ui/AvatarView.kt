package com.board.ime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.board.ime.R
import kotlin.math.max

/**
 * 圆形头像。
 *
 * - 有头像：位图按「居中裁剪 + 铺满圆形」绘制（任何比例的图片都不会变形）
 * - 没有头像：绘制默认人形剪影
 *
 * 尺寸完全由布局参数决定（设置首页 44dp、账号页 72dp、工具栏由 ToolbarView 自己画）。
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var avatar: Bitmap? = null

    private val defaultAvatar = ResourcesCompat.getDrawable(resources, R.drawable.ic_board_default_avatar, null)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x46808A96
    }

    private val shaderMatrix = Matrix()

    fun setAvatar(bitmap: Bitmap?) {
        if (avatar === bitmap) return
        avatar = bitmap
        invalidate()
    }

    fun currentAvatar(): Bitmap? = avatar

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = minOf(width, height).toFloat()
        if (diameter <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = diameter / 2f
        val bitmap = avatar
        if (bitmap == null || bitmap.isRecycled) {
            defaultAvatar?.let { drawable ->
                drawable.setBounds(
                    (centerX - radius).toInt(),
                    (centerY - radius).toInt(),
                    (centerX + radius).toInt(),
                    (centerY + radius).toInt(),
                )
                drawable.draw(canvas)
            }
            return
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(diameter / bitmap.width, diameter / bitmap.height)
        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(
            centerX - bitmap.width * scale / 2f,
            centerY - bitmap.height * scale / 2f,
        )
        shader.setLocalMatrix(shaderMatrix)
        bitmapPaint.shader = shader
        canvas.drawCircle(centerX, centerY, radius, bitmapPaint)
        canvas.drawCircle(centerX, centerY, radius - borderPaint.strokeWidth / 2f, borderPaint)
    }
}
