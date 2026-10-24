// 根工程 build 脚本
plugins {
    // AGP 8.5.2 -> 8.9.1：Compose 1.10.5 / androidx.core 1.16.0 / activity 1.11.0 等 AAR 元数据要求 AGP >= 8.9.1
    id("com.android.application") version "8.9.1" apply false
    // Kotlin 1.9.24 -> 2.3.20：Miuix 0.9.0 的模块元数据强制要求 Kotlin >= 2.3.20
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Kotlin 2.0+ 起 Compose 编译器独立为插件，版本必须与 Kotlin 严格一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
