package com.board.ime.dictionary

/**
 * 自定义词 / 常用语数据变化 → 通知当前活跃的输入法刷新 Board 侧映射。
 *
 * 背景：`DictionaryRepository` 是「每个页面 / 每个服务各自 new」，**不是进程单例**；
 * 设置页改完自定义词后，正在运行的 `BoardInputMethodService` 拿不到任何通知，
 * 只能等下一次进入输入框才刷新（数字自定义编码 / 屏蔽词都受此影响）。
 *
 * 这是**进程内最小通知**：不新建事件总线，只保留「当前活跃 IME」的一个回调，
 * 由 IME 自己在 `onCreate` 注册、`onDestroy` 注销（生命周期感知；不持有 Activity；
 * 服务销毁后不会再有回调，因此不会泄漏）。
 *
 * 没有活跃 IME（例如只在 WorkManager 里做同步）时，[notifyChanged] 是空操作，不抛异常。
 */
object DictionaryChangeNotifier {

    @Volatile
    private var listener: (() -> Unit)? = null

    /** 由当前活跃的输入法服务注册；重复注册以最后一次为准。 */
    fun register(listener: () -> Unit) {
        this.listener = listener
    }

    /**
     * 注销。必须传入与注册时**同一个** lambda 实例，避免把后来注册的回调误清掉。
     */
    fun unregister(listener: () -> Unit) {
        if (this.listener === listener) {
            this.listener = null
        }
    }

    /** 数据已变化（并已按既有流程同步到 Rime）；没有活跃输入法时为空操作。 */
    fun notifyChanged() {
        listener?.invoke()
    }
}
