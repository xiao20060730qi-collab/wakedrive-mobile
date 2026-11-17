package com.wakedrive.mobile.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.wakedrive.mobile.R
import com.wakedrive.mobile.databinding.ItemEventBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EventAdapter(private val items: MutableList<JSONObject> = mutableListOf()) :
    RecyclerView.Adapter<EventAdapter.Holder>() {

    private val typeLabels = mapOf(
        "EYE_CLOSE" to "持续闭眼",
        "YAWN" to "打哈欠",
        "PHONE_USE" to "分心使用手机",
        "HEAD_DOWN" to "低头",
        "HEAD_AWAY" to "视线偏离",
        "FACE_LOST" to "未检测到人脸",
        "DRINK" to "喝水/抬手动作",
    )

    fun submit(list: List<JSONObject>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.binding.root.context
        holder.binding.eventType.text = typeLabels[item.optString("type")] ?: item.optString("type", "疲劳事件")
        val severity = item.optString("severity", "未知")
        holder.binding.eventSeverity.text = severity
        val color = when (severity) {
            "重度疲劳" -> R.color.danger
            "中度疲劳" -> R.color.warning
            else -> R.color.safe
        }
        holder.binding.eventSeverity.setTextColor(ContextCompat.getColor(context, color))
        holder.binding.eventTime.text = formatTime(item.optString("createdAt"))
        holder.binding.eventMessage.text = item.optString("message").ifBlank { "—" }
    }

    private fun formatTime(value: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val date: Date = parser.parse(value.take(19)) ?: return value
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(date)
        } catch (error: Exception) {
            value
        }
    }

    class Holder(val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root)
}
