import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 1
        versionName = "1.0"
        // 工程 strings.xml 全部为中文（无 values-xx 多语言目录），裁剪其余语言资源
        resConfigs = listOf("zh")
    }

    buildTypes {
        release {
            // 开启 R8 代码混淆/压缩 + 资源裁剪（shrinkResources）
            isMinifyEnabled = true
            isShrinkResources = true
            // release 复用 debug 签名，避免引入签名密钥 / CI secrets
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        // 开启 Compose 支持（View+XML 体系保持不变，仅叠加 Compose 入口）
        compose = true
    }

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

    // ---- Compose 基础：版本与 Miuix 0.8.8 传递进来的 androidx.compose 1.10.5 对齐（CMP 1.10.3）----
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")
    // ComponentActivity.setContent { ... } 扩展
    implementation("androidx.activity:activity-compose:1.11.0")

    // ---- Miuix（HyperOS 风格组件库）----
    // 降级 0.9.0 -> 0.8.8：0.9.x 模块重构后拆为 miuix-ui-android，要求 compileSdk 37+；
    // 0.8.8 是重构前的单一工件 miuix-android，compileSdk 36 生态稳定版，API 与 0.9.0 兼容
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
}
