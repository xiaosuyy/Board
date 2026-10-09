package com.board.ime.engine.rime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * librime 运行时。
 *
 * 负责「一次性」的全局流程：释放数据 → RimeSetup / RimeInitialize → 部署 → 创建会话。
 * 部署很慢（要构建词库索引），必须在后台线程调用，因此这里只提供阻塞式步骤，
 * 由调用方（输入法服务）放到 IO 协程里执行。
 *
 * 会话数量：本阶段一个输入法实例只用一个会话，切换输入框时复用。
 */
object RimeRuntime {

    private const val TAG = "BoardRime"

    /** 第一版默认方案：朙月拼音 · 简化字（简体输出） */
    const val DEFAULT_SCHEMA = "luna_pinyin_simp"

    /**
     * 用户词典名（学习记录写在这里）。
     * 由 schema 的 `translator/dictionary` 决定：luna_pinyin(_simp) 都是 `luna_pinyin`，
     * 因此两种方案共享同一份学习记录。数据落在 `<user_data_dir>/luna_pinyin.userdb/`。
     */
    const val USER_DICT_NAME = "luna_pinyin"

    /**
     * 自定义短语词典（schema 里的 `table_translator@custom_phrase`，`user_dict: custom_phrase`）。
     * 文件是 `<user_data_dir>/custom_phrase.txt`，每行 `词条<TAB>编码`，优先级高于普通词库。
     */
    const val CUSTOM_PHRASE_DICT = "custom_phrase"

    private val lock = ReentrantLock()

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionId = 0L

    @Volatile
    var librimeVersion: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var isDeploying: Boolean = false
        private set

    /** 上一次 [initialize] 是否重新释放了 assets 数据 */
    @Volatile
    var dataWasReinstalled: Boolean = false
        private set

    /** 用户数据目录（学习记录 / 自定义短语） */
    @Volatile
    private var userDirPath: String? = null

    /** 首次 [initialize] 时保存的 Context / 版本，用于「重建引擎」时重新 setup */
    @Volatile
    private var storedContext: Context? = null

    @Volatile
    private var storedVersion: String = ""

    /** 最近一次「按键/组词」活动时间，用来避免同步任务打断正在进行的输入 */
    @Volatile
    private var lastActivityAt: Long = 0L

    val isNativeAvailable: Boolean
        get() = RimeNative.isAvailable

    /** 会话是否可用（部署完成之后才为 true） */
    val isReady: Boolean
        get() = sessionId != 0L

    /** 当前会话 id，0 表示不可用 */
    val session: Long
        get() = sessionId

