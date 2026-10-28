package com.hidpager.hid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.hidpager.R

/**
 * 经典「系统 HID」特权状态页（priv-app / BLUETOOTH_PRIVILEGED 路线）。
 *
 * 展示：BluetoothHidDevice 代理是否获取成功（priv-app 部署后非 null）、
 * registerApp 结果、已连接设备，并提供上一页/下一页/播放暂停三键
 * （Consumer 0x0C 页：0xE9 / 0xEA / 0xCD，走现有 HidDeviceService 的 MEDIA 模式）。
 *
 * 与参考工程 ble-hid-mediakeys 的 HidDeviceService 路线对应；本页只做状态展示与按键，
 * 不改变 HidDeviceService 注册逻辑。
 */
class SystemHidActivity : AppCompatActivity() {

    private var service: HidDeviceService? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvProxy: TextView
    private lateinit var tvRegistered: TextView
    private lateinit var tvConnected: TextView

    private val listener = object : HidDeviceService.Listener {
        override fun onHidStateChanged(state: Int, device: android.bluetooth.BluetoothDevice?) {
            runOnUiThread { refresh() }
        }

        override fun onLog(line: String) {
            runOnUiThread { Toast.makeText(this@SystemHidActivity, line, Toast.LENGTH_SHORT).show() }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? HidDeviceService.LocalBinder)?.getService() ?: return
            service = svc
            svc.setListener(listener)
            // 经典模式固定走 Consumer 媒体键（本页用途即电纸书翻页）
            svc.setKeyMode(HidDeviceService.KeyMode.MEDIA)
            if (!svc.isRegistered()) {
                svc.registerHid()
            }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sys_hid)
        title = getString(R.string.syshid_title)

        tvProxy = findViewById(R.id.syshid_tv_proxy)
        tvRegistered = findViewById(R.id.syshid_tv_registered)
        tvConnected = findViewById(R.id.syshid_tv_connected)
        tvStatus = findViewById(R.id.syshid_tv_status)

        findViewById<MaterialButton>(R.id.syshid_btn_retry_register).setOnClickListener {
            val svc = service ?: return@setOnClickListener
            svc.unregisterHid()
            svc.registerHid()
            Toast.makeText(this, R.string.syshid_retry_toast, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.syshid_btn_page_up).setOnClickListener {
            sendOrToast(HidKeys.MEDIA_VOLUME_UP, getString(R.string.btn_page_up))
        }
        findViewById<MaterialButton>(R.id.syshid_btn_page_down).setOnClickListener {
            sendOrToast(HidKeys.MEDIA_VOLUME_DOWN, getString(R.string.btn_page_down))
        }
        findViewById<MaterialButton>(R.id.syshid_btn_play_pause).setOnClickListener {
            sendOrToast(HidKeys.MEDIA_PLAY_PAUSE, getString(R.string.ble_label_play_pause))
        }
    }

    override fun onStart() {
        super.onStart()
        // 与主界面同一服务：仅 bind，不重复 start（主界面已 start 过）
        try {
            bindService(
                Intent(this, HidDeviceService::class.java),
                connection, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            tvStatus.text = e.message
        }
    }

    override fun onStop() {
        super.onStop()
        service?.setListener(null)
        try { unbindService(connection) } catch (_: Exception) {}
        service = null
    }

    private fun sendOrToast(usage: Int, label: String) {
        val svc = service
        if (svc == null || svc.getHidState() != 2) {
            Toast.makeText(this, R.string.syshid_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        svc.sendKey(usage)
        tvStatus.text = getString(R.string.syshid_last_sent, label, "0x${Integer.toHexString(usage).uppercase()}")
    }

    private fun refresh() {
        val svc = service
        if (svc == null) {
            tvProxy.text = getString(R.string.syshid_proxy_unknown)
            tvRegistered.text = getString(R.string.syshid_registered_no)
            tvConnected.text = getString(R.string.syshid_connected_none)
            return
        }
        tvProxy.text = if (svc.isHidProxyAvailable()) {
            getString(R.string.syshid_proxy_ok)
        } else {
            getString(R.string.syshid_proxy_null)
        }
        tvRegistered.text = if (svc.isRegistered()) {
            getString(R.string.syshid_registered_yes)
        } else {
            getString(R.string.syshid_registered_no)
        }
        val dev = svc.getConnectedDevice()
        tvConnected.text = if (dev != null) {
            val name = try { dev.name ?: dev.address } catch (_: Exception) { dev.address }
            getString(R.string.syshid_connected_ok, name ?: "")
        } else {
            getString(R.string.syshid_connected_none)
        }
    }
}
