package com.hidpager.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 线程安全的 BLE HOGP 诊断状态容器。
 *
 * - 环形缓冲日志（最多保留 [MAX_LOG] 条），Service 与 Activity 共享；
 * - 广播/连接/bond/CCCD 状态以 @Volatile 字段暴露；
 * - Activity 通过轮询 [snapshot] 拉取最新状态与日志，切页不丢。
 *
 * 不引入 Flow/LiveData 以避免额外依赖与生命周期泄漏；调用方在 UI 线程读取即可。
 */
object BleDiagnostics {

    private const val MAX_LOG = 200

    data class LogEntry(
        val ts: Long = System.currentTimeMillis(),
        val tag: String,
        val message: String,
    ) {
        fun wallClock(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ts))
    }

    private val log = ConcurrentLinkedDeque<LogEntry>()

    @Volatile var advertisingState: String = "未开始"
        private set
    @Volatile var advertisingErrorCode: Int = -1
        private set
    @Volatile var connectionState: String = "未连接"
        private set
    @Volatile var lastConnectionStatus: Int = -1
        private set
    @Volatile var lastRemoteAddress: String = "-"
        private set
    @Volatile var bondState: String = "-"
        private set
    @Volatile var cccdSubscribed: Boolean = false
        private set
    @Volatile var lastNotifyStatus: Int = -1
        private set

    @Synchronized
    fun log(tag: String, message: String) {
        val entry = LogEntry(tag = tag, message = message)
        log.addLast(entry)
        while (log.size > MAX_LOG) log.removeFirst()
    }

    fun setAdvertising(state: String, errorCode: Int = -1) {
        advertisingState = state
        advertisingErrorCode = errorCode
        log("ADV", "$state${if (errorCode >= 0) " (err=$errorCode)" else ""}")
    }

    fun setConnection(status: Int, newState: Int, address: String) {
        lastConnectionStatus = status
        lastRemoteAddress = address
        connectionState = when (newState) {
            BluetoothProfile.STATE_CONNECTED -> "已连接"
            BluetoothProfile.STATE_DISCONNECTED -> "已断开"
            else -> "newState=$newState"
        }
        val statusHex = if (status >= 0) "0x${Integer.toHexString(status)}" else "?"
        log("GATT", "conn: status=$statusHex newState=$newState addr=$address")
    }

    fun setBond(state: String) {
        bondState = state
        log("BOND", "bondState=$state")
    }

    fun setCccd(subscribed: Boolean) {
        cccdSubscribed = subscribed
        log("CCCD", if (subscribed) "主机已订阅通知" else "主机取消订阅通知")
    }

    fun logGattRead(uuid: String, device: String) {
        log("GATT-R", "读 $uuid from $device")
    }

    fun logGattWrite(uuid: String, value: ByteArray, device: String) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        log("GATT-W", "写 $uuid = [$hex] to $device")
    }

    fun logNotify(status: Int, device: String) {
        lastNotifyStatus = status
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("NOTIFY", "发送失败 status=0x${Integer.toHexString(status)} dev=$device")
        }
    }

    fun snapshot(): String {
        val sb = StringBuilder()
        sb.appendLine("== BLE HOGP 诊断 ==")
        sb.appendLine("广播: $advertisingState" +
            (if (advertisingErrorCode >= 0) " (code=$advertisingErrorCode)" else ""))
        sb.appendLine("连接: $connectionState" +
            (if (lastConnectionStatus >= 0) " (status=0x${Integer.toHexString(lastConnectionStatus)})" else ""))
        sb.appendLine("远端: $lastRemoteAddress")
        sb.appendLine("Bond: $bondState")
        sb.appendLine("CCCD: ${if (cccdSubscribed) "已订阅" else "未订阅"}")
        sb.appendLine("最近 notify: " +
            (if (lastNotifyStatus >= 0) "0x${Integer.toHexString(lastNotifyStatus)}" else "-"))
        sb.appendLine("")
        sb.appendLine("== 日志（最近 ${log.size} 条）==")
        for (e in log) {
            sb.appendLine("[${e.wallClock()}] ${e.tag}: ${e.message}")
        }
        return sb.toString()
    }

    fun clear() {
        log.clear()
    }
}
