package com.hidpager

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hidpager.hid.DeviceScanner
import com.hidpager.hid.HidDeviceService
import com.hidpager.hid.HidKeys

class MainActivity : AppCompatActivity() {

    // ---- 视图 ----
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var switchRepeat: SwitchMaterial
    private lateinit var tvRepeatLabel: TextView
    private lateinit var sliderRepeat: Slider
    private lateinit var btnScan: MaterialButton
    private lateinit var recyclerDevices: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    // ---- 核心层 ----
    private var service: HidDeviceService? = null
    private lateinit var scanner: DeviceScanner
    private lateinit var adapter: DeviceAdapter

    private var scanning = false
    private var pendingBondAddress: String? = null

    // ================= 核心层回调 =================

    private val hidListener = object : HidDeviceService.Listener {
        override fun onHidStateChanged(state: Int, device: BluetoothDevice?) {
            runOnUiThread { refreshUi() }
        }

        override fun onLog(line: String) {
            runOnUiThread { log(line) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? HidDeviceService.LocalBinder)?.getService() ?: return
            service = svc
            svc.setListener(hidListener)
            if (!svc.isRegistered()) {
                val ok = svc.registerHid()
                log(
                    if (ok) getString(R.string.log_hid_register_success)
                    else getString(R.string.log_hid_register_failed)
                )
            }
            svc.setRepeatIntervalMs(sliderRepeat.value.toInt())
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshUi()
        }
    }

    private val scanListener = object : DeviceScanner.Listener {
        override fun onDeviceFound(device: BluetoothDevice, rssi: Int, isBle: Boolean) {
            runOnUiThread {
                adapter.addOrUpdate(safeItem(device, isBle))
                tvEmpty.visibility = View.GONE
            }
        }

        override fun onScanFinished() {
            runOnUiThread {
                scanning = false
                btnScan.text = getString(R.string.btn_scan)
                log(getString(R.string.log_scan_finished))
            }
        }

        override fun onLog(line: String) {
            runOnUiThread { log(line) }
        }
    }

