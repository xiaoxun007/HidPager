package com.hidpager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.hidpager.ble.BlePagerService
import com.hidpager.hid.HidDeviceService

/**
 * 主页：纯导航中枢。经典 HID 键盘区/扫描/连接/日志已移除（X3 只认 Consumer 页，
 * 键盘操作无意义），翻页操作分散到各模式页：
 * - BLE 翻页器（BlePagerActivity，HOGP 推荐）
 * - 系统 HID 特权模式（SystemHidActivity，priv-app 路线）
 * - 翻页遥控（PagerRemoteActivity，复用主服务的状态/模式）
 * - 环境自检（DiagnosisActivity）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)

        // 打开主页始终回到顶部：NestedScrollView 会恢复上次滚动位置
        // View.post 接收无参 Runnable，lambda 无隐式 it，用闭包捕获 scrollRoot
        val scrollRoot = findViewById<NestedScrollView>(R.id.scroll_root)
        scrollRoot.post { scrollRoot.scrollTo(0, 0) }

        // 保持经典 HID 前台服务存活（SystemHidActivity/PagerRemoteActivity 绑定的同一服务）；
        // 此处仅 start 不 bind，不做注册/按键
        HidDeviceService.start(this)

        // 模式页入口：仅跳转，不触碰 HID 服务
        findViewById<MaterialButton>(R.id.btn_open_ble).setOnClickListener {
            startActivity(Intent(this, com.hidpager.ble.BlePagerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_open_syshid).setOnClickListener {
            startActivity(Intent(this, com.hidpager.hid.SystemHidActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_open_pager_remote).setOnClickListener {
            startActivity(Intent(this, PagerRemoteActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_open_diagnosis).setOnClickListener {
            startActivity(Intent(this, DiagnosisActivity::class.java))
        }

        // 运行时蓝牙权限（SystemHidActivity 注册/发送与 BLE 页共用），未授予时模式页内再判
        val perm = requiredPermissions()
        if (perm.isNotEmpty() && !hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, perm, REQ_PERM)
        }
    }

    override fun onResume() {
        super.onResume()
        // 状态区仅显示 BLE 广播/连接状态
        tvStatus.text = BlePagerService.status
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQ_PERM = 1001
    }
}
