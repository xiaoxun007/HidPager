package com.hidpager.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.hidpager.R

/**
 * BLE HOGP page-turner UI: start/stop advertising, page up/down, play/pause, re-pair,
 * live status. Port of the fork's sample MainActivity; the classic BluetoothHidDevice
 * path on this device is unavailable (see DiagnosisActivity).
 */
class BlePagerActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var tvLastSent: TextView
    private val uiHandler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_pager)
        title = getString(R.string.ble_title)

        tvStatus = findViewById(R.id.ble_tv_status)
        tvLastSent = findViewById(R.id.ble_tv_last_sent)
        btnToggle = findViewById(R.id.ble_btn_toggle)

        btnToggle.setOnClickListener { toggleAdvertising() }
        findViewById<MaterialButton>(R.id.ble_btn_page_up).setOnClickListener {
            sendCode(ConsumerPeripheral.CONSUMER_INCREMENT, getString(R.string.ble_label_page_up))
        }
        findViewById<MaterialButton>(R.id.ble_btn_page_down).setOnClickListener {
            sendCode(ConsumerPeripheral.CONSUMER_DECREMENT, getString(R.string.ble_label_page_down))
        }
        findViewById<MaterialButton>(R.id.ble_btn_play_pause).setOnClickListener {
            sendCode(ConsumerPeripheral.CONSUMER_PLAY_PAUSE, getString(R.string.ble_label_play_pause))
        }
        findViewById<MaterialButton>(R.id.ble_btn_repair).setOnClickListener { rePair() }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissions()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    // ================= 权限（按 API 级别） =================

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        val needed = requiredPermissions()
        if (needed.isNotEmpty() && !hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, needed, REQ_BLE_PERM)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERM && !hasAllPermissions()) {
            Toast.makeText(this, R.string.ble_toast_permission_missing, Toast.LENGTH_LONG).show()
        }
    }

    // ================= 操作 =================

    private fun toggleAdvertising() {
        if (!hasAllPermissions()) {
            ensurePermissions()
            Toast.makeText(this, R.string.ble_toast_permission_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (BlePagerService.peripheral != null) {
            BlePagerService.stop(this)
            Toast.makeText(this, R.string.ble_toast_stopped, Toast.LENGTH_SHORT).show()
        } else {
            BlePagerService.start(this)
            Toast.makeText(this, R.string.ble_toast_started, Toast.LENGTH_SHORT).show()
        }
        refreshUi()
    }

    private fun sendCode(code: Int, label: String) {
        val p = BlePagerService.peripheral
        if (p == null) {
            Toast.makeText(this, R.string.ble_toast_not_advertising, Toast.LENGTH_SHORT).show()
            return
        }
        p.sendConsumerPress(code)
        tvLastSent.text = getString(R.string.ble_last_sent, label, "0x${Integer.toHexString(code).uppercase()}")
    }

    private fun rePair() {
        val device = BlePagerService.lastDevice
        if (device != null) {
            try {
                device.createBond()
                Toast.makeText(this, getString(R.string.ble_toast_repair, device.address ?: ""), Toast.LENGTH_LONG).show()
            } catch (e: SecurityException) {
                Toast.makeText(this, getString(R.string.ble_toast_no_pair_perm, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
            return
        }
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter != null) {
            try {
                for (d: BluetoothDevice in adapter.bondedDevices) {
                    try {
                        d.createBond()
                        Toast.makeText(this, getString(R.string.ble_toast_repair, d.address ?: ""), Toast.LENGTH_LONG).show()
                        return
                    } catch (_: SecurityException) {}
                }
            } catch (_: SecurityException) {}
        }
        Toast.makeText(this, R.string.ble_toast_no_device, Toast.LENGTH_LONG).show()
    }

    private fun refreshUi() {
        val advertising = BlePagerService.peripheral != null
        btnToggle.text = getString(if (advertising) R.string.ble_btn_stop else R.string.ble_btn_start)
        tvStatus.text = BlePagerService.status
    }

    companion object {
        private const val REQ_BLE_PERM = 0xB1E2
    }
}
