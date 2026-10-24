package com.hidpager.hid

/**
 * HID Usage 定义与报表构造。
 *
 * 报表格式（带 Report ID）：
 * - 键盘报表（Report ID=0x01）共 9 字节：[0x01, modifier, reserved, key1..key6]
 * - Consumer 媒体报表（Report ID=0x02）共 3 字节：[0x02, usageLo, usageHi]（小端 16 位）
 *
 * Report ID 方案：键盘段与 Consumer 段都带 Report ID（0x01 / 0x02）。
 * 依据：HID 规范要求「若任一报表含 Report ID，则所有报表都必须带」；两个 input 段若都不带 ID，
 * 主机会把它们视为合并的逻辑报表，电纸书类接收端按描述符切分报表极易解析错乱；
 * 成熟样例（如 BleHID Simulator）均按每报表独立 ID，接收端按 Report ID 分发无歧义。
 * 键盘报表由 8 字节变 9 字节（仅在头部加 0x01 前缀），标准主机解析等价，按键语义不变。
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

    // ---- Consumer Control（Usage Page 0x0C）媒体键 Usage ID，>0x7F 发送时按小端拆字节 ----
    /** Consumer Volume Increment（音量+/上一页） */
    const val MEDIA_VOLUME_UP = 0xE9
    /** Consumer Volume Decrement（音量-/下一页） */
    const val MEDIA_VOLUME_DOWN = 0xEA
    /** Consumer Play/Pause（播放暂停/唤菜单） */
    const val MEDIA_PLAY_PAUSE = 0xCD

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

    /**
     * HID 报表描述符：键盘段（Report ID=0x01）+ Consumer 媒体段（Report ID=0x02）。
     * 键盘段字节与历史版本完全一致，仅在 Collection 内新增 Report ID 0x01；>0x7F 字面量显式 .toByte()。
     */
    fun buildReportDescriptor(): ByteArray = byteArrayOf(
        // ===== 键盘段（Usage Page 0x01 Generic Desktop / Usage 0x06 Keyboard）=====
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85, 0x01,             //   Report ID (1) —— 键盘报表前缀
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(),    //   Usage Minimum (0xE0 Left Control)
        0x29, 0xE7.toByte(),    //   Usage Maximum (0xE7 Right GUI)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1 bit)
        0x95.toByte(), 0x08,    //   Report Count (8) —— 8 个修饰键位
        0x81.toByte(), 0x02,    //   Input (Data,Var,Abs)
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x75, 0x08,             //   Report Size (8 bits)
        0x81.toByte(), 0x01,    //   Input (Const) —— reserved 字节
        0x95.toByte(), 0x06,    //   Report Count (6)
        0x75, 0x08,             //   Report Size (8 bits)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x81.toByte(), 0x00,    //   Input (Data,Array) —— 6 个按键位
        0xC0.toByte(),          // End Collection (键盘段)

        // ===== Consumer 媒体段（Usage Page 0x0C Consumer / Usage 0x01 Consumer Control）=====
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85, 0x02,             //   Report ID (2) —— 媒体报表前缀
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0xFF.toByte(), // Logical Maximum (0xFFFF)
        0x19, 0x00,             //   Usage Minimum (0)
        0x2A, 0xFF.toByte(), 0xFF.toByte(), // Usage Maximum (0xFFFF)
        0x75, 0x10,             //   Report Size (16 bits)
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x81.toByte(), 0x00,    //   Input (Data,Array) —— 1 个 16 位 Consumer Usage
        0xC0.toByte()           // End Collection (Consumer 段)
    )

    /** 构造 9 字节键盘报表：[0x01, modifier=0, reserved=0, key1..key6] */
    fun makeReport(vararg usages: Int): ByteArray {
        val report = ByteArray(9)
        report[0] = 0x01.toByte() // Report ID
        report[1] = 0 // modifier
        report[2] = 0 // reserved
        usages.forEachIndexed { i, usage ->
            if (i < 6) report[3 + i] = (usage and 0xFF).toByte()
        }
        return report
    }

    /** 按下某个键（保持按下状态，需配合 keyUpReport 抬起）—— 9 字节，0x01 前缀 */
    fun keyDownReport(usage: Int): ByteArray = makeReport(usage)

    /** 抬起全部按键（9 字节：0x01 + 8 字节全零）—— 键盘模式行为与历史 8 字节全零等价 */
    fun keyUpReport(): ByteArray = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    /** 构造 3 字节 Consumer 报表：[0x02, usageLo, usageHi]（16 位小端） */
    fun makeConsumerReport(usage: Int): ByteArray = byteArrayOf(
        0x02,
        (usage and 0xFF).toByte(),
        ((usage ushr 8) and 0xFF).toByte()
    )

    /** 抬起全部媒体键（3 字节：0x02 + 0x00 0x00） */
    fun consumerUpReport(): ByteArray = byteArrayOf(0x02, 0x00, 0x00)
}
