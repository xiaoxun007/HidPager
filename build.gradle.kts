// 根工程 build 脚本
plugins {
    // AGP 8.5.2 -> 8.9.1：androidx.core 1.16.0 / activity 1.11.0 等 AAR 元数据要求 AGP >= 8.9.1
    id("com.android.application") version "8.9.1" apply false
    // Kotlin 1.9.24 -> 2.3.20：历史上为升级 Compose 生态 AAR 元数据而升级；
    // Compose 演示页已移除并改为标准 View 实现，Kotlin 版本暂不回退（与 AGP 8.9.1 生态兼容，回退有连锁风险）
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Compose 编译器插件已随 Compose 演示页一并移除（全工程为标准 View+XML 实现）
}
