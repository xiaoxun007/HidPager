# ============================================================
# HidPager Release 混淆（R8）规则 —— 保守优先，绝对保蓝牙 HID 功能
# 应用自身体积极小（少量 Kotlin 文件），宁可牺牲压缩率也要保证：
#   1) Manifest 中声明的组件（MainActivity / PagerRemoteActivity /
#      DiagnosisActivity / BlePagerActivity / SystemHidActivity /
#      HidDeviceService / BlePagerService）可被系统/框架按名字实例化；
#   2) HidDeviceService / BlePagerService 内部匿名回调对象、
#      LocalBinder、动态注册的 BroadcastReceiver、ScanCallback 匿名对象
#      不被 R8 裁剪/改名导致系统回调失效。
# ============================================================

# ---- 1. 保留注解/泛型签名/内部类元信息（反射稳定性分析所需）----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# ---- 2. 应用自身代码整体保留（含匿名内部类）----
# 理由：蓝牙 HID 全部依赖由框架按类名/回调对象触发：
#   - HidDeviceService / BlePagerService 由 AndroidManifest 实例化，内部匿名
#     Callback 经 registerApp / GATT 交给系统回调；
#   - LocalBinder 经 onBind() 返回给 Activity；
#   - MainActivity.bondReceiver、DeviceScanner.classicReceiver 为动态注册
#     BroadcastReceiver，DeviceScanner 内 ScanCallback 为匿名对象；
# 上述链路一旦被裁剪/改名即破坏 HID 功能。应用代码体积极小，全包 keep 代价可忽略。
-keep class com.hidpager.** { *; }
# 显式兜底：匿名内部类（$1 等）随外部类一并保留，确保系统回调方法签名稳定
-keep class com.hidpager.**$* { *; }

# ---- 3. AndroidX 体系 ----
# 已核查：androidx.appcompat:1.7.0、com.google.android.material:1.12.0 各自自带
# proguard.txt（consumer rules），AGP 会自动合并 AAR consumer rules，无需在此手写。
