package com.hidpager.ble

import android.content.Context
import android.util.Log

/**
 * BLE Consumer Control (page turner) peripheral. Port of validated ConsumerPeripheral.java.
 *
 * Report Map 与载荷长度对齐（修复 A）：
 *   - Report ID = 1（与 Report Reference 描述符一致，修复 B）
 *   - Report Size = 16 bit，Report Count = 1 → 单次报告 2 字节
 *   - notify 载荷就是 2 字节小端消费控制码
 *
 * Usage Page 0x0C (Consumer)，Usage Min/Max 覆盖 0x000–0x3FF：
 *   0xE9 Volume Increment -> 上一页, 0xEA Volume Decrement -> 下一页, 0xCD Play/Pause。
 */
class ConsumerPeripheral(context: Context) :
    HidPeripheral(context.applicationContext, needInputReport = true, needOutputReport = false, needFeatureReport = false, dataSendingRate = 20) {

    companion object {
        const val CONSUMER_INCREMENT = 0xE9 // 上一页
        const val CONSUMER_DECREMENT = 0xEA  // 下一页
        const val CONSUMER_PLAY_PAUSE = 0xCD
    }

    override val reportMap: ByteArray = byteArrayOf(
        usagePageItem(1), 0x0C.toByte(),                          // Usage Page (Consumer)
        usageItem(1), 0x01.toByte(),                              // Usage (Consumer Control)
        collectionItem(1), 0x01.toByte(),                         // Collection (Application)
        reportIdItem(1), 0x01.toByte(),                           // Report ID (1) — 与 0x2908 一致
        logicalMinimumItem(1), 0x00.toByte(),                     // Logical Minimum (0)
        logicalMaximumItem(2), 0xFF.toByte(), 0x03.toByte(),      // Logical Maximum (1023)
        usageMinimumItem(1), 0x00.toByte(),                       // Usage Minimum (0)
        usageMaximumItem(2), 0xFF.toByte(), 0x03.toByte(),        // Usage Maximum (1023)
        reportSizeItem(1), 0x10.toByte(),                         // Report Size (16)
        reportCountItem(1), 0x01.toByte(),                        // Report Count (1) → 2 字节报告
        inputItem(1), 0x00.toByte(),                              // Input (Data,Array,Abs)
        endCollectionItem(0),                                     // End Collection
    )

    /** Send a Consumer Control code as a short press: down [code,00] then release [00,00]. */
    fun sendConsumerPress(code: Int) {
        val down = byteArrayOf((code and 0xff).toByte(), 0x00) // little-endian, 2 字节
        val up = byteArrayOf(0x00, 0x00)
        addInputReport(down)
        addInputReport(up)
        Log.d("ConsumerPeripheral", "sendConsumerPress: 0x${Integer.toHexString(code)}")
    }

    override fun onOutputReport(outputReport: ByteArray) {
        Log.i("ConsumerPeripheral", "onOutputReport: ${outputReport.toList()}")
    }
}
