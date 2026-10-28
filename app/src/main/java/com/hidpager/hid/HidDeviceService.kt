package com.hidpager.hid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.hidpager.R
import java.util.concurrent.Executor

/**
 * 蓝牙 HID Device 前台服务：把本机伪装成标准键盘，向已配对主机发送按键报表。
 */
class HidDeviceService : Service() {

    interface Listener {
        /** state: 0 未注册/断开、1 连接中、2 已连接 */
        fun onHidStateChanged(state: Int, device: BluetoothDevice?)
        fun onLog(line: String)
    }

    // BluetoothHidDevice.Callback 是抽象类而非接口，Kotlin 不允许 Service() 之外再继承它；
    // 改为内部匿名对象持有回调，回调逻辑与原本逐字一致
    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
            this@HidDeviceService.registered = registered
            if (!registered) {
                connectedDevice = null
                stopRepeat()
                publishState(0)
            }
            log("onAppStatusChanged registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    publishState(2)
                    log("已连接: ${device?.name ?: device?.address}")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    publishState(1)
                    log("连接中…")
                }
                else -> {
                    connectedDevice = null
                    stopRepeat()
                    publishState(0)
                    log("已断开")
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): HidDeviceService = this@HidDeviceService
    }

    /**
     * 按键模式：
     * - KEYBOARD（默认）：标准键盘 Usage Page 0x07，Report ID 0x01；
     * - MEDIA：Consumer Control Usage Page 0x0C 媒体键（音量+/-、播放暂停），Report ID 0x02，
     *   用于兼容只认 Consumer 媒体键的接收端（如阅星曈 X3 电纸书：键盘 HID 全丢，只认 0x0C 页）。
     */
    enum class KeyMode { KEYBOARD, MEDIA }

    companion object {
        const val ACTION_START = "com.hidpager.action.START"
        const val ACTION_STOP = "com.hidpager.action.STOP"

        private const val CHANNEL_ID = "hid_pager_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, HidDeviceService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, HidDeviceService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }

    @Volatile private var listener: Listener? = null
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var connectedDevice: BluetoothDevice? = null
    @Volatile private var state = 0

    private val handler = Handler(Looper.getMainLooper())
    private var repeatIntervalMs = 800
    @Volatile private var repeating = false
    @Volatile private var repeatUsage: Int = 0

    // 按键模式：默认 KEYBOARD = 历史行为；MEDIA 发送 Consumer 0x0C 页报表
    @Volatile private var keyMode: KeyMode = KeyMode.KEYBOARD

    /** 切换按键模式；切换时若正在连发先停止，避免旧模式报表残留 */
    fun setKeyMode(mode: KeyMode) {
        if (repeating) stopRepeat()
        keyMode = mode
    }

    fun getKeyMode(): KeyMode = keyMode

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!repeating) return
            val u = repeatUsage
            sendKeyDown(u)
            handler.postDelayed({ sendKeyUp(u) }, 60L) // 每次按下持续 60ms
            handler.postDelayed(this, repeatIntervalMs.toLong())
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundInternal()
                log("HID 翻页器服务已启动")
            }
            ACTION_STOP -> {
                stopRepeat()
                unregisterHid()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRepeat()
        unregisterHid()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hid_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.hid_notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.hid_notification_title))
            .setContentText(getString(R.string.hid_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------- Listener ----------------

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onHidStateChanged(state, connectedDevice)
    }

    private fun log(line: String) {
        listener?.onLog(line)
    }

    private fun publishState(newState: Int) {
        state = newState
        listener?.onHidStateChanged(newState, connectedDevice)
    }

    // ---------------- HID 获取与注册 ----------------

    private fun obtainHid(): BluetoothHidDevice? {
        hid?.let { return it }
        hid = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(BluetoothHidDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getSystemService("bluetooth_hid_device") as? BluetoothHidDevice
            }
        } catch (e: Exception) {
            log("获取 BluetoothHidDevice 失败: ${e.message}")
            null
        }
        if (hid == null) log("当前设备不支持 HID Device profile")
        return hid
    }

    fun isRegistered(): Boolean = registered

    fun registerHid(): Boolean {
        val h = obtainHid() ?: return false
        if (adapter?.isEnabled != true) {
            log("蓝牙未开启，请先打开蓝牙")
            return false
        }
        if (registered) {
            log("HID 应用已注册，跳过重复注册")
            return true
        }
        return try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "HidPager 翻页器",
                "蓝牙翻页遥控器",
                "HidPager",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidKeys.buildReportDescriptor()
            )
            // compileSdk 36 中该类服务类型常量名为 SERVICE_BEST_EFFORT（=1，原代码误写 QOS_BEST_EFFORT）；
            // 且构造函数为 6 参(service, tokenRate, tokenBucketSize, peakBandwidth, latency, delayVariation)，
            // 原代码少传 1 个参数。行为保持：BestEffort 服务类型 + 其余 QoS 参数全 0
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 0, 0, 0, 0, 0
            )
            // compileSdk 36 的 registerApp 真实签名为 5 参：
            // (SdpSettings, inQos: QosSettings, outQos: QosSettings, Executor, Callback)
            // 输入/输出两个 QosSettings 都用 BestEffort 零参数，executor 回调线程、回调对象均不变
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                h.registerApp(sdp, qos, qos, Executor { it.run() }, hidCallback)
            } else {
                // API 28/29/30：系统侧无 Executor 版公开重载，只能反射隐藏 registerApp（参考工程同款分支）
                registerViaReflection(h, sdp)
            }
            log("registerApp 调用结果: $ok")
            ok
        } catch (e: SecurityException) {
            log("registerApp 缺少 BLUETOOTH_CONNECT 权限: ${e.message}")
            false
        } catch (e: Exception) {
            log("registerApp 异常: ${e.message}")
            false
        }
    }

    /**
     * API 28/29/30 兼容分支（与参考工程 ble-hid-mediakeys 一致）：
     * compileSdk 36 仅暴露 5 参 Executor 版 registerApp（API 31+ 系统方法），
     * 旧系统上隐藏的 registerApp 只能按形参类型反射调用，只填 SdpSettings 与 Callback，其余置 null。
     */
    private fun registerViaReflection(
        hid: BluetoothHidDevice,
        sdp: BluetoothHidDeviceAppSdpSettings
    ): Boolean {
        return try {
            for (method in hid.javaClass.methods) {
                if (method.name != "registerApp") continue
                val types = method.parameterTypes
                if (types.isEmpty()) continue
                if (!types[0].isInstance(sdp)) continue
                if (!types[types.size - 1].isInstance(hidCallback)) continue
                val args = arrayOfNulls<Any>(types.size)
                args[0] = sdp
                args[types.size - 1] = hidCallback
                val result = method.invoke(hid, *args)
                result as? Boolean ?: false
            }
            false
        } catch (e: Exception) {
            log("反射 registerApp 失败: ${e.message}")
            false
        }
    }

    fun unregisterHid() {
        val h = hid ?: return
        if (!registered) return
        try {
            h.unregisterApp()
            log("HID 应用已注销")
        } catch (e: SecurityException) {
            log("unregisterApp 权限不足: ${e.message}")
        }
        registered = false
        connectedDevice = null
        publishState(0)
    }

    // ---------------- 连接管理 ----------------

    fun connectDevice(device: BluetoothDevice) {
        if (adapter?.isEnabled != true) {
            log("蓝牙未开启，请先打开蓝牙")
            return
        }
        val h = obtainHid() ?: return
        if (!registered) {
            log("HID 尚未注册，请先执行注册")
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("目标设备未配对，请先在系统蓝牙设置中配对后再连接")
            return
        }
        try {
            h.connect(device)
            log("正在连接 ${device.name ?: device.address} …")
        } catch (e: SecurityException) {
            log("connect 权限不足: ${e.message}")
        }
    }

    fun disconnectDevice() {
        val d = connectedDevice ?: run {
            log("当前没有已连接设备")
            return
        }
        try {
            hid?.disconnect(d)
        } catch (e: SecurityException) {
            log("disconnect 权限不足: ${e.message}")
        }
    }

    // ---------------- 按键发送 ----------------

    private fun sendReport(report: ByteArray): Boolean {
        val d = connectedDevice ?: run {
            log("未连接主机，报表未发送")
            return false
        }
        val h = hid ?: run {
            log("HID 未注册，报表未发送")
            return false
        }
        return try {
            h.sendReport(d, 0, report)
        } catch (e: SecurityException) {
            log("sendReport 权限不足: ${e.message}")
            false
        } catch (e: Exception) {
            log("sendReport 异常: ${e.message}")
            false
        }
    }

    /** 单击：按下后保持 60ms 再抬起，避免主机漏判 */
    fun sendKey(usageId: Int) {
        sendKeyDown(usageId)
        handler.postDelayed({ sendKeyUp(usageId) }, 60L)
    }

    fun sendKeyDown(usageId: Int) {
        // 按当前模式选报表：键盘→Report ID 0x01 键盘报表；媒体→Report ID 0x02 Consumer 报表
        sendReport(
            when (keyMode) {
                KeyMode.MEDIA -> HidKeys.makeConsumerReport(usageId)
                KeyMode.KEYBOARD -> HidKeys.keyDownReport(usageId)
            }
        )
    }

    fun sendKeyUp(usageId: Int) {
        sendReport(
            when (keyMode) {
                KeyMode.MEDIA -> HidKeys.consumerUpReport()
                KeyMode.KEYBOARD -> HidKeys.keyUpReport()
            }
        )
    }

    // ---------------- 长按连发 ----------------

    fun startRepeat(usageId: Int) {
        stopRepeat()
        repeating = true
        repeatUsage = usageId
        // 立即按一次
        sendKeyDown(usageId)
        handler.postDelayed({ sendKeyUp(usageId) }, 60L) // 首次按下持续 60ms
        // 450ms 初始延迟后按 repeatIntervalMs 周期连发
        handler.postDelayed(repeatRunnable, 450L)
        log("开始连发 usage=0x${Integer.toHexString(usageId)}")
    }

    fun stopRepeat() {
        if (!repeating && !handler.hasCallbacks(repeatRunnable)) return
        repeating = false
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacksAndMessages(null)
        // 抬起报表也按当前模式选，避免连发停在旧模式残留按键状态
        sendReport(
            when (keyMode) {
                KeyMode.MEDIA -> HidKeys.consumerUpReport()
                KeyMode.KEYBOARD -> HidKeys.keyUpReport()
            }
        )
        log("停止连发")
    }

    fun setRepeatIntervalMs(ms: Int) {
        repeatIntervalMs = ms.coerceAtLeast(50)
    }

    fun getHidState(): Int = state

    /** 特权状态页用：BluetoothHidDevice 代理是否获取成功（priv-app/BLUETOOTH_PRIVILEGED 生效后非 null） */
    fun isHidProxyAvailable(): Boolean = hid != null

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice
}
