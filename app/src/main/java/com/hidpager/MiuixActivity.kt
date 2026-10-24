package com.hidpager

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hidpager.hid.HidDeviceService
import com.hidpager.hid.HidDeviceService.KeyMode
import com.hidpager.hid.HidKeys
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Miuix（HyperOS 风格）界面。
 *
 * 在原有 Miuix 主题/Scaffold/TopAppBar/Button 结构上接入真实 HID 功能：
 * - onStart() 绑定主界面已启动的 HidDeviceService（仅 bind，不 startService）；
 * - 通过 HidDeviceService.Listener 用 Compose 状态驱动连接状态行；
 * - 「上一页/下一页」按钮调 sendKey，未连接时给出可见提示而非静默失败。
 * hid/ 包与 MainActivity 的既有绑定/注册逻辑零改动。
 */
class MiuixActivity : AppCompatActivity() {

    private var service: HidDeviceService? = null

    // ---- Compose 状态：0 未注册 / 1 连接中 / 2 已连接；设备名；瞬时提示；媒体模式开关 ----
    private var hidState by mutableIntStateOf(0)
    private var deviceName by mutableStateOf("")
    private var hint by mutableStateOf<String?>(null)
    private var mediaMode by mutableStateOf(false)

    private val hidListener = object : HidDeviceService.Listener {
        override fun onHidStateChanged(state: Int, device: BluetoothDevice?) {
            // onAppStatusChanged/onConnectionStateChanged 经 registerApp 的 Executor 回调，
            // 可能在非主线程触发；Compose 快照状态必须在主线程写入（对齐 MainActivity 的 runOnUiThread 包裹）
            runOnUiThread {
                hidState = state
                deviceName = try {
                    device?.name ?: ""
                } catch (e: SecurityException) {
                    ""
                }
            }
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
            hidState = svc.getHidState()
            mediaMode = svc.getKeyMode() == KeyMode.MEDIA
            deviceName = try {
                svc.getConnectedDevice()?.name ?: ""
            } catch (e: SecurityException) {
                ""
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            hidState = 0
            deviceName = ""
            mediaMode = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 可用模式：System / Light / Dark / MonetSystem / MonetLight / MonetDark
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                Scaffold(
                    topBar = { TopAppBar(title = "翻页遥控器") },
                    content = { innerPadding ->
                        // 状态行：未连接 → 提示回主界面连接；连接中/已连接复用主界面文案
                        val statusText = when (hidState) {
                            1 -> getString(R.string.status_connecting)
                            2 -> getString(
                                R.string.status_connected,
                                deviceName.ifEmpty { getString(R.string.device_unknown) }
                            )
                            else -> getString(R.string.miuix_status_idle)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterVertically
                            )
                        ) {
                            Text(statusText)
                            // 当前按键模式 + 切换开关（miuix Switch，checked/onCheckedChange 两个必填参）
                            Text(getString(if (mediaMode) R.string.mode_media else R.string.mode_keyboard))
                            Switch(
                                checked = mediaMode,
                                onCheckedChange = { checked ->
                                    mediaMode = checked
                                    service?.setKeyMode(if (checked) KeyMode.MEDIA else KeyMode.KEYBOARD)
                                }
                            )
                            Button(onClick = { sendPage(up = true) }) {
                                Text("上一页")
                            }
                            Button(onClick = { sendPage(up = false) }) {
                                Text("下一页")
                            }
                            // 瞬时操作提示（未连接等），2 秒后自动消失
                            hint?.let { Text(it) }
                            LaunchedEffect(hint) {
                                if (hint != null) {
                                    delay(2000L)
                                    hint = null
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    /** 点击翻页：按当前模式选 Usage（键盘→PageUp/Down；媒体→Volume+/-）；未连接给出可见提示，不静默失败 */
    private fun sendPage(up: Boolean) {
        val svc = service
        if (svc == null || svc.getHidState() != 2) {
            hint = getString(R.string.log_not_connected)
            return
        }
        val usage = when (svc.getKeyMode()) {
            KeyMode.MEDIA -> if (up) HidKeys.MEDIA_VOLUME_UP else HidKeys.MEDIA_VOLUME_DOWN
            KeyMode.KEYBOARD -> if (up) HidKeys.KEY_PAGE_UP else HidKeys.KEY_PAGE_DOWN
        }
        svc.sendKey(usage)
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
            hint = e.message
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
