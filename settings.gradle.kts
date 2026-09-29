// Board —— 跨设备输入法
// 阶段一：仅 Android 端核心（IME 服务 + 键盘 UI + 输入引擎接口 + Room 数据层）

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Board"

// 后续阶段会在此处加入 :android:sync、:android:desktop-bridge 等模块
include(":android:app")
