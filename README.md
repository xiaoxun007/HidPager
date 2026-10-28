# HidPager —— 手机蓝牙模拟翻页器

把安卓手机变成一个**蓝牙翻页器/蓝牙键盘**：手机注册为蓝牙 HID 设备（蓝牙键盘角色），与电脑、平板或另一台安卓配对后，在手机界面上按键，即可向主机发送 PageUp / PageDown / 方向键 / 空格 / 回车等标准键盘 HID 报表，用于 PPT 翻页、PDF/网页滚动、视频翻页等场景。

包名：`com.hidpager`；语言：Kotlin。

---

## 1. 功能特性

- **标准按键映射**：上一页 / 下一页 / 方向键（上、下、左、右）/ 空格 / 回车，发送标准 USB HID 键盘报表，主机侧无需知道本 App 的存在。
- **长按连发**：按住按键持续发送，可调节连发间隔（repeat interval），模拟翻页器长按连续翻页。
- **设备扫描与配对**：内置蓝牙扫描列表，展示已配对设备与新发现设备，一键配对 / 连接。
- **断线重连提示**：连接断开时通过 `onConnectionStateChanged` 回调刷新界面状态并在日志区提示，上次连接记录保留在设备列表中，可一键手动重连。
- **运行日志**：界面内展示注册、连接、报表发送的状态日志，便于排查。

## 2. 技术方案说明

本工程基于 Android 开放的 **`BluetoothHidDevice`**（蓝牙 HID Device profile）实现：手机通过 `BluetoothAdapter.getProfileProxy(..., BluetoothProfile.HID_DEVICE)` 拿到 `BluetoothHidDevice` 代理，调用 `registerApp(...)` 向系统注册一份自定义 HID 描述符（标准键盘 Usage Page），之后即可用 `sendReport(...)` 向已连接主机发送按键报表。

**为什么选 BluetoothHidDevice：**

- 它走的是**经典蓝牙 BR/EDR 上的 HID profile**，配对后 Windows / macOS / Android 主机会把手机直接识别为一个"蓝牙键盘"，系统原生支持、即插即用，主机端**不需要安装任何驱动或配套软件**。
- 配套事实：该 API 自 **Android 9（API 28）**起对第三方应用开放，公开项目 dev.fabik.bluetoothhid（F-Droid 上架的"手机当蓝牙扫码枪"）即基于此 API，最低要求 Android 9，可作为"第三方 App 可用"的旁证（来源：F-Droid 应用页 https://f-droid.org/packages/dev.fabik.bluetoothhid/ ；CSDN 开发记录 https://blog.csdn.net/CJohn1994/article/details/127867838 ）。

**为什么不用 SPP / RFCOMM：** SPP 是串口透传协议，主机端必须运行配套接收软件/驱动才能把串口数据翻译成键盘事件，无法即插即用；而翻页器的目标是在任意主机上"开箱即用"。

**为什么不手写 BLE HOGP（HID over GATT）：** Android 公共 API **不提供系统级的 BLE HID 外设（Peripheral）角色**——即不能让系统帮你把手机对外暴露一个 GATT HID 服务（UUID `0x1812`）。自己在 BLE Peripheral 模式下手写 GATT HID 服务栈，需要自行实现 HID Service / Battery Service / 报告特征 / 连接参数协商，且各主机（尤其是 Windows）对 BLE 键盘的兼容性、配对流程、按键上报稳定性参差不齐，工作量大、稳定性差。

**措辞注意：** BluetoothHidDevice 走的是经典蓝牙 **BR/EDR HID**，严格来说**不是** BLE HOGP；但对用户而言配对后的体验就是"连上一个蓝牙键盘"，无差别。

## 3. 系统要求

- **手机端（本机）：Android 9 及以上（API 28+）**。低于 API 28 时 `BluetoothHidDevice` 不是可用的公共 API。
- **手机硬件/ROM**：手机蓝牙栈必须支持并开放 HID Device profile。部分厂商 ROM、精简/定制系统（如部分车机、二手精简 ROM、极少数国行定制系统）可能裁剪或限制该 profile，表现为 `getProfileProxy` 拿不到代理或 `registerApp` 失败——这是系统边界，见第 7 节排查。
- **主机端（被控制的电脑/平板/手机）**：只需支持普通蓝牙键盘即可，Windows / macOS / iPadOS / 安卓平板均原生支持。

## 4. 构建步骤

推荐 **Android Studio（Giraffe 及以上版本）**打开本工程根目录，IDE 会自动下载 Gradle wrapper 并同步依赖。**注意：本机需 JDK 17**（AGP 8.x 要求），Android Studio 自带 JBR 即可，无需单独安装。

命令行构建（已配置好 SDK 环境变量 `ANDROID_HOME`）：

```bash
cd HidPager
./gradlew assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # 直接装到已连接的设备
```

无 Android SDK 环境的机器无法命令行构建，需先安装 Android SDK + 接受 licenses（`sdkmanager --licenses`），或直接在装有 Android Studio 的机器上打开构建。

