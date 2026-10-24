package com.hidpager

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * 设备列表项数据。name/address/isBonded/isBle 为契约字段，device 供连接使用。
 */
data class DeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val isBonded: Boolean,
    val isBle: Boolean
)

class DeviceAdapter(
    private val onConnectClick: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items: MutableList<DeviceItem> = mutableListOf()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_device_name)
        val mac: TextView = view.findViewById(R.id.tv_device_mac)
        val badge: TextView = view.findViewById(R.id.tv_device_badge)
        val btn: MaterialButton = view.findViewById(R.id.btn_device_connect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.name.text = item.name.ifEmpty { ctx.getString(R.string.device_unknown) }
        holder.mac.text = item.address
        val badges = mutableListOf<String>()
        if (item.isBonded) badges.add(ctx.getString(R.string.badge_bonded))
        badges.add(if (item.isBle) ctx.getString(R.string.badge_ble) else ctx.getString(R.string.badge_br))
        holder.badge.text = badges.joinToString(" · ")
        holder.btn.text = if (item.isBonded) ctx.getString(R.string.btn_connect)
        else ctx.getString(R.string.btn_pair_connect)
        holder.btn.setOnClickListener { onConnectClick(item) }
    }

    override fun getItemCount(): Int = items.size

    /** 整体替换（如已配对列表初始化） */
    fun submit(list: List<DeviceItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 按 MAC 去重：已存在则更新，否则插入 */
    fun addOrUpdate(item: DeviceItem) {
        val idx = items.indexOfFirst { it.address == item.address }
        if (idx >= 0) {
            items[idx] = item
            notifyItemChanged(idx)
        } else {
            items.add(item)
            notifyItemInserted(items.size - 1)
        }
    }

    fun findByAddress(address: String): DeviceItem? =
        items.firstOrNull { it.address == address }

    fun isEmpty(): Boolean = items.isEmpty()
}
