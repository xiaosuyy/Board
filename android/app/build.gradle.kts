import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// librime 静态库所在目录（由 tools/fetch_rime.ps1 下载），按 ABI 分子目录
val rimePrebuiltRoot: String = rootProject.layout.projectDirectory
    .dir("third_party/rime")
    .asFile
    .absolutePath
    .replace('\\', '/')

/**
 * 正式 release 签名材料（keystore 路径与口令）。
 *
 * 读取优先级：**环境变量 > local.properties**，两者都已被 .gitignore 排除；
 * 仓库里不保存任何私钥或口令，构建日志里也绝不打印口令。
 *
 *   board.release.storeFile / BOARD_RELEASE_STORE_FILE
 *   board.release.storePassword / BOARD_RELEASE_STORE_PASSWORD
 *   board.release.keyAlias / BOARD_RELEASE_KEY_ALIAS
 *   board.release.keyPassword / BOARD_RELEASE_KEY_PASSWORD
 *
 * 若缺失：release 仍可编译，但产出 `-unsigned` 包并给出醒目警告，
 * **不会**静默回退到 debug 证书（避免"以为是正式签名、其实是 debug 签名"）。
 */
val signingProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("board.release.storeFile", "BOARD_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("board.release.storePassword", "BOARD_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("board.release.keyAlias", "BOARD_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("board.release.keyPassword", "BOARD_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

// 只报告"有没有 / 用的哪个文件名"，绝不输出任何口令
logger.lifecycle(
    if (hasReleaseSigning) {
        "release 签名材料：已就绪（keystore=${releaseStoreFile!!.substringAfterLast('/')}，alias=${releaseKeyAlias}）"
    } else {
        "release 签名材料：缺失（release 将输出未签名 APK）"
    },
)

android {
    namespace = "com.board.ime"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.board.ime"
        minSdk = 26
        targetSdk = 36
        // v1.2.1：账号与头像（WebDAV 登录、头像同步）、工具栏「更多」显示头像、移除左移/右移图标。
        // versionCode 保持单调递增（1 → 2 → 3 → 4 → 5 → 6 → 7），不破坏已有升级链。
        versionCode = 7
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 真机主流（arm64 / armv7）、本机模拟器（x86_64）
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DRIME_PREBUILT_ROOT=$rimePrebuiltRoot"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 普通 JVM 单测里让 android.util.Log 等桩方法返回默认值（不抛 "not mocked"），
        // 这样即可单测 RimeDictionaryBridge 生成的 Rime 补丁内容。
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        /**
         * 正式 release 签名（v1.1.0 起所有正式版本**共用同一把 keystore**）。
         *
         * 私钥与口令不在仓库里：从环境变量 / local.properties 读取（见文件顶部说明）。
         * 这里只在**四项都齐**时才创建；缺任何一项就不给 release 配签名，
         * 产出 `app-release-unsigned.apk` 并警告，绝不偷偷用 debug 证书。
         */
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8：混淆 + 代码收缩。keep 规则见 proguard-rules.pro（只保留 JNI bridge）。
            isMinifyEnabled = true
            // 资源收缩**不开启**：Rime 的 schema / 词典 / OpenCC 都在 assets/ 下，
            // 由运行时按路径读取，资源收缩无法判断它们被使用；一旦误删输入法直接不可用。
            // 本阶段不冒这个险（收益也远小于代码收缩）。
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠ 未找到 release 签名材料（环境变量 BOARD_RELEASE_* 或 local.properties 的 board.release.*），" +
                        "本次 release 将输出**未签名** APK，不会回退 debug 证书。",
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs {
            // 原生库保持压缩进 APK（默认行为），不额外解压到磁盘
            useLegacyPackaging = false
        }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

// AGP 9 内置 Kotlin 支持，这里统一 JVM target
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WebDAV / SyncClipboard
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    // JVM 单测需要真正的 org.json（Android 运行时自带，但单元测试里是打桩会抛 not mocked）
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
