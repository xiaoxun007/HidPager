package com.hidpager.hid

/**
 * HID Usage 定义与键盘报表构造。
 * 报表格式（8 字节）：[modifier, reserved, key1, key2, key3, key4, key5, key6]
 */
object HidKeys {
    const val KEY_ESCAPE = 0x29
    const val KEY_ENTER = 0x28
    const val KEY_SPACE = 0x2C
    const val KEY_PAGE_UP = 0x4B
    const val KEY_PAGE_DOWN = 0x4E
    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50
    const val KEY_DOWN = 0x51
    const val KEY_UP = 0x52

    /** 中文名 → Usage ID 映射，UI 层可选用 */
    val nameToUsage: Map<String, Int> = mapOf(
        "上一页" to KEY_PAGE_UP,
        "下一页" to KEY_PAGE_DOWN,
        "确认" to KEY_ENTER,
        "空格" to KEY_SPACE,
        "返回" to KEY_ESCAPE,
        "上" to KEY_UP,
        "下" to KEY_DOWN,
        "左" to KEY_LEFT,
        "右" to KEY_RIGHT
    )

    /** 标准 8 字节键盘 HID 报表描述符（>0x7F 的字面量显式 .toByte()，字节值不变） */
    fun buildReportDescriptor(): ByteArray = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x05, 0x07,
        0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01,
        0x75, 0x08, 0x81.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08,
        0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00,
        0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte()
    )

    /** 构造 8 字节键盘报表：[modifier=0, reserved=0, key1..key6] */
    fun makeReport(vararg usages: Int): ByteArray {
        val report = ByteArray(8)
        report[0] = 0 // modifier
        report[1] = 0 // reserved
        usages.forEachIndexed { i, usage ->
            if (i < 6) report[2 + i] = (usage and 0xFF).toByte()
        }
        return report
    }

    /** 按下某个键（保持按下状态，需配合 keyUpReport 抬起） */
    fun keyDownReport(usage: Int): ByteArray = makeReport(usage)

    /** 抬起全部按键（全零报表） */
    fun keyUpReport(): ByteArray = ByteArray(8)
}
