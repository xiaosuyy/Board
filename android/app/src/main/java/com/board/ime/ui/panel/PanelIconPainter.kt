package com.board.ime.ui.panel

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import com.board.ime.R

/**
 * 顶部面板用到的图标。
 *
 * 全部是 `24×24` viewport 的 VectorDrawable（`res/drawable/ic_board_*.xml`），
 * 统一线宽（1.7–2.0）、统一圆头圆角、统一视觉重量，运行时按主题着色。
 */
enum class PanelIcon(@DrawableRes val res: Int) {
    MORE(R.drawable.ic_board_more),
    CLIPBOARD(R.drawable.ic_board_clipboard),
    EDIT(R.drawable.ic_board_edit),
    BACK(R.drawable.ic_board_back),
    CHEVRON_UP(R.drawable.ic_board_chevron_up),
    CHEVRON_DOWN(R.drawable.ic_board_chevron_down),
    THEME(R.drawable.ic_board_theme),
    TRANSLATE(R.drawable.ic_board_translate),
    VOICE(R.drawable.ic_board_voice),
    SETTINGS(R.drawable.ic_board_settings),
    PHRASES(R.drawable.ic_board_phrases),
    CURSOR_LEFT(R.drawable.ic_board_cursor_left),
    CURSOR_RIGHT(R.drawable.ic_board_cursor_right),
    CHECK(R.drawable.ic_board_check),
    CLOSE(R.drawable.ic_board_close),
    SEARCH(R.drawable.ic_board_search),
    PIN(R.drawable.ic_board_pin),
    STAR(R.drawable.ic_board_star),
    TRASH(R.drawable.ic_board_trash),
    KEYBOARD_HIDE(R.drawable.ic_board_keyboard_hide),
    CLOUD(R.drawable.ic_board_cloud),
    DEVICE(R.drawable.ic_board_device),
    DOWNLOAD(R.drawable.ic_board_download),
}

/**
 * 统一的矢量图标渲染器。
 *
 * 所有图标共用同一套矢量资源、同一 viewport、同一线宽，
 * 因此不会出现「某个图标特别粗 / 特别大」的问题；
 * 颜色通过 `setTint` 运行时注入，自动跟随浅色 / 深色主题。
 */
class PanelIconPainter(private val resources: Resources) {

    /** key = 资源 id * 31 + 颜色，避免每次绘制都 setTint / mutate */
    private val cache = HashMap<Long, Drawable>()

    fun draw(canvas: Canvas, centerX: Float, centerY: Float, size: Float, icon: PanelIcon, color: Int) {
        val half = size / 2f
        draw(canvas, RectF(centerX - half, centerY - half, centerX + half, centerY + half), icon, color)
    }

    fun draw(canvas: Canvas, rect: RectF, icon: PanelIcon, color: Int) {
        val drawable = obtain(icon, color) ?: return
        drawable.setBounds(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        )
        drawable.draw(canvas)
    }

    private fun obtain(icon: PanelIcon, color: Int): Drawable? {
        val key = icon.res.toLong() * 31L + color
        cache[key]?.let { return it }
        val drawable = ResourcesCompat.getDrawable(resources, icon.res, null)?.mutate() ?: return null
        drawable.setTint(color)
        cache[key] = drawable
        return drawable
    }
}