    /**
     * 在**持有会话锁**的前提下使用当前会话。
     *
     * 为什么需要：同步任务（导出/导入学习词、重写 custom_phrase）会先销毁会话再重开；
     * 如果按键路径先用 [session] 取到 id、随后同步线程把会话销毁了，就会拿一个**已销毁的
     * session 去调 native** —— 真机上表现为「用着用着输入法自己退出/键盘消失」。
     * 这里把「取 id」和「调用」放进同一把锁，保证两者严格互斥：
     * 键处理要么完整跑在旧会话上，要么完整跑在新会话上。
     *
     * @param fallback 会话不可用时的返回值
     */
    fun <T> withSession(fallback: T, block: (Long) -> T): T {
        lock.lock()
        return try {
            val id = sessionId
            if (id == 0L) {
                fallback
            } else {
                lastActivityAt = System.currentTimeMillis()
                block(id)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 最近有没有在输入。
     *
     * 同步任务导出/导入学习词时需要**先销毁会话**；如果用户正在打字，这一步会把
     * 组词打断（真机上表现为「打一半突然没了」）。所以同步侧先用这个判断，最近有输入
     * 就跳过一次学习词同步，等下一次机会再传——数据不会丢，只是延后。
     */
    fun isRecentlyActive(windowMs: Long): Boolean =
        System.currentTimeMillis() - lastActivityAt < windowMs

    /** 初始化 RimeSetup + RimeInitialize；可重复调用 */
    fun initialize(context: Context, appVersion: String): Boolean {
        lock.withLock {
            if (initialized) return true
            lastError = null
            storedContext = context.applicationContext
            storedVersion = appVersion

            if (!RimeNative.isAvailable) {
                lastError = "board_rime.so 未加载"
                Log.e(TAG, lastError!!)
                return false
            }

            val installer = RimeDataInstaller(context)
            val reCopied = try {
                installer.install()
            } catch (throwable: Throwable) {
                lastError = "释放 Rime 数据失败：${throwable.message}"
                Log.e(TAG, "释放 Rime 数据失败", throwable)
                return false
            }
            dataWasReinstalled = reCopied
            userDirPath = installer.userDir().absolutePath

            val ok = try {
                RimeNative.nativeSetup(
                    installer.sharedDir().absolutePath,
                    installer.userDir().absolutePath,
                    APP_NAME,
                    appVersion,
                    installer.logDir().absolutePath,
                )
            } catch (throwable: Throwable) {
                Log.e(TAG, "RimeSetup 失败", throwable)
                false
            }

            if (!ok) {
                lastError = "RimeSetup 失败"
                return false
            }

            librimeVersion = RimeNative.nativeVersion().orEmpty()
            initialized = true
            Log.i(TAG, "librime $librimeVersion 初始化完成，assets 重新释放=$reCopied")
            return true
        }
    }

    /** 阻塞式部署（构建词库索引），必须放在后台线程 */
    fun deploy(fullCheck: Boolean): Boolean {
        if (!initialized) {
            lastError = "尚未初始化"
            return false
        }
        lock.withLock {
            isDeploying = true
            try {
                val started = RimeNative.nativeStartMaintenance(fullCheck)
                Log.i(TAG, "开始部署 Rime 数据，fullCheck=$fullCheck started=$started")
                // start_maintenance 返回 false 有两种情况：没有需要处理的任务，
                // 或者已经处于维护状态。后者需要 join 等待，否则会在部署中途开会话。
                if (started || RimeNative.nativeIsMaintenanceMode()) {
                    RimeNative.nativeJoinMaintenance()
                }
                Log.i(TAG, "Rime 部署结束")
                return true
            } catch (throwable: Throwable) {
                lastError = "部署失败：${throwable.message}"
                Log.e(TAG, "Rime 部署失败", throwable)
                return false
            } finally {
                isDeploying = false
            }
        }
    }

    /** 部署完成后创建会话并选中默认方案 */
    fun openSession(): Boolean {
        if (!initialized) {
            lastError = "尚未初始化"
            return false
        }
        lock.withLock { return openSessionLocked() }
    }

    private fun openSessionLocked(): Boolean {
        if (sessionId != 0L) return true
        val id = try {
            RimeNative.nativeCreateSession()
        } catch (throwable: Throwable) {
            Log.e(TAG, "创建会话失败", throwable)
            0L
        }
        if (id == 0L) {
            lastError = "创建 Rime 会话失败"
            return false
        }
        val selected = RimeNative.nativeSelectSchema(id, DEFAULT_SCHEMA)
        Log.i(TAG, "创建 Rime 会话 $id，选中方案 $DEFAULT_SCHEMA = $selected")
        sessionId = id
        return true
    }

    /** 关闭会话：leveldb 的用户词典只允许一个句柄，操作 userdb 前必须先释放 */
    private fun closeSessionLocked() {
        if (sessionId != 0L) {
            RimeNative.nativeDestroySession(sessionId)
            sessionId = 0L
        }
    }

    fun currentSchema(): String {
        val id = sessionId
        if (id == 0L) return ""
        return RimeNative.nativeGetCurrentSchema(id).orEmpty()
    }

    /** 重启会话（写了自定义短语、清空学习记录之后调用） */
    fun restartSession(): Boolean {
        lock.withLock {
            closeSessionLocked()
            return if (initialized) openSessionLocked() else false
        }
    }

    /** 用户数据目录：学习记录（userdb）与自定义短语都在这里 */
    fun userDataDir(): File? = userDirPath?.let { File(it) }

    /** 自定义短语文件（Rime 标准格式：每行 `词条<TAB>编码`） */
    fun customPhraseFile(): File? = userDataDir()?.let { File(it, "$CUSTOM_PHRASE_DICT.txt") }

    /**
     * 导出学习记录（用户词典）到文本文件。
     * @return 条目数；失败返回 -1
     */
    fun exportUserDict(destination: File): Int = withSessionReleased {
        RimeNative.nativeExportUserDict(USER_DICT_NAME, destination.absolutePath)
    }

    /**
     * 从文本文件导入学习记录。
     *
     * 导入只写了磁盘上的 userdb；librime 的翻译器 / 用户词典挂在**进程级 Engine** 上，
     * 内存里的旧词表不会自动重载。因此默认导入后**重建引擎**，让导入的学习词真正参与候选排序
     * （否则会表现为「导入的学习词有时无效」，要等进程重启才生效）。
     *
     * @return 条目数；失败返回 -1
     */
    fun importUserDict(source: File, reload: Boolean = true): Int {
        if (!initialized) return -1
        val count = lock.withLock {
            closeSessionLocked()
            RimeNative.nativeImportUserDict(USER_DICT_NAME, source.absolutePath)
        }
        if (reload) {
            reloadEngine()
        } else {
            lock.withLock { if (initialized) openSessionLocked() }
        }
        return count
    }

    /**
     * 重建引擎：finalize 后重新 setup + initialize + 开会话。
     *
     * 用于「导入学习词 / 清空学习记录」等直接改动磁盘 userdb 的场景 —— 只有重建 Engine
     * 才能让内存里的用户词典重新加载。失败返回 false（不抛异常）。
     */
    fun reloadEngine(): Boolean {
        val context = storedContext ?: return false
        val version = storedVersion
        lock.withLock {
            closeSessionLocked()
            if (initialized) {
                RimeNative.nativeFinalize()
                initialized = false
            }
        }
        val ok = initialize(context, version)
        if (ok) lock.withLock { openSessionLocked() }
        return ok
    }

    /** 清空学习记录：删除 userdb 目录后**重建引擎**（本地操作，不涉及网络） */
    fun clearUserLearning(): Boolean {
        val removed = lock.withLock {
            closeSessionLocked()
            val dir = userDataDir()
            var r = false
            dir?.listFiles()?.forEach { file ->
                if (file.name.startsWith(USER_DICT_NAME)) {
                    if (file.deleteRecursively()) r = true
                }
            }
            Log.i(TAG, "清空学习记录：removed=$r dir=${dir?.absolutePath}")
            r
        }
        // 删除磁盘 userdb 后必须重建引擎，内存里的用户词典才会真正清空并重新加载
        reloadEngine()
        return removed
    }

    /**
     * 操作 userdb 期间先销毁会话，结束后恢复。
     * 因为同一个 leveldb 用户词典不能被两个句柄同时打开。
     */
    private inline fun <T> withSessionReleased(block: () -> T): T {
        if (!initialized) return -1 as T
        lock.withLock {
            val hadSession = sessionId
            closeSessionLocked()
            try {
                return block()
            } catch (throwable: Throwable) {
                Log.e(TAG, "用户词典操作失败", throwable)
                return -1 as T
            } finally {
                if (hadSession != 0L) openSessionLocked()
            }
        }
    }

    fun close() {
        lock.withLock {
            if (sessionId != 0L) {
                RimeNative.nativeDestroySession(sessionId)
                sessionId = 0L
            }
            if (initialized) {
                RimeNative.nativeFinalize()
                initialized = false
            }
        }
    }

    /**
     * 供候选栏展示的状态文案；返回 null 表示一切正常。
     * 只在与中文输入相关时才有意义。
     */
    fun statusText(): String? = when {
        !RimeNative.isAvailable -> "原生库缺失，中文输入不可用"
        lastError != null -> lastError
        !initialized -> "正在初始化中文引擎…"
        isDeploying -> "正在部署中文词库…"
        sessionId == 0L -> "正在启动中文引擎…"
        else -> null
    }

    private const val APP_NAME = "board"
}