## 5. 安装与使用

1. 安装 `app-debug.apk`（或 release 包）到手机。
2. 首次启动按提示授予权限：**蓝牙（扫描/连接）、定位（Android 11 及以下扫描时必需）、通知（Android 13+ 授予后 HID 前台服务常驻通知才可显示）**。
3. 进入 App，点击扫描，在列表中选择要控制的主机（电脑/平板），发起配对；若主机尚未配对，请同时在主机蓝牙设置里点击确认配对。
4. 配对成功后，在设备列表中点击该设备进行连接，状态变为"已连接"后即可按键。
5. 断开/重连：点击已连接设备可断开；连接断开时界面状态即时刷新并在日志区记录"已断开连接"，设备列表保留记录，再次点击即可重连。

**按键映射表：**

| App 界面按钮 | 发送的 HID 按键 | 典型用途 |
|---|---|---|
| 上一页 | PageUp (Page_Up) | PPT 上翻一页 / PDF 向上翻 |
| 下一页 | PageDown (Page_Down) | PPT 下翻一页 / PDF 向下翻 |
| ↑ | 方向键 Up | 菜单/列表向上 |
| ↓ | 方向键 Down | 菜单/列表向下 |
| ← | 方向键 Left | 后退/左移 |
| → | 方向键 Right | 前进/右移 |
| 空格 | Space | 播放/暂停（视频、PPT） |
| 回车 | Enter | 确认 |
| 返回 | Escape (Esc) | 退出/取消（依据 HidKeys.kt：Usage 0x29） |

**长按连发：** 按住按钮不松手即持续发送该键，连发间隔在设置中调节（报表级按键 down/up 循环），松手即停。

## 6. 权限清单

| 权限 | 用途 | 所需 API | 类型 |
|---|---|---|---|
| `BLUETOOTH_SCAN` | 扫描发现附近蓝牙设备 | 31+（Android 12+） | 运行时 |
| `BLUETOOTH_CONNECT` | 配对、连接、发送 HID 报表 | 31+（Android 12+） | 运行时 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | 传统蓝牙开关与扫描 | ≤30（maxSdkVersion 30） | 普通（安装时授予） |
| `ACCESS_FINE_LOCATION` | Android 11 及以下蓝牙扫描必须（系统以定位权限作为扫描前置条件，见 AOSP 文档） | ≤30（maxSdkVersion 30） | 运行时 |
| `FOREGROUND_SERVICE` | HID 连接保持为前台服务，防止系统杀死 | 28+（类型声明 34+） | 普通 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 前台服务类型 `connectedDevice`（蓝牙外设场景） | 34+ | 普通 |
| `POST_NOTIFICATIONS` | 允许 HID 前台服务在 Android 13+ 显示常驻通知（断连本身不发通知） | 33+ | 运行时 |

依据：Android 官方蓝牙权限文档（https://developer.android.google.cn/develop/connectivity/bluetooth/bt-permissions ）；AOSP：Android 11 及更低版本 App 使用 BLE/蓝牙扫描需定位权限（https://source.android.google.cn/docs/core/connect/bluetooth/ble ）。

## 7. 已知限制与排查

**配对不上 / 扫描不到主机：**

- 主机蓝牙设置里**删除**本手机的旧配对记录，双方都重启蓝牙后重新配对（旧记录残留是最常见原因）。
- Android 11 及以下：**打开系统定位开关并授予定位权限**，否则系统不返回扫描结果。
- 确认主机蓝牙处于"可发现"状态；部分主机需在蓝牙设置界面停留才可被发现。

**连接成功但按键无响应：**

- 先确认主机真的把手机识别成了"键盘"：Windows 在"蓝牙和设备"里应显示为键盘类设备；可打开记事本按方向键测试。
- **本 App 是否注册成功**：查看 App 内日志。`registerApp()` 返回 `false` 通常意味着**本手机上已有另一个应用占用了 HID Device 角色**——蓝牙栈同时只允许一个应用注册 HID 设备能力（该约束在华为蓝牙 HID 文档中亦有明确说明："只允许一个应用注册 HID 设备能力"，https://developer.huawei.com/consumer/en/doc/harmonyos-references/js-apis-bluetooth-hid ）。请关闭/卸载其他同类 App（蓝牙键盘、游戏手柄模拟、扫码枪类应用）后重试。
- 主机端配对记录损坏：两端都删掉记录重新配对。

**国产 ROM / 厂商限制（已知边界，非本工程代码问题）：**

- 部分国产系统在设置中有"模拟外设 / 蓝牙输入设备"类开关，需手动开启才允许 App 使用 HID Device 角色；不同机型入口各异，待实机验证。
- 部分厂商系统（尤其精简 ROM、车机系统）可能裁剪了 HID Device profile，表现为 `getProfileProxy(HID_DEVICE)` 回调 `onServiceDisconnected` 或拿不到代理。此类机型**无法运行本 App**，属系统能力缺失。
- 小米官方 FAQ 亦说明蓝牙 HID 外设支持因产品而异（https://www.mi.com/global/support/faq/details/KA-491589/ ）。