    /** 配对状态监听：未配对设备 createBond() 成功后自动 connectDevice */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    log(getString(R.string.log_bond_success, device.address ?: ""))
                    if (pendingBondAddress == device.address) {
                        pendingBondAddress = null
                        service?.connectDevice(device)
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    if (pendingBondAddress == device.address) {
                        pendingBondAddress = null
                        log(getString(R.string.log_bond_failed, device.address ?: ""))
                    }
                }
            }
        }
    }

    // ================= 生命周期 =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnConnect = findViewById(R.id.btn_connect)
        switchRepeat = findViewById(R.id.switch_repeat)
        tvRepeatLabel = findViewById(R.id.tv_repeat_label)
        sliderRepeat = findViewById(R.id.slider_repeat)
        btnScan = findViewById(R.id.btn_scan)
        recyclerDevices = findViewById(R.id.recycler_devices)
        tvEmpty = findViewById(R.id.tv_empty_devices)
        tvLog = findViewById(R.id.tv_log)
        scrollLog = findViewById(R.id.scroll_log)

        adapter = DeviceAdapter { onDeviceConnect(it) }
        recyclerDevices.layoutManager = LinearLayoutManager(this)
        recyclerDevices.adapter = adapter

        scanner = DeviceScanner(this)
        // 与 HidDeviceService.setListener 同构：挂载扫描监听（契约未显式列出，见风险点）
        scanner.setListener(scanListener)

        loadBondedDevices()

        // 遥控按键：单击 sendKey；长按连发由触摸监听驱动
        bindKey(findViewById(R.id.btn_page_up), HidKeys.KEY_PAGE_UP)
        bindKey(findViewById(R.id.btn_page_down), HidKeys.KEY_PAGE_DOWN)
        bindKey(findViewById(R.id.btn_up), HidKeys.KEY_UP)
        bindKey(findViewById(R.id.btn_down), HidKeys.KEY_DOWN)
        bindKey(findViewById(R.id.btn_left), HidKeys.KEY_LEFT)
        bindKey(findViewById(R.id.btn_right), HidKeys.KEY_RIGHT)
        bindKey(findViewById(R.id.btn_enter), HidKeys.KEY_ENTER)
        bindKey(findViewById(R.id.btn_space), HidKeys.KEY_SPACE)

        tvRepeatLabel.text = getString(R.string.label_repeat_interval, sliderRepeat.value.toInt())
        sliderRepeat.addOnChangeListener { _, value, _ ->
            val ms = value.toInt()
            tvRepeatLabel.text = getString(R.string.label_repeat_interval, ms)
            service?.setRepeatIntervalMs(ms)
        }

        btnConnect.setOnClickListener {
            val svc = service
            if (svc != null && svc.getHidState() == 2) {
                svc.disconnectDevice()
                log(getString(R.string.log_disconnected))
            }
        }

        btnScan.setOnClickListener { toggleScan() }
        findViewById<MaterialButton>(R.id.btn_clear_log).setOnClickListener { tvLog.text = "" }
        // Miuix 演示页入口：仅跳转，不触碰 HID 服务
        findViewById<MaterialButton>(R.id.btn_open_miuix).setOnClickListener {
            startActivity(Intent(this, MiuixActivity::class.java))
        }

        // 配对广播（API 34+ 必须显式指定 exported 标志）
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bondReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bondReceiver, filter)
        }

        val perm = requiredPermissions()
        if (perm.isEmpty()) startHidService()
        else ActivityCompat.requestPermissions(this, perm, REQ_PERM)
    }

    override fun onStart() {
        super.onStart()
        // 从后台返回时重新绑定以接收回调（服务本身已由 start() 保持运行）
        if (hasPermissions() && service == null) {
            try {
                bindService(Intent(this, HidDeviceService::class.java),
                    serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                log(getString(R.string.log_error_prefix, e.message))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 仅解绑，不 stopService：已 start 的服务在后台继续运行，支持后台连发
        if (service != null) {
            try { unbindService(serviceConnection) } catch (e: Exception) { /* ignore */ }
            service = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不 stopService()：用户可在后台继续使用翻页服务，由系统按需回收
        try { unregisterReceiver(bondReceiver) } catch (e: Exception) { /* ignore */ }
        if (scanning) {
            try { scanner.stopScan() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ================= 权限 =================

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        permissions.forEachIndexed { i, p ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            log(
                getString(
                    R.string.log_permission_result, p,
                    if (granted) getString(R.string.granted) else getString(R.string.denied)
                )
            )
        }
        // 无论是否全部授予都继续，扫描/按键在真正用到时再判权限
        startHidService()
    }

    // ================= 服务 =================

    private fun startHidService() {
        if (service != null) return
        HidDeviceService.start(this)
        log(getString(R.string.log_service_started))
        try {
            bindService(
                Intent(this, HidDeviceService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            log(getString(R.string.log_error_prefix, e.message))
        }
    }

    // ================= 按键 =================

    private fun bindKey(btn: MaterialButton, usage: Int) {
        btn.setOnClickListener {
            val svc = service
            if (svc == null || svc.getHidState() != 2) {
                log(getString(R.string.log_not_connected))
                return@setOnClickListener
            }
            svc.sendKey(usage)
        }
        btn.setOnTouchListener { _, event ->
            if (!switchRepeat.isChecked) return@setOnTouchListener false
            val svc = service
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (svc != null && svc.getHidState() == 2) svc.startRepeat(usage)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> svc?.stopRepeat()
            }
            false // 返回 false，保证单击事件仍正常派发
        }
    }

    // ================= 设备扫描 / 连接 =================

    private fun safeItem(device: BluetoothDevice, isBle: Boolean): DeviceItem {
        var name = ""
        var bonded = false
        try {
            name = device.name ?: ""
            bonded = device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) { /* 权限未授予时留空 */ }
        return DeviceItem(device, name, device.address ?: "", bonded, isBle)
    }

    private fun loadBondedDevices() {
        try {
            val list = scanner.getBondedDevices().map { safeItem(it, false) }
            adapter.submit(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            log(getString(R.string.log_error_prefix, e.message))
        }
    }

    private fun toggleScan() {
        if (scanning) {
            try { scanner.stopScan() } catch (e: Exception) { /* ignore */ }
            scanning = false
            btnScan.text = getString(R.string.btn_scan)
            log(getString(R.string.log_scan_stopped))
        } else {
            log(getString(R.string.log_scan_started))
            try { scanner.startScan() } catch (e: Exception) {
                log(getString(R.string.log_error_prefix, e.message))
                return
            }
            scanning = true
            btnScan.text = getString(R.string.btn_stop_scan)
        }
    }

    private fun onDeviceConnect(item: DeviceItem) {
        val svc = service
        if (svc == null) {
            log(getString(R.string.log_not_connected))
            return
        }
        try {
            if (item.isBonded) {
                log(getString(R.string.log_connecting, item.name.ifEmpty { item.address }))
                svc.connectDevice(item.device)
            } else {
                // 未配对：先 createBond()，等广播 BOND_BONDED 后再 connectDevice
                log(getString(R.string.log_bonding, item.address))
                pendingBondAddress = item.address
                val ok = item.device.createBond()
                if (!ok) {
                    pendingBondAddress = null
                    log(getString(R.string.log_bond_init_failed))
                }
            }
        } catch (e: Exception) {
            log(getString(R.string.log_error_prefix, e.message))
        }
    }

    // ================= UI 刷新 / 日志 =================

    private fun refreshUi() {
        val svc = service
        val state = svc?.getHidState() ?: 0
        val devName = try {
            svc?.getConnectedDevice()?.name ?: ""
        } catch (e: SecurityException) { "" }
        tvStatus.text = when (state) {
            1 -> getString(R.string.status_connecting)
            2 -> getString(R.string.status_connected, devName)
            else -> getString(R.string.status_unregistered)
        }
        btnConnect.visibility = if (state == 2) View.VISIBLE else View.GONE
    }

    private fun log(line: String) {
        tvLog.append(line + "\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val REQ_PERM = 1001
    }
}
