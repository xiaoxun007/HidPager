import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hidpager"
    // androidx.compose 1.10.x（Miuix 0.9.0 传递依赖）的 AAR 元数据要求 compileSdk 35+，
    // 直接取 36 一次到位；AGP 8.5.2 对“未正式测试的 compileSdk”仅告警，已在 gradle.properties 抑制
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hidpager"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ---- Compose 基础：版本与 Miuix 0.9.0 传递进来的 androidx.compose 1.10.5 对齐 ----
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")
    // ComponentActivity.setContent { ... } 扩展
    implementation("androidx.activity:activity-compose:1.11.0")

    // ---- Miuix（HyperOS 风格组件库，Android 工件）----
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.0")
}
