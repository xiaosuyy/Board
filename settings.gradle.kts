// Board —— 跨设备输入法
// 阶段一：仅 Android 端核心（IME 服务 + 键盘 UI + 输入引擎接口 + Room 数据层）

pluginManagement {
    repositories {
        // 国内镜像优先（沙箱无法直连 dl.google.com），原仓库兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "Board"

// 后续阶段会在此处加入 :android:sync、:android:desktop-bridge 等模块
include(":android:app")