**断连重连提示的实现说明：** HID 连接由前台服务 `HidDeviceService`（`foregroundServiceType="connectedDevice"`）托管；连接断开时通过 `onConnectionStateChanged` 回调刷新界面状态并在日志区提示，已连接设备记录保留在列表中可一键手动重连；系统层是否自动重连取决于双方蓝牙栈行为，本 App 不做后台自动重连轮询。

## 8. 工程结构

```
HidPager/
├── settings.gradle.kts
├── build.gradle.kts            (root)
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/hidpager/
        │   ├── MainActivity.kt         主界面：按键按钮、连发开关/间隔滑条、设备列表、日志区
        │   ├── DeviceAdapter.kt         设备列表 RecyclerView 适配器
        │   └── hid/
        │       ├── HidDeviceService.kt   BluetoothHidDevice 代理管理、registerApp、sendReport、连接回调
        │       ├── HidKeys.kt            HID 键盘 Usage 码映射与报表构造（含 Esc 0x29）
        │       └── DeviceScanner.kt      蓝牙扫描/配对辅助
        └── res/
            ├── layout/                  activity_main.xml、item_device.xml
            ├── values/                  strings.xml / colors.xml / themes.xml
            ├── drawable/                ic_launcher_foreground.xml
            └── mipmap-anydpi-v26/       ic_launcher.xml 自适应启动图标
```

> 本目录树已按工程最终落地文件逐一核对，与实际结构一致；`AndroidManifest.xml`（minSdk 28 / targetSdk 34，权限与 `connectedDevice` 前台服务类型）、`HidKeys.kt`（8 字节标准键盘报表）、`MainActivity.kt`（日志区、连发开关与间隔滑条、断连仅刷新界面+日志记录）等关键实现均已对照源码修正本文表述。

## 9. 媒体模式（Consumer 0x0C 页，电纸书兼容）

### 9.1 适用场景

部分接收端（典型如**阅星曈 X3 电纸书**）只识别蓝牙 HID 的 **Consumer Control（Usage Page 0x0C）媒体键**，对标准键盘（Usage Page 0x07）报表完全无反应——配对连上后按键全丢。本工程在描述符中追加了一段 Consumer 报表段（Report ID=0x02），可在 App 内一键切到「媒体模式」，把翻页键改发音量+/音量-/播放暂停等标准 Consumer 键，让这类接收端可用。

### 9.2 键码映射表

| App 界面按钮 | 键盘模式（默认，Usage Page 0x07） | 媒体模式（Usage Page 0x0C） |
|---|---|---|
| 上一页 | PageUp `0x4B` | Volume Increment `0xE9`（音量+） |
| 下一页 | PageDown `0x4E` | Volume Decrement `0xEA`（音量-） |
| 回车 | Enter `0x28` | Play/Pause `0xCD`（播放/暂停） |
| ↑ ↓ ← → | 方向键 `0x52/0x51/0x50/0x4F` | **未映射**（媒体模式下点击提示「媒体模式下该键未映射」） |
| 空格 | Space `0x2C` | **未映射**（同上提示） |

### 9.3 阅星曈 X3 实测结论

- **有效码**：该设备只响应 Consumer 0x0C 页；实测上一页=VolumeUp `0xE9`、下一页=VolumeDown `0xEA`、PlayPause `0xCD` 可唤出菜单/锁屏。
- **键盘页无效**：Usage Page 0x07 的全部键码（PageUp/PageDown/方向键/回车/空格）对 X3 均无反应——主页键盘区已因此移除，翻页只需 `0xE9/0xEA/0xCD` 三个 Consumer 键。
- **配对要点**：在**设备自己的应用内菜单**里发起连接（而非系统蓝牙设置页），连上后在翻页器 App 里切到「媒体模式」即可翻页。
- **排查**：按键无反应多半是**模式没切**（还停在键盘模式）；PlayPause 仅在阅读页内响应（系统级页面不响应）属正常。

### 9.4 切换方式

- 翻页遥控（View 版）页：页面内的**「媒体模式」开关**（与 HidDeviceService 内模式实时同步）；主页键盘区/连发设置已移除，不再提供入口。

### 9.5 实现说明（Report ID 方案）

HID 描述符中键盘段与 Consumer 段**都带 Report ID**：键盘段 `Report ID=0x01`、Consumer 段 `Report ID=0x02`。HID 规范要求「若任一报表含 Report ID，则所有报表都必须带」；两个 input 段若都不带 ID，主机会把它们视为合并逻辑报表，电纸书类接收端按描述符切分报表极易解析错乱。成熟样例（如 BleHID Simulator）均按每报表独立 Report ID，接收端按 ID 分发无歧义。键盘报表由 8 字节变为 9 字节（`0x01` 前缀 + 原 8 字节），对标准主机解析等价，键盘模式按键语义零回归。
