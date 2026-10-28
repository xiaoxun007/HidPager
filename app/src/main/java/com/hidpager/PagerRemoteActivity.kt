package com.hidpager

import android.bluetooth.BluetoothDevice
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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hidpager.hid.HidDeviceService
import com.hidpager.hid.HidDeviceService.KeyMode
import com.hidpager.hid.HidKeys

/**
 * 翻页遥控（标准 View 版页面）。
 *
 * - onStart() 绑定主界面已启动的 HidDeviceService（仅 bind，不 startService）；
 * - 状态行显示 HID 连接状态；
 * - 媒体模式开关：上一页/下一页 ↔ 键盘 PageUp/Down 或 Consumer Volume+/-；
 * - 「上一页/下一页」按钮调 sendKey，未连接时 Toast 提示而非静默失败。
 * hid/ 包与 MainActivity 的既有绑定/注册逻辑零改动。
 */
class PagerRemoteActivity : AppCompatActivity() {

    private var service: HidDeviceService? = null

    private lateinit var tvStatus: TextView
    private lateinit var switchMode: SwitchMaterial

    private val hidListener = object : HidDeviceService.Listener {
        override fun onHidStateChanged(state: Int, device: BluetoothDevice?) {
            // onAppStatusChanged/onConnectionStateChanged 经 registerApp 的 Executor 回调，
            // 可能在非主线程触发，统一切主线程刷新
            runOnUiThread { refreshStatus(device) }
        }

        override fun onLog(line: String) {
            // 演示页不展示日志，忽略
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? HidDeviceService.LocalBinder)?.getService() ?: return
            service = svc
            svc.setListener(hidListener)
            // 绑定后先同步一次当前状态（服务由主界面启动并保持运行，这里不重复 registerHid）
            switchMode.setOnCheckedChangeListener(null)
            switchMode.isChecked = svc.getKeyMode() == KeyMode.MEDIA
            switchMode.setOnCheckedChangeListener { _, checked ->
                svc.setKeyMode(if (checked) KeyMode.MEDIA else KeyMode.KEYBOARD)
                Toast.makeText(
                    this@PagerRemoteActivity,
                    getString(if (checked) R.string.log_mode_media else R.string.log_mode_keyboard),
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshStatus(svc.getConnectedDevice())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshStatus(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pager_remote)
        title = getString(R.string.title_main)

        tvStatus = findViewById(R.id.view_tv_status)
        switchMode = findViewById(R.id.view_switch_mode)

        findViewById<MaterialButton>(R.id.view_btn_page_up).setOnClickListener { sendPage(up = true) }
        findViewById<MaterialButton>(R.id.view_btn_page_down).setOnClickListener { sendPage(up = false) }
    }

    /** 点击翻页：按当前模式选 Usage（键盘→PageUp/Down；媒体→Volume+/-）；未连接给出可见提示，不静默失败 */
    private fun sendPage(up: Boolean) {
        val svc = service
        if (svc == null || svc.getHidState() != 2) {
            Toast.makeText(this, R.string.log_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val usage = when (svc.getKeyMode()) {
            KeyMode.MEDIA -> if (up) HidKeys.MEDIA_VOLUME_UP else HidKeys.MEDIA_VOLUME_DOWN
            KeyMode.KEYBOARD -> if (up) HidKeys.KEY_PAGE_UP else HidKeys.KEY_PAGE_DOWN
        }
        svc.sendKey(usage)
    }

    private fun refreshStatus(device: BluetoothDevice?) {
        val svc = service
        val state = svc?.getHidState() ?: 0
        val devName = try { device?.name ?: "" } catch (e: SecurityException) { "" }
        tvStatus.text = when (state) {
            1 -> getString(R.string.status_connecting)
            2 -> getString(R.string.status_connected, devName.ifEmpty { getString(R.string.device_unknown) })
            else -> getString(R.string.pager_remote_status_idle)
        }
    }

    override fun onStart() {
        super.onStart()
        // 仅绑定主界面已启动的服务；不 startService：未启动时 getHidState()=0，按未连接展示
        try {
            bindService(
                Intent(this, HidDeviceService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        service?.setListener(null)
        service = null
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            /* ignore */
        }
    }
}
