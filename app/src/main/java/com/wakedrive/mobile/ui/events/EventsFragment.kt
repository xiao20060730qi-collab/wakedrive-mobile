package com.wakedrive.mobile.ui.events

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class EventsFragment : Fragment() {

    private val severityOptions = listOf("全部等级" to "", "轻度疲劳" to "轻度疲劳", "中度疲劳" to "中度疲劳", "重度疲劳" to "重度疲劳")
    private val typeOptions = listOf(
        "全部类型" to "", "闭眼" to "EYE_CLOSE", "打哈欠" to "YAWN", "使用手机" to "PHONE_USE",
        "低头" to "HEAD_DOWN", "视线偏离" to "HEAD_AWAY", "未检测到人脸" to "FACE_LOST", "喝水/抬手" to "DRINK",
    )
    private lateinit var listContainer: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var severitySpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private var items: JSONArray = JSONArray()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(context.getColor(R.color.bg_deep)) }
        val root = Ui.scrollContainer(context)
        scroll.addView(root)

        root.addView(Ui.text(context, "事件记录", 24f, R.color.text_primary, true))
        root.addView(Ui.text(context, "查看、筛选和处理当前账号的疲劳事件（含抓拍图片）", 12f, R.color.text_muted))

        val filterCard = Ui.card(context)
        severitySpinner = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, severityOptions.map { it.first }) }
        typeSpinner = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, typeOptions.map { it.first }) }
        filterCard.addView(severitySpinner)
        filterCard.addView(typeSpinner)
        val filterRow = Ui.row(context)
        val apply = Ui.button(context, "筛选", primary = true)
        apply.setOnClickListener { load() }
        val export = Ui.button(context, "导出 CSV")
        export.setOnClickListener { exportCsv() }
        val clear = Ui.button(context, "清空全部")
        clear.setOnClickListener { confirmClear() }
        filterRow.addView(apply)
        filterRow.addView(export)
        filterRow.addView(clear)
        filterCard.addView(filterRow)
        root.addView(filterCard)

        status = Ui.text(context, "加载中…", 12f, R.color.text_muted)
        root.addView(status)
        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        load()
        return scroll
    }

    private fun load() {
        val severity = severityOptions[severitySpinner.selectedItemPosition].second
        val type = typeOptions[typeSpinner.selectedItemPosition].second
        status.text = "加载中…"
        lifecycleScope.launch {
            val (array, error) = ApiClient.fetchEvents(100, severity, type)
            if (!isAdded) return@launch
            listContainer.removeAllViews()
            if (array == null) {
                status.text = error ?: "加载失败"
                return@launch
            }
            items = array
            status.text = if (array.length() == 0) "暂无符合条件的事件" else "共 ${array.length()} 条事件"
            if (array.length() == 0) return@launch
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                listContainer.addView(eventCard(item))
            }
        }
    }

    private fun eventCard(item: JSONObject): View {
        val context = requireContext()
        val card = Ui.card(context)
        val row = Ui.row(context)
        val title = Ui.text(context, typeLabel(item.optString("type")), 15f, R.color.text_primary, true)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(title)
        val severity = item.optString("severity")
        val severityView = Ui.text(
            context, severity, 12f,
            when (severity) {
                "重度疲劳" -> R.color.danger
                "中度疲劳" -> R.color.warning
                else -> R.color.safe
            },
            true,
        )
        row.addView(severityView)
        card.addView(row)
        card.addView(Ui.text(context, item.optString("createdAt").take(19).replace('T', ' '), 11f, R.color.text_muted))
        card.addView(Ui.text(context, item.optString("message").ifBlank { "—" }, 12f, R.color.text_muted))
        card.setOnClickListener { showDetail(item) }
        return card
    }

    private fun showDetail(item: JSONObject) {
        val context = requireContext()
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        layout.addView(Ui.text(context, item.optString("message").ifBlank { "—" }, 13f, R.color.text_primary))
        layout.addView(Ui.text(context, "发生时间：${item.optString("createdAt").take(19).replace('T', ' ')}", 12f, R.color.text_muted))
        layout.addView(Ui.text(context, "状态：${item.optString("status", "待处理")} · EAR ${"%.2f".format(item.optDouble("earValue", 0.0))} · MAR ${"%.2f".format(item.optDouble("marValue", 0.0))}", 12f, R.color.text_muted))
        val snapshot = item.optString("snapshot")
        if (snapshot.startsWith("data:image/")) {
            try {
                val raw = Base64.decode(snapshot.substringAfter(","), Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bitmap != null) {
                    val image = ImageView(context)
                    image.setImageBitmap(bitmap)
                    image.adjustViewBounds = true
                    layout.addView(image)
                }
            } catch (error: Exception) {
                // Ignore malformed snapshots.
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(typeLabel(item.optString("type")))
            .setView(layout)
            .setPositiveButton("标记已处理") { _, _ ->
                val id = item.optString("id")
                lifecycleScope.launch {
                    if (ApiClient.acknowledgeEvent(id)) load()
                }
            }
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空事件")
            .setMessage("确定删除当前账号的全部事件记录吗？此操作无法撤销。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    if (ApiClient.clearEvents()) load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCsv() {
        if (items.length() == 0) {
            status.text = "暂无可导出的事件"
            return
        }
        val builder = StringBuilder("发生时间,事件类型,风险等级,EAR,MAR,处理状态,说明\n")
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            builder.append(listOf(
                item.optString("createdAt"),
                typeLabel(item.optString("type")),
                item.optString("severity"),
                item.optString("earValue"),
                item.optString("marValue"),
                item.optString("status"),
                item.optString("message").replace(",", "，"),
            ).joinToString(",")).append('\n')
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "WakeDrive 事件导出")
            putExtra(Intent.EXTRA_TEXT, builder.toString())
        }
        startActivity(Intent.createChooser(intent, "导出事件 CSV"))
    }

    private fun typeLabel(type: String): String = mapOf(
        "EYE_CLOSE" to "持续闭眼", "YAWN" to "打哈欠", "PHONE_USE" to "分心使用手机",
        "HEAD_DOWN" to "低头", "HEAD_AWAY" to "视线偏离", "FACE_LOST" to "未检测到人脸", "DRINK" to "喝水/抬手动作",
    )[type] ?: "疲劳事件"
}
