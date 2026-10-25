package com.hidpager.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hidpager.R

/**
 * Foreground service owning the [ConsumerPeripheral] advertiser/GATT server so the
 * device stays discoverable while the Activity is backgrounded. Port of PagerService.java.
 */
class BlePagerService : Service() {

    companion object {
        private const val TAG = "BlePagerService"
        const val ACTION_START = "com.hidpager.ble.START"
        const val ACTION_STOP = "com.hidpager.ble.STOP"
        private const val CHANNEL_ID = "ble_pager"
        private const val NOTIFICATION_ID = 0x51

        /** Held so the Activity can drive the peripheral without binding. */
        @JvmStatic
        @Volatile
        var peripheral: ConsumerPeripheral? = null

        /** Human readable connection status, refreshed by the GATT callback. */
        @JvmStatic
        @Volatile
        var status: String = "未广播"
            private set

        /** Most recently seen central device (for the re-pair button). */
        @JvmStatic
        @Volatile
        var lastDevice: BluetoothDevice? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BlePagerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BlePagerService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setStatus(s: String) { status = s }
    }

    private var consumer: ConsumerPeripheral? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BLE Pager", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.ble_notif_title))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        val action = intent?.action ?: ACTION_START
        if (ACTION_STOP == action) {
            stopPeripheral()
            stopSelf()
            return START_NOT_STICKY
        }

        startPeripheral()
        return START_STICKY
    }

    private fun startPeripheral() {
        if (consumer != null) return
        try {
            val p = ConsumerPeripheral(this)
            p.setDeviceName("X3-Pager")
            p.setConnectionStateCallback { device, _, newState ->
                lastDevice = device
                status = if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val bond = try { device.bondState } catch (_: Exception) { BluetoothDevice.BOND_NONE }
                    if (bond == BluetoothDevice.BOND_BONDED) "已连接 / 已配对" else "已连接 / 配对中"
                } else {
                    "广播中（等待主机连接）"
                }
                Log.d(TAG, "connection state: $status")
            }
            consumer = p
            peripheral = p
            p.startAdvertising()
            status = "广播中（等待主机连接）"
            Toast.makeText(this, R.string.ble_toast_advertising, Toast.LENGTH_SHORT).show()
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "BLE peripheral unavailable", e)
            status = "蓝牙不可用"
            Toast.makeText(this, getString(R.string.ble_toast_unavailable, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPeripheral() {
        consumer?.let {
            it.stopAdvertising()
        }
        consumer = null
        peripheral = null
        status = "未广播"
    }

    override fun onDestroy() {
        stopPeripheral()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
