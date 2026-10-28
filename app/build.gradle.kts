import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // kotlin.plugin.compose 已移除：MiuixActivity 重构为 View 版，不再需要 Compose 编译器
}

android {
    namespace = "com.hidpager"
    // 停在 compileSdk 36 生态：Miuix 0.8.8 官方即按 compileSdk 36 构建（其 BuildConfig COMPILE_SDK=36），
    // 而 0.9.x 拆分成的 miuix-ui/core/shapes-android 要求 compileSdk 37（需 AGP 9.x，会连锁升级 Kotlin），故降级
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hidpager"
        minSdk = 28
        targetSdk = 34
        // 版本号由 -PversionCode=xx -PversionName=xx 注入（CI dispatch 传入），无参数时保持默认 1 / 1.0
        versionCode = providers.gradleProperty("versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("versionName").getOrElse("1.0")
        // 工程 strings.xml 全部为中文（无 values-xx 多语言目录），裁剪其余语言资源
        // Kotlin DSL 中 resConfigs 是 vararg 函数而非属性，须以函数调用形式书写
        resConfigs("zh")
    }

    // 正式签名配置：仅当 CI 注入 KEYSTORE_PATH 且文件存在时才填充；
    // 本地无该环境变量则留空，release 回退 debug 签名，本地构建不受影响
    signingConfigs {
        create("release") {
            val ksPath = providers.environmentVariable("KEYSTORE_PATH").orNull
            if (!ksPath.isNullOrEmpty() && File(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码混淆/压缩 + 资源裁剪（shrinkResources）
            isMinifyEnabled = true
            isShrinkResources = true
            // CI 有 keystore 用正式签名；本地缺失回退 debug 签名，避免引入签名密钥 / CI secrets
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile?.exists() == true }
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Compose 已整体移除：MiuixActivity 重构为标准 View+XML 页面，
    // 全工程不再引用 androidx.compose / top.yukonga，故 compose buildFeatures 与全部
    // compose/miuix 依赖（含 activity-compose）一并删除。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- 原有 View+XML 体系依赖（保持不变）----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // androidx.constraintlayout:constraintlayout 已删除：
    // 全量 grep 源码(.kt)/布局(.xml)/gradle 无任何 import 或 XML 标签引用，属未使用依赖

    // Compose 基础（androidx.compose.ui / activity-compose）与 Miuix 组件库
    // （top.yukonga.miuix.kmp:miuix-android）已随 MiuixActivity 的 View 版重构一并移除：
    // 删除 MiuixActivity 的 Compose 实现后全工程无任何 compose import，保留只会徒增体积
}
