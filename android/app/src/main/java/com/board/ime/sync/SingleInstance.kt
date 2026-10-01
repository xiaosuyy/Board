package com.board.ime.sync

/**
 * 进程内「唯一共享实例」容器：首次 [get] 时用 [factory] 创建，之后恒返回**同一个**对象。
 *
 * 为什么需要它：Board 的剪贴板捕获入口有多处（输入法服务 / 设置页 / 分享页 / Worker）。
 * 如果各自 `new` 一个 `ClipboardRepository`，B1 的「本机新增历史」回调就会挂在一个
 * **从不被使用**的实例上 —— 真机实测表现为：Android 端复制后
 * `clipboard_log_seq` 永远不产生、远端 `/clipboard-log/<id>.json` 恒 404。
 *
 * Android-free、线程安全、可 JVM 单测。
 */
class SingleInstance<T : Any>(private val factory: () -> T) {

    @Volatile
    private var value: T? = null

    fun get(): T {
        val existing = value
        if (existing != null) return existing
        return synchronized(this) {
            val again = value
            if (again != null) again else factory().also { value = it }
        }
    }

    /** 已经创建出来的实例（未创建时为 null）。装配阶段用来确认拿到的是同一个对象。 */
    val installed: T?
        get() = value
}
