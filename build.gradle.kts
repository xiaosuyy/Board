// 顶层构建文件：只声明插件，不在此处应用
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
