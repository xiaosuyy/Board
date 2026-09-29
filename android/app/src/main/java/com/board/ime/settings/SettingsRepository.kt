package com.board.ime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.ColorInt

/**
 * 键盘外观 / 尺寸参数仓库。
 *
 * ## 为什么单独一个存储
 * - 这些参数**每秒可能被拖动好几次**（SeekBar），放进 Room 既没必要也会拖慢
 * - 需求明确：UI 参数不进数据库，也不参与数据库迁移
 * - 独立文件 + 独立监听，输入法服务可以只订阅它做**实时预览**
 *
 * 存储位置：`board_ui_settings`（普通 SharedPreferences）。
 * 主题（跟随系统 / 浅色 / 深色）与候选字号仍然沿用原有的 `board_settings`，
 * 这样升级上来的用户不会因为换存储而丢失已有选择。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 监听

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ---------------------------------------------------------------- 键盘尺寸

    fun geometry(scope: KeyboardScope): KeyboardGeometry = KeyboardGeometry(
        level = KeyboardHeightLevel.fromName(prefs.getString(key(scope, KEY_LEVEL), null)),
        customHeightFactor = finiteOrDefault(
            prefs.getFloat(key(scope, KEY_HEIGHT_FACTOR), 1f),
            1f,
        ).coerceIn(KeyboardGeometry.MIN_CUSTOM_FACTOR, KeyboardGeometry.MAX_CUSTOM_FACTOR),
        keyGapDp = readOptionalFloat(scope, KEY_GAP),
        keyCornerRadiusDp = readOptionalFloat(scope, KEY_RADIUS),
        functionKeyWidthRatio = finiteOrDefault(prefs.getFloat(key(scope, KEY_FN_RATIO), 1f), 1f)
            .coerceIn(KeyboardGeometry.MIN_FN_RATIO, KeyboardGeometry.MAX_FN_RATIO),
    )

    /**
     * 读一个「可不设置」的浮点值。
     *
     * 做了两层保护，避免坏值把键盘弄成畸形甚至看不见：
     * - 不是有限数（NaN / Infinity）→ 当作「未设置」
     * - 存的是别的类型（历史/手改）→ 当作「未设置」，**不抛异常**
     */
    private fun readOptionalFloat(scope: KeyboardScope, suffix: String): Float = try {
        finiteOrDefault(prefs.getFloat(key(scope, suffix), KeyboardGeometry.UNSET), KeyboardGeometry.UNSET)
    } catch (throwable: ClassCastException) {
        Log.w(TAG, "键盘尺寸值类型不对，按未设置处理：$suffix")
        KeyboardGeometry.UNSET
    }

    private fun finiteOrDefault(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    /** 改一个作用域的几何参数（读-改-写，只落这一组键）。 */
    fun updateGeometry(scope: KeyboardScope, transform: (KeyboardGeometry) -> KeyboardGeometry) {
        val geometry = transform(geometry(scope))
        prefs.edit()
            .putString(key(scope, KEY_LEVEL), geometry.level.name)
            .putFloat(
                key(scope, KEY_HEIGHT_FACTOR),
                geometry.customHeightFactor
                    .coerceIn(KeyboardGeometry.MIN_CUSTOM_FACTOR, KeyboardGeometry.MAX_CUSTOM_FACTOR),
            )
            .putFloat(key(scope, KEY_GAP), geometry.keyGapDp)
            .putFloat(key(scope, KEY_RADIUS), geometry.keyCornerRadiusDp)
            .putFloat(
                key(scope, KEY_FN_RATIO),
                geometry.functionKeyWidthRatio
                    .coerceIn(KeyboardGeometry.MIN_FN_RATIO, KeyboardGeometry.MAX_FN_RATIO),
            )
            .apply()
    }

    /** 恢复某个作用域的键盘尺寸默认值 */
    fun resetGeometry(scope: KeyboardScope) {
        prefs.edit()
            .remove(key(scope, KEY_LEVEL))
            .remove(key(scope, KEY_HEIGHT_FACTOR))
            .remove(key(scope, KEY_GAP))
            .remove(key(scope, KEY_RADIUS))
            .remove(key(scope, KEY_FN_RATIO))
            .apply()
    }

    // ---------------------------------------------------------------- 外观

    fun appearance(): AppearanceConfig {
        val colors = HashMap<AppearanceColor, Int>()
        AppearanceColor.entries.forEach { target ->
            // 类型不对 / 读取异常都不能让设置页或输入法挂掉
            val value = runCatching { prefs.getInt(colorKey(target), NO_COLOR) }.getOrDefault(NO_COLOR)
            if (value != NO_COLOR) colors[target] = value
        }
        val radii = HashMap<AppearanceRadius, Float>()
        AppearanceRadius.entries.forEach { target ->
            val value = runCatching {
                prefs.getFloat(radiusKey(target), AppearanceConfig.UNSET_RADIUS)
            }.getOrDefault(AppearanceConfig.UNSET_RADIUS)
            if (value.isFinite() && value != AppearanceConfig.UNSET_RADIUS) {
                radii[target] = value.coerceIn(target.minDp, target.maxDp)
            }
        }
        return AppearanceConfig(colors, radii)
    }

    /** 设置 / 清除某个颜色项（传 null 表示恢复默认） */
    fun setColor(target: AppearanceColor, @ColorInt value: Int?) {
        prefs.edit().apply {
            if (value == null) remove(colorKey(target)) else putInt(colorKey(target), value)
        }.apply()
    }

    /** 设置 / 清除某个圆角项（传 null 表示恢复默认） */
    fun setRadius(target: AppearanceRadius, value: Float?) {
        prefs.edit().apply {
            if (value == null) {
                remove(radiusKey(target))
            } else {
                putFloat(radiusKey(target), value.coerceIn(target.minDp, target.maxDp))
            }
        }.apply()
    }

    /** 一键恢复默认外观（白 / 黑 / 灰 + 少量蓝） */
    fun resetAppearance() {
        prefs.edit().apply {
            AppearanceColor.entries.forEach { remove(colorKey(it)) }
            AppearanceRadius.entries.forEach { remove(radiusKey(it)) }
        }.apply()
    }

    // ---------------------------------------------------------------- 内部

    private fun key(scope: KeyboardScope, suffix: String): String =
        "kbd_${scope.name.lowercase()}_$suffix"

    private fun colorKey(target: AppearanceColor): String = "color_${target.name.lowercase()}"

    private fun radiusKey(target: AppearanceRadius): String = "radius_${target.name.lowercase()}"

    companion object {
        const val FILE_NAME = "board_ui_settings"

        private const val TAG = "BoardUiSettings"

        /** 颜色未设置时的哨兵值 */
        private const val NO_COLOR = 0

        private const val KEY_LEVEL = "height_level"
        private const val KEY_HEIGHT_FACTOR = "height_factor"
        private const val KEY_GAP = "key_gap"
        private const val KEY_RADIUS = "key_radius"
        private const val KEY_FN_RATIO = "fn_width_ratio"
    }
}
