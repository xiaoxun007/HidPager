// 根工程 build 脚本
plugins {
    // AGP 8.5.2 -> 8.9.1：androidx.core 1.16.0 / activity 1.11.0 等 AAR 元数据要求 AGP >= 8.9.1
    id("com.android.application") version "8.9.1" apply false
    // Kotlin 1.9.24 -> 2.3.20：此前 Miuix 0.9.0 模块元数据强制要求 Kotlin >= 2.3.20；
    // Miuix 已移除，Kotlin 版本暂不回退（与 AGP 8.9.1 生态兼容，回退有连锁风险）
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Compose 编译器插件已随 Miuix/Compose 整体移除（全工程无 compose 引用）
}
