# ============================================================
# HidPager Release 混淆（R8）规则 —— 保守优先，绝对保蓝牙 HID 功能
# 应用自身体积极小（6 个 Kotlin 文件），宁可牺牲压缩率也要保证：
#   1) Manifest 中声明的三个组件（MainActivity / MiuixActivity / HidDeviceService）
#      可被系统/框架按名字实例化；
#   2) HidDeviceService 内部匿名 BluetoothHidDevice.Callback 回调对象、
#      LocalBinder、动态注册的两个 BroadcastReceiver、ScanCallback 匿名对象
#      不被 R8 裁剪/改名导致系统回调失效。
# ============================================================

# ---- 1. 保留注解/泛型签名/内部类元信息（反射与 Compose 稳定性分析所需）----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# ---- 2. 应用自身代码整体保留（含匿名内部类）----
# 理由：蓝牙 HID 全部依赖由框架按类名/回调对象触发：
#   - HidDeviceService 由 AndroidManifest 实例化，内部匿名 hidCallback
#     继承 android.bluetooth.BluetoothHidDevice.Callback()，经
#     BluetoothHidDevice.registerApp(...) 交给系统回调（onAppStatusChanged 等）；
#   - LocalBinder 经 onBind() 返回给 Activity；
#   - MainActivity.bondReceiver、DeviceScanner.classicReceiver 为动态注册
#     BroadcastReceiver，DeviceScanner 内 ScanCallback 为匿名对象；
# 上述链路一旦被裁剪/改名即破坏 HID 功能。应用代码体积极小，全包 keep 代价可忽略。
-keep class com.hidpager.** { *; }
# 显式兜底：匿名内部类（$1 等）随外部类一并保留，确保系统回调方法签名稳定
-keep class com.hidpager.**$* { *; }

# ---- 3. Miuix（top.yukonga.miuix.kmp:miuix-android:0.8.8）----
# 核查结论（见交付报告）：该 AAR 内【无 proguard.txt / 无 META-INF/proguard consumer rules】，
# classes.jar 内 strings 扫描无 Class.forName / 反射 / ServiceLoader 使用，
# 官方文档站亦未给出 R8/ProGuard 要求。但其为 Compose Multiplatform UI 库，
# 组件经 Compose 编译器插件生成的桥接代码在运行期调度，且无官方规则可依据；
# 按"宁保守勿漏"原则整体保留 miuix 包类与成员，避免 Compose 组合树被裁。
-keep class top.yukonga.miuix.kmp.** { *; }

# ---- 4. Compose / AndroidX 体系 ----
# 已核查：androidx.compose.ui:ui-android:1.10.5 AAR 自带 proguard.txt
# （ViewLayerContainer / AndroidComposeView / ModifierNodeElement / 异常桩等 consumer rules），
# androidx.appcompat:1.7.0、com.google.android.material:1.12.0 亦各自自带 proguard.txt，
# AGP 会自动合并 AAR consumer rules，无需在此手写。
# 仅兜底保留 Compose runtime（防御性，覆盖 Miuix 传递依赖的 runtime 层）：
-keep class androidx.compose.runtime.** { *; }
