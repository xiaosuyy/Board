package com.board.ime.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.board.ime.R

/**
 * 键盘音效的统一封装（进程级单例）。
 *
 * 设计要点：
 * - **不依赖系统「触摸提示音」**：旧实现用 `View.playSoundEffect(CLICK)`，走的是系统
 *   触摸音开关（很多 ROM 默认关闭 / 静音），所以「开启了却没声音」。这里改用 SoundPool
 *   播放**自带音效资源**，开关与音量完全由 Board 自己掌控；
 * - 音效走 `USAGE_MEDIA` 流（跟随媒体音量），Board 音量滑块通过 SoundPool 的 play volume
 *   参数独立调节增益，与系统媒体音量互不混淆；
 * - 短音效（20~70ms）+ SoundPool 低延迟播放，连续快速输入不丢音、不重复、不卡顿；
 * - 单例持有 `applicationContext`（不持有 Activity/Service），SoundPool 进程级复用，
 *   不随键盘 Service 生命周期反复重建；
 * - 支持**多套音效**（[SoundStyle]），切换时释放旧 SoundPool 并重新预加载对应资源，
 *   开关 / 音量 / 音效三套配置统一由这里管理，所有按键共用同一套。
 */
object KeyboardSoundManager {

    /** 按键类别（不同类别使用不同音色，制造细微差别） */
    enum class SoundType { KEY, SPACE, DELETE, ENTER }

    /** 音效风格：经典 / Pure */
    enum class SoundStyle(val label: String) {
        DEFAULT("经典"),
        PURE("Pure");

        companion object {
            fun fromName(name: String?): SoundStyle =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
        }
    }

    private var appContext: Context? = null
    private var pool: SoundPool? = null
    private val soundIds = IntArray(SoundType.entries.size) { -1 }

    /** 音效开关（统一配置，所有按键共用） */
    @Volatile
    private var enabled = true

    /** 音量增益 0..1（由设置页 0%..100% 映射而来） */
    @Volatile
    private var volume = 0.5f

    /** 当前音效风格 */
    @Volatile
    private var style = SoundStyle.DEFAULT

    /** 懒初始化 SoundPool + 预加载当前风格的四段音效（幂等，可反复调用） */
    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        if (pool != null) return
        createPool()
    }

    /**
     * 切换音效风格并立即重新加载资源；相同风格时是空操作。
     * 之后设置页可再 [preview] 试听新音色。
     */
    @Synchronized
    fun setStyle(newStyle: SoundStyle) {
        if (style == newStyle) return
        style = newStyle
        reload()
    }

    /** 统一配置：音效开关 + 音量（0..100）。所有按键共用这一套。 */
    fun configure(enabled: Boolean, volumePercent: Int) {
        this.enabled = enabled
        this.volume = (volumePercent.coerceIn(0, 100)) / 100f
    }

    /** 播放某类按键音效；关闭或音量为 0 时直接静默，不创建任何播放 */
    fun play(type: SoundType) {
        if (!enabled || volume <= 0f) return
        val p = pool ?: return
        val id = soundIds[type.ordinal]
        if (id < 0) return
        p.play(id, volume, volume, 1, 0, 1f)
    }

    /** 试听（设置页拖动音量 / 切换音效时实时播放普通键音效） */
    fun preview() {
        play(SoundType.KEY)
    }

    // ---------------------------------------------------------------- 内部

    private fun createPool() {
        val ctx = appContext ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        val res = when (style) {
            SoundStyle.DEFAULT -> intArrayOf(
                R.raw.key_click,
                R.raw.key_space,
                R.raw.key_delete,
                R.raw.key_enter,
            )
            SoundStyle.PURE -> intArrayOf(
                R.raw.pure_key_click,
                R.raw.pure_key_space,
                R.raw.pure_key_delete,
                R.raw.pure_key_enter,
            )
        }
        for (i in res.indices) {
            soundIds[i] = p.load(ctx, res[i], 1)
        }
        pool = p
    }

    private fun reload() {
        pool?.release()
        pool = null
        soundIds.fill(-1)
        createPool()
    }
}
