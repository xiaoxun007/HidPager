package com.hidpager.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Deque
import java.util.HashMap
import java.util.Queue
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE HID-over-GATT peripheral base. Direct Kotlin port of the validated
 * jp.kshoji.blehid.HidPeripheral (GATT server, CCCD notify, bonding, advertising).
 *
 * 修复要点（HOGP 主机连上即断的根因）：
 *  - A: Report Map Report Size=16/Count=1 → 2 字节，与 notify 载荷一致；
 *  - B: Report 特征带 0x2908 Report Reference（Report ID=1, Input=0x01），属性/描述符完整；
 *  - C: HOGP 强制加密链路，特征用 PERMISSION_READ_ENCRYPTED；bond 状态机正确，不盲目重连；
 *  - D: ADV_IND connectable 广播带 0x1812 与设备名；连接后 requestConnectionPriority HIGH；
 *  - E: 全部 GATT 回调实现正确返回；notify 仅在 CCCD 订阅后发送。
 */
abstract class HidPeripheral(
    context: Context,
    needInputReport: Boolean,
    needOutputReport: Boolean,
    needFeatureReport: Boolean,
    private val dataSendingRate: Int,
) {
    private val tag = javaClass.simpleName

    // ---- HID report item helpers ----
    protected fun inputItem(size: Int): Byte = (0x80 or size).toByte()
    protected fun collectionItem(size: Int): Byte = (0xA0 or size).toByte()
    protected fun endCollectionItem(size: Int): Byte = (0xC0 or size).toByte()
    protected fun usagePageItem(size: Int): Byte = (0x04 or size).toByte()
    protected fun usageItem(size: Int): Byte = (0x08 or size).toByte()
    protected fun usageMinimumItem(size: Int): Byte = (0x18 or size).toByte()
    protected fun usageMaximumItem(size: Int): Byte = (0x28 or size).toByte()
    protected fun logicalMinimumItem(size: Int): Byte = (0x14 or size).toByte()
    protected fun logicalMaximumItem(size: Int): Byte = (0x24 or size).toByte()
    protected fun reportSizeItem(size: Int): Byte = (0x74 or size).toByte()
    protected fun reportCountItem(size: Int): Byte = (0x94 or size).toByte()
    protected fun reportIdItem(size: Int): Byte = (0x85 or size).toByte()

    // ---- GATT UUIDs ----
    private val SERVICE_DEVICE_INFORMATION = BleUuidUtils.fromShortValue(0x180A)
    private val CHAR_MANUFACTURER_NAME = BleUuidUtils.fromShortValue(0x2A29)
    private val CHAR_MODEL_NUMBER = BleUuidUtils.fromShortValue(0x2A24)
    private val CHAR_SERIAL_NUMBER = BleUuidUtils.fromShortValue(0x2A25)

    private val SERVICE_BATTERY = BleUuidUtils.fromShortValue(0x180F)
    private val CHAR_BATTERY_LEVEL = BleUuidUtils.fromShortValue(0x2A19)

    private val SERVICE_BLE_HID = BleUuidUtils.fromShortValue(0x1812)
    private val CHAR_HID_INFORMATION = BleUuidUtils.fromShortValue(0x2A4A)
    private val CHAR_REPORT_MAP = BleUuidUtils.fromShortValue(0x2A4B)
    private val CHAR_HID_CONTROL_POINT = BleUuidUtils.fromShortValue(0x2A4C)
    private val CHAR_REPORT = BleUuidUtils.fromShortValue(0x2A4D)
    private val CHAR_PROTOCOL_MODE = BleUuidUtils.fromShortValue(0x2A4E)

    private val DESC_REPORT_REFERENCE = BleUuidUtils.fromShortValue(0x2908)
    private val DESC_CCCD = BleUuidUtils.fromShortValue(0x2902)

    private val EMPTY_BYTES = ByteArray(0)
    private val RESPONSE_HID_INFORMATION = byteArrayOf(0x11, 0x01, 0x00, 0x03)

    /** Subclass provides the HID report map. */
    protected abstract val reportMap: ByteArray

    private var manufacturer = "hidpager"
    private var deviceName = "X3-Pager"
    private var serialNumber = "12345678"

    private val applicationContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter
    private val advertiser: BluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer?
    private var inputReportCharacteristic: BluetoothGattCharacteristic? = null
    private val devicesMap = HashMap<String, BluetoothDevice>()

    /** 已订阅 CCCD 的设备地址集合——未订阅时不发 notify。 */
    private val subscribedDevices = Collections.synchronizedSet(HashSet<String>())

    private val inputReportQueue: Queue<ByteArray> = ConcurrentLinkedQueue()
    protected fun addInputReport(report: ByteArray) {
        if (report.isNotEmpty()) inputReportQueue.offer(report)
    }

    protected abstract fun onOutputReport(outputReport: ByteArray)

    private val pendingServices: Deque<BluetoothGattService> = ConcurrentLinkedDeque()
    private var serviceBeingAdded: BluetoothGattService? = null
    @Volatile
    private var gattServicesReady = false
    private var advertisingRequested = false
    private var reducedAdvertiseData = false

    /** Connection state callback (mirrors fork's ConnectionStateCallback). */
    fun interface ConnectionStateCallback {
        fun onConnectionStateChanged(device: BluetoothDevice, status: Int, newState: Int)
    }

    @Volatile
    private var connectionStateCallback: ConnectionStateCallback? = null

    fun setConnectionStateCallback(cb: ConnectionStateCallback?) {
        connectionStateCallback = cb
    }

    private fun notifyConnectionState(device: BluetoothDevice, status: Int, newState: Int) {
        connectionStateCallback?.onConnectionStateChanged(device, status, newState)
    }

    // 注意：必须在 init 块之前声明——init 里 openGattServer(this, gattServerCallback) 立即引用它
    private val gattServerCallback = createGattServerCallback()

    init {
        val bluetoothManager =
            applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter
            ?: throw UnsupportedOperationException("Bluetooth is not available.")
        if (!bluetoothAdapter.isEnabled) {
            throw UnsupportedOperationException("Bluetooth is disabled.")
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            throw UnsupportedOperationException("BLE advertising not supported on this device.")
        }
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            ?: throw UnsupportedOperationException("BLE advertising not supported on this device.")

        gattServer = bluetoothManager.openGattServer(applicationContext, gattServerCallback)
            ?: throw UnsupportedOperationException("gattServer is null, check Bluetooth is ON.")

        pendingServices.offerLast(setUpHidService(needInputReport, needOutputReport, needFeatureReport))
        pendingServices.offerLast(setUpDeviceInformationService())
        pendingServices.offerLast(setUpBatteryService())
        handler.post { addNextGattService() }

        // poll queue and notify ~dataSendingRate ms
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val polled = inputReportQueue.poll()
                if (polled != null && inputReportCharacteristic != null) {
                    handler.post { notifyInputReport(polled) }
                }
            }
        }, 0, dataSendingRate.toLong())
    }

    private fun registerDevice(device: BluetoothDevice) {
        synchronized(devicesMap) { devicesMap[device.address] = device }
    }

    private fun notifyInputReport(report: ByteArray) {
        val server = gattServer ?: return
        val characteristic = inputReportCharacteristic ?: return
        characteristic.value = report

        val devices = getDevices()
        if (devices.isEmpty()) {
            Log.w(tag, "Input report dropped: no registered Centrals")
            return
        }
        for (device in devices) {
            // 修复 E：仅向已订阅 CCCD 的设备发 notify
            if (!subscribedDevices.contains(device.address)) {
                Log.d(tag, "notify skipped: $device not subscribed to CCCD")
                continue
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = server.notifyCharacteristicChanged(device, characteristic, false, report)
                    BleDiagnostics.logNotify(status, device.address)
                    if (status != BluetoothStatusCodes.SUCCESS) {
                        Log.w(tag, "notifyCharacteristicChanged failed for ${device.address}, status=$status")
                    }
                } else {
                    val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                    BleDiagnostics.logNotify(
                        if (sent) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        device.address
                    )
                    if (!sent) Log.w(tag, "notifyCharacteristicChanged failed for ${device.address}")
                }
            } catch (e: Throwable) {
                Log.w(tag, "notifyCharacteristicChanged error for ${device.address}", e)
            }
        }
    }

    private fun addNextGattService() {
        val server = gattServer ?: return
        val next = pendingServices.pollFirst()
        if (next == null) {
            gattServicesReady = true
            if (advertisingRequested) startAdvertisingInternal()
            return
        }
        serviceBeingAdded = next
        val accepted = server.addService(next)
        if (!accepted) {
            serviceBeingAdded = null
            pendingServices.offerFirst(next)
            handler.postDelayed({ addNextGattService() }, 100)
        }
    }

    private fun setUpDeviceInformationService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_DEVICE_INFORMATION, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val c1 = BluetoothGattCharacteristic(CHAR_MANUFACTURER_NAME,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        while (!service.addCharacteristic(c1)) Unit
        val c2 = BluetoothGattCharacteristic(CHAR_MODEL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        while (!service.addCharacteristic(c2)) Unit
        val c3 = BluetoothGattCharacteristic(CHAR_SERIAL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        while (!service.addCharacteristic(c3)) Unit
        return service
    }

    private fun setUpBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHAR_BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        val cccd = BluetoothGattDescriptor(DESC_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        characteristic.addDescriptor(cccd)
        while (!service.addCharacteristic(characteristic)) Unit
        return service
    }

    private fun setUpHidService(
        needInput: Boolean, needOutput: Boolean, needFeature: Boolean,
    ): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_BLE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val info = BluetoothGattCharacteristic(CHAR_HID_INFORMATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        while (!service.addCharacteristic(info)) Unit

        val map = BluetoothGattCharacteristic(CHAR_REPORT_MAP,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        while (!service.addCharacteristic(map)) Unit

        val protocolMode = BluetoothGattCharacteristic(CHAR_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED)
        protocolMode.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        protocolMode.value = byteArrayOf(0x01) // Report Protocol
        while (!service.addCharacteristic(protocolMode)) Unit

        val controlPoint = BluetoothGattCharacteristic(CHAR_HID_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED)
        controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        while (!service.addCharacteristic(controlPoint)) Unit

        if (needInput) {
            // 修复 B：Report 特征 = READ + NOTIFY，加密权限；
            //   带 CCCD(0x2902) + Report Reference(0x2908, Report ID=1, Input=0x01)
            val characteristic = BluetoothGattCharacteristic(
                CHAR_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
            )
            val cccd = BluetoothGattDescriptor(DESC_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
            cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            characteristic.addDescriptor(cccd)

            val reportRef = BluetoothGattDescriptor(DESC_REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
            // Report ID=1, Report Type=Input=0x01
            reportRef.value = byteArrayOf(0x01, 0x01)
            characteristic.addDescriptor(reportRef)

            while (!service.addCharacteristic(characteristic)) Unit
            inputReportCharacteristic = characteristic
        }

        if (needOutput) {
            val characteristic = BluetoothGattCharacteristic(
                CHAR_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val reportRef = BluetoothGattDescriptor(DESC_REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
            // Report ID=2, Report Type=Output=0x02
            reportRef.value = byteArrayOf(0x02, 0x02)
            characteristic.addDescriptor(reportRef)
            while (!service.addCharacteristic(characteristic)) Unit
        }

        if (needFeature) {
            val characteristic = BluetoothGattCharacteristic(
                CHAR_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )
            val reportRef = BluetoothGattDescriptor(DESC_REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
            // Report ID=3, Report Type=Feature=0x03
            reportRef.value = byteArrayOf(0x03, 0x03)
            characteristic.addDescriptor(reportRef)
            while (!service.addCharacteristic(characteristic)) Unit
        }
        return service
    }

    fun startAdvertising() {
        handler.post {
            advertisingRequested = true
            reducedAdvertiseData = false
            if (!gattServicesReady) return@post
            startAdvertisingInternal()
        }
    }

    private fun startAdvertisingInternal() {
        try { advertiser.stopAdvertising(advertiseCallback) } catch (_: Exception) {}

        try { bluetoothAdapter.name = deviceName } catch (e: SecurityException) {
            Log.d(tag, "Failed to set adapter name", e)
        }

        val settings = AdvertiseSettings.Builder()
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // 修复 D：ADV_IND 可连接
            .setTimeout(0)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .build()

        // 修复 D：主广播包带 HID Service UUID(0x1812) + 设备名
        val advertiseDataBuilder = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_BLE_HID))
            .setIncludeDeviceName(true)
        val scanResultBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_BATTERY))
            .setIncludeDeviceName(false)

        advertiser.startAdvertising(settings, advertiseDataBuilder.build(), scanResultBuilder.build(), advertiseCallback)
    }

    fun stopAdvertising() {
        handler.post {
            advertisingRequested = false
            try { advertiser.stopAdvertising(advertiseCallback) } catch (_: IllegalStateException) {}
            try {
                val server = gattServer
                if (server != null) {
                    for (device in getDevices()) server.cancelConnection(device)
                    server.close()
                    gattServer = null
                }
            } catch (_: IllegalStateException) {}
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(tag, "Advertising started")
            BleDiagnostics.setAdvertising("广播中（ADV_IND, connectable, 0x1812+name）")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(tag, "Advertising failed: $errorCode")
            BleDiagnostics.setAdvertising("广播失败", errorCode)
            if (!advertisingRequested || errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !reducedAdvertiseData) {
                reducedAdvertiseData = true
                handler.post {
                    if (advertisingRequested && gattServicesReady) startAdvertisingInternal()
                }
                return
            }
            if (errorCode == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ||
                errorCode == ADVERTISE_FAILED_INTERNAL_ERROR
            ) {
                handler.postDelayed({
                    if (advertisingRequested && gattServicesReady) startAdvertisingInternal()
                }, 500)
            }
        }
    }

    private fun getDevices(): Set<BluetoothDevice> {
        synchronized(devicesMap) { return Collections.unmodifiableSet(HashSet(devicesMap.values)) }
    }

    /**
     * 修复 C：注册 Just Works 配对请求接收器，自动确认配对（免弹窗）。
     * 仅在主机发起配对时生效； peripheral 侧不盲目 createBond 死循环。
     */
    private var pairingRequestReceiver: BroadcastReceiver? = null

    private fun ensurePairingRequestReceiver() {
        if (pairingRequestReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Log.d(tag, "ACTION_PAIRING_REQUEST: device=${device?.address}, variant=$variant")
                BleDiagnostics.log("BOND", "配对请求 variant=$variant")
                try {
                    device?.setPairingConfirmation(true)
                    abortBroadcast()
                } catch (e: SecurityException) {
                    Log.w(tag, "setPairingConfirmation failed", e)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            applicationContext.registerReceiver(receiver, filter)
        }
        pairingRequestReceiver = receiver
    }

    private fun unregisterPairingRequestReceiver() {
        pairingRequestReceiver?.let {
            try { applicationContext.unregisterReceiver(it) } catch (_: Exception) {}
        }
        pairingRequestReceiver = null
    }

    private fun createGattServerCallback(): BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(tag, "onConnectionStateChange: status=$status, newState=$newState, bondState=${device.bondState}, addr=${device.address}")
            BleDiagnostics.setConnection(status, newState, device.address)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 修复 C：确保配对请求接收器已注册（Just Works 自动确认）
                    ensurePairingRequestReceiver()
                    when (device.bondState) {
                        BluetoothDevice.BOND_NONE -> {
                            // HOGP 强制加密：主机访问加密特征时协议栈会发起配对。
                            // 这里只监听 bond 状态变化，不主动 createBond（peripheral 侧
                            // 主动 createBond 在部分主机上会死循环/失败）。
                            BleDiagnostics.setBond("未配对，等待主机发起加密连接触发 Just Works")
                            val bondReceiver = object : BroadcastReceiver() {
                                override fun onReceive(context: Context?, intent: Intent?) {
                                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                                    val state = intent.getIntExtra(
                                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                                    when (state) {
                                        BluetoothDevice.BOND_BONDED -> {
                                            try { applicationContext.unregisterReceiver(this) } catch (_: Exception) {}
                                            registerDevice(device)
                                            BleDiagnostics.setBond("已配对")
                                            // 修复 D：连接参数优化
                                            requestConnectionPriorityHigh(device)
                                            Log.d(tag, "bonded successfully: ${device.address}")
                                        }
                                        BluetoothDevice.BOND_BONDING -> {
                                            BleDiagnostics.setBond("配对中…")
                                        }
                                        BluetoothDevice.BOND_NONE -> {
                                            try { applicationContext.unregisterReceiver(this) } catch (_: Exception) {}
                                            BleDiagnostics.setBond("配对失败/被拒绝")
                                            Log.w(tag, "bonding failed/aborted for ${device.address}")
                                        }
                                    }
                                }
                            }
                            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                applicationContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                            } else {
                                @Suppress("UnspecifiedRegisterReceiverFlag")
                                applicationContext.registerReceiver(bondReceiver, filter)
                            }
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            registerDevice(device)
                            BleDiagnostics.setBond("已配对（复用绑定）")
                            requestConnectionPriorityHigh(device)
                        }
                        else -> {
                            registerDevice(device)
                            BleDiagnostics.setBond("bondState=${device.bondState}")
                        }
                    }
                    notifyConnectionState(device, status, newState)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val address = device.address
                    // 修复 C：断开后不自动 gattServer.connect() 重连——这会导致
                    // 主机侧配对失败后进入重连死循环。由主机端决定是否重连。
                    synchronized(devicesMap) { devicesMap.remove(address) }
                    subscribedDevices.remove(address)
                    BleDiagnostics.setBond("-")
                    BleDiagnostics.setCccd(false)
                    notifyConnectionState(device, status, newState)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            val server = gattServer ?: return
            handler.post {
                val uuid = characteristic.uuid
                BleDiagnostics.logGattRead(shortUuid(uuid), device.address)
                when {
                    BleUuidUtils.matches(CHAR_HID_INFORMATION, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, RESPONSE_HID_INFORMATION)
                    BleUuidUtils.matches(CHAR_REPORT_MAP, uuid) -> {
                        val map = reportMap
                        if (offset == 0) {
                            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, map)
                        } else {
                            val remain = map.size - offset
                            if (remain > 0) {
                                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, map.copyOfRange(offset, map.size))
                            } else {
                                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                            }
                        }
                    }
                    BleUuidUtils.matches(CHAR_PROTOCOL_MODE, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                            characteristic.value ?: byteArrayOf(0x01))
                    BleUuidUtils.matches(CHAR_HID_CONTROL_POINT, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0))
                    BleUuidUtils.matches(CHAR_REPORT, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.value ?: EMPTY_BYTES)
                    BleUuidUtils.matches(CHAR_MANUFACTURER_NAME, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, manufacturer.toByteArray(StandardCharsets.UTF_8))
                    BleUuidUtils.matches(CHAR_SERIAL_NUMBER, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, serialNumber.toByteArray(StandardCharsets.UTF_8))
                    BleUuidUtils.matches(CHAR_MODEL_NUMBER, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, deviceName.toByteArray(StandardCharsets.UTF_8))
                    BleUuidUtils.matches(CHAR_BATTERY_LEVEL, uuid) ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0x64))
                    else ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.value)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            val server = gattServer ?: return
            handler.post {
                val uuid = descriptor.uuid
                BleDiagnostics.logGattRead("desc:${shortUuid(uuid)}", device.address)
                when {
                    BleUuidUtils.matches(DESC_REPORT_REFERENCE, uuid) -> {
                        // 修复 B：直接返回描述符初始值（已在 setUpHidService 中按 Report ID 设置）
                        val value = descriptor.value
                        if (value != null && value.size >= 2) {
                            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                        } else {
                            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0x01, 0x01))
                        }
                    }
                    BleUuidUtils.matches(DESC_CCCD, uuid) -> {
                        val value = descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        val payload = if (offset == 0) value else value.copyOfRange(offset, value.size)
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
                    }
                    else ->
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.value)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            val server = gattServer ?: return
            val props = characteristic.properties
            BleDiagnostics.logGattWrite(shortUuid(characteristic.uuid), value, device.address)
            if (BleUuidUtils.matches(CHAR_REPORT, characteristic.uuid) &&
                props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            ) {
                onOutputReport(value)
            } else if (BleUuidUtils.matches(CHAR_PROTOCOL_MODE, characteristic.uuid)) {
                characteristic.value = value
            } else if (BleUuidUtils.matches(CHAR_HID_CONTROL_POINT, characteristic.uuid)) {
                // HID Control Point: 0=Suspend, 1=Exit Suspend；记录即可
                BleDiagnostics.log("HID-CP", "control point = ${value.joinToString(" ") { "%02X".format(it) }}")
            }
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, EMPTY_BYTES)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            BleDiagnostics.logGattWrite("desc:${shortUuid(descriptor.uuid)}", value, device.address)
            descriptor.value = value
            if (BleUuidUtils.matches(DESC_CCCD, descriptor.uuid) &&
                value.size >= 2
            ) {
                val enabled = value[0].toInt() and 0x01 == 0x01
                if (enabled) {
                    subscribedDevices.add(device.address)
                    registerDevice(device)
                    BleDiagnostics.setCccd(true)
                } else {
                    subscribedDevices.remove(device.address)
                    BleDiagnostics.setCccd(false)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, EMPTY_BYTES)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            BleDiagnostics.logNotify(status, device.address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(tag, "onNotificationSent: status=$status for ${device.address}")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val failed = serviceBeingAdded ?: service ?: return
                serviceBeingAdded = null
                pendingServices.offerFirst(failed)
                handler.postDelayed({ addNextGattService() }, 100)
                return
            }
            serviceBeingAdded = null
            handler.post { addNextGattService() }
        }
    }

    /** 修复 D：连接成功后请求高优先级连接间隔（低延迟翻页）。
     *  peripheral 侧通过反射调用 updateConnectionPriority，避免在旧 API 上编译失败。 */
    private fun requestConnectionPriorityHigh(device: BluetoothDevice) {
        try {
            val server = gattServer ?: return
            val method = server.javaClass.getMethod(
                "updateConnectionPriority",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            @Suppress("UNCHECKED_CAST")
            method.invoke(server, device, 1 /* CONNECTION_PRIORITY_HIGH */)
            Log.d(tag, "requestConnectionPriority HIGH for ${device.address}")
        } catch (e: Throwable) {
            // 部分 Android 版本 peripheral 侧不支持主动请求连接参数；
            // 广播已设为 LOW_LATENCY，由主机协商连接间隔即可。
            Log.d(tag, "updateConnectionPriority not available on this platform")
        }
    }

    private fun shortUuid(uuid: UUID): String {
        // 16-bit Bluetooth SIG UUID 简写：格式 0000XXXX-0000-1000-8000-00805F9B34FB
        val base = UUID.fromString("00000000-0000-1000-8000-00805F9B34FB")
        if (uuid.leastSignificantBits == base.leastSignificantBits &&
            (uuid.mostSignificantBits and 0x00000000FFFFFFFFL) == (base.mostSignificantBits and 0x00000000FFFFFFFFL)
        ) {
            val short = ((uuid.mostSignificantBits ushr 32) and 0xFFFFL).toInt()
            return "0x%04X".format(short)
        }
        return uuid.toString()
    }

    fun setDeviceName(newName: String) {
        deviceName = newName
    }

    fun release() {
        unregisterPairingRequestReceiver()
    }
}
