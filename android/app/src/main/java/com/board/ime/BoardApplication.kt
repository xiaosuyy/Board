package com.board.ime

import android.app.Application
import com.board.ime.util.CrashRecorder

/**
 * Board 的 Application。
 *
 * 只做一件事：装崩溃记录器（真机上不给电脑也能看到堆栈）。
 * 这里**不做**任何重活：不初始化 Rime、不打开数据库、不读网络配置 ——
 * 那些都必须按需、在后台线程做，否则设置页会和输入法进程抢资源。
 */
class BoardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { CrashRecorder.install(this) }
    }
}
