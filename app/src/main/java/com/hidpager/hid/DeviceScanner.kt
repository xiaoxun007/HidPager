package com.hidpager.hid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/**
 * 并行扫描经典蓝牙设备与 BLE 设备，按 MAC 地址去重后回调（同一设备只上报一次，isBle 标记来源）。
 */
class DeviceScanner(context: Context) {

    interface Listener {
        fun onDeviceFound(device: BluetoothDevice, rssi: Int, isBle: Boolean)
        fun onScanFinished()
        fun onLog(line: String)
    }

    private val appContext: Context = context.applicationContext
    private var listener: Listener? = null

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** 已上报地址 → 是否来自 BLE */
    private val seen = HashMap<String, Boolean>()

    @Volatile private var scanning = false
    @Volatile private var classicFinished = true
    @Volatile private var bleFinished = true

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    private fun log(line: String) {
        listener?.onLog(line)
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED &&
                appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun startScan() {
        stopScanInternal()
        if (adapter == null) {
            log("设备不支持蓝牙")
            listener?.onScanFinished()
            return
        }
        if (!adapter.isEnabled) {
            log("蓝牙未开启，请先打开蓝牙")
            listener?.onScanFinished()
            return
        }
        if (!hasScanPermission()) {
            log("缺少蓝牙扫描权限（Android 12+ 需 BLUETOOTH_SCAN，旧版需 BLUETOOTH_ADMIN + 定位）")
            listener?.onScanFinished()
            return
        }

        synchronized(seen) { seen.clear() }
        scanning = true
        classicFinished = false
        bleFinished = false

        // 经典蓝牙发现
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                appContext.registerReceiver(
                    classicReceiver,
                    IntentFilter(BluetoothDevice.ACTION_FOUND),
                    Context.RECEIVER_NOT_EXPORTED
                )
                appContext.registerReceiver(
                    classicReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext.registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
                appContext.registerReceiver(
                    classicReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                )
            }
            val started = adapter.startDiscovery()
            log("经典蓝牙 discovery 启动: $started")
            if (!started) {
                classicFinished = true
                tryFinish()
            }
        } catch (e: SecurityException) {
            log("启动经典扫描权限不足: ${e.message}")
            classicFinished = true
            tryFinish()
        }

        // BLE 扫描
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                bleFinished = true
                tryFinish()
            } else {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, leScanCallback)
                log("BLE 扫描已启动")
            }
        } catch (e: SecurityException) {
            log("启动 BLE 扫描权限不足: ${e.message}")
            bleFinished = true
            tryFinish()
        }
    }

    fun stopScan() {
        stopScanInternal()
        tryFinish()
    }

    private fun stopScanInternal() {
        scanning = false
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        try {
            adapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: Exception) {
        }
        try {
            appContext.unregisterReceiver(classicReceiver)
        } catch (_: IllegalArgumentException) {
        }
        classicFinished = true
        bleFinished = true
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            log("获取已配对设备权限不足: ${e.message}")
            emptyList()
        }
    }

    // ---------------- 内部 ----------------

    /** 经典 discovery 与 BLE 扫描都结束时回调 onScanFinished */
    private fun tryFinish() {
        if (classicFinished && bleFinished && !scanning) {
            listener?.onScanFinished()
        }
    }

    private fun reportDevice(device: BluetoothDevice, rssi: Int, isBle: Boolean) {
        val addr = device.address ?: return
        val first: Boolean
        synchronized(seen) {
            first = !seen.containsKey(addr)
            if (first) seen[addr] = isBle
        }
        if (first) {
            listener?.onDeviceFound(device, rssi, isBle)
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (device != null) reportDevice(device, rssi, isBle = false)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    classicFinished = true
                    // 经典 discovery 自然结束即视为整体扫描结束，顺带停掉 BLE 扫描
                    try {
                        adapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                    } catch (_: Exception) {
                    }
                    bleFinished = true
                    log("经典蓝牙 discovery 结束")
                    tryFinish()
                }
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            reportDevice(device, result.rssi, isBle = true)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { reportDevice(it.device, it.rssi, isBle = true) }
        }

        override fun onScanFailed(errorCode: Int) {
            bleFinished = true
            log("BLE 扫描失败 errorCode=$errorCode")
            tryFinish()
        }
    }
}
