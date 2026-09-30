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

android {
    namespace = "com.board.ime"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.board.ime"
        minSdk = 26
        targetSdk = 36
        // 正式版 v1.0.0。
        // versionCode 保持单调递增（1 → 2 → 3），不破坏已有升级链。
        versionCode = 3
        versionName = "1.0.0"

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

    signingConfigs {
        /**
         * 正式包（v1.0.0）签名。
         *
         * 用项目既有的 debug 证书：
         *   · 与当前已安装版本（versionCode 2 / 0.2.0）**同签名**，
         *     否则覆盖升级会因签名不一致被系统拒绝，本轮「升级安装」验收无法通过；
         *   · 仓库里不含任何私钥（keystore 只在开发机 ~/.android 下）。
         *
         * 将来要发应用商店/外部分发时，把这三行换成自己的正式证书即可，
         * 注意换证书后就无法再覆盖升级本次的安装（需要卸载重装）。
         */
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 暂不混淆：JNI 的 native 方法名与 Room/Kotlin 反射类名必须保留，
            // 打开 R8 需要一整套 keep 规则，属于后续独立任务（v1.1）。
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
