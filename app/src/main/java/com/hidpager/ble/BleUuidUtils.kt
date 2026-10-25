package com.hidpager.ble

import android.os.ParcelUuid
import java.util.UUID

/** BLE UUID utilities (short 16-bit style <-> full style). Port of jp.kshoji.blehid.util.BleUuidUtils. */
internal object BleUuidUtils {
    private const val PREFIX = "0000"
    private const val POSTFIX = "-0000-1000-8000-00805F9B34FB"

    fun fromShortValue(uuidShortValue: Int): UUID =
        UUID.fromString(PREFIX + String.format("%04X", uuidShortValue and 0xffff) + POSTFIX)

    /** Full style or short (16bits) style UUID match. */
    fun matches(src: UUID, dst: UUID): Boolean {
        if (isShort(src) || isShort(dst)) {
            val s = src.mostSignificantBits and 0x0000ffff00000000L
            val d = dst.mostSignificantBits and 0x0000ffff00000000L
            return s == d
        }
        return src == dst
    }

    private fun isShort(uuid: UUID): Boolean =
        // 仅 bits 32..47 可非零（等价 Java 的 msb & 0xffff0000ffffffffL == 0）
        (uuid.mostSignificantBits and 0xffffffffL) == 0L &&
            (uuid.mostSignificantBits ushr 48) == 0L &&
            uuid.leastSignificantBits == 0L
}
