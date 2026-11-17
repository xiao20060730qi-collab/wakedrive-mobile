package com.wakedrive.mobile.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch
import org.json.JSONObject

class StatsFragment : Fragment() {

    private var range = "7d"
    private lateinit var container: LinearLayout
    private lateinit var status: android.widget.TextView
    private lateinit var buttons: Map<String, android.widget.Button>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(context.getColor(R.color.bg_deep)) }
        val root = Ui.scrollContainer(context)
        scroll.addView(root)

        root.addView(Ui.text(context, "统计分析", 24f, R.color.text_primary, true))
        val ranges = mapOf("today" to "今日", "7d" to "近 7 天", "30d" to "近 30 天")
        val row = Ui.row(context)
        buttons = ranges.map { (key, label) ->
            val button = Ui.button(context, label)
            button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8) }
            button.setOnClickListener { range = key; load() }
            row.addView(button)
            key to button
        }.toMap()
        root.addView(row)
        status = Ui.text(context, "加载中…", 12f, R.color.text_muted)
        root.addView(status)
        this.container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(this.container)
        load()
        return scroll
    }

    private fun load() {
        lifecycleScope.launch {
            val summary = ApiClient.summary(range)
            if (!isAdded) return@launch
            container.removeAllViews()
            if (summary == null) {
                status.text = "统计数据加载失败"
                return@launch
            }
            status.text = "总记录 ${summary.optInt("totalRecords", 0)} 条"
            val context = requireContext()
            val severity = summary.optJSONObject("severityDistribution") ?: JSONObject()
            val type = summary.optJSONObject("typeDistribution") ?: JSONObject()

            val severityCard = Ui.card(context)
            severityCard.addView(Ui.text(context, "风险等级分布", 15f, R.color.text_primary, true))
            listOf("轻度疲劳" to R.color.safe, "中度疲劳" to R.color.warning, "重度疲劳" to R.color.danger).forEach { (label, colorRes) ->
                severityCard.addView(barRow(label, severity.optInt(label, 0), colorRes))
            }
            container.addView(severityCard)

            val typeCard = Ui.card(context)
            typeCard.addView(Ui.text(context, "事件类型分布", 15f, R.color.text_primary, true))
            val labels = mapOf(
                "EYE_CLOSE" to "闭眼", "YAWN" to "打哈欠", "PHONE_USE" to "使用手机",
                "HEAD_DOWN" to "低头", "HEAD_AWAY" to "视线偏离", "FACE_LOST" to "未检测到人脸", "DRINK" to "喝水/抬手",
            )
            labels.forEach { (key, label) ->
                typeCard.addView(barRow(label, type.optInt(key, 0), R.color.cyan))
            }
            container.addView(typeCard)

            val trend = summary.optJSONArray("trend")
            if (trend != null) {
                val trendCard = Ui.card(context)
                trendCard.addView(Ui.text(context, "近 7 天趋势", 15f, R.color.text_primary, true))
                for (i in 0 until trend.length()) {
                    val item = trend.optJSONObject(i) ?: continue
                    trendCard.addView(
                        Ui.text(context, "${item.optString("label")}  ${item.optInt("count")} 条", 12f, R.color.text_muted),
                    )
                }
                container.addView(trendCard)
            }
        }
    }

    private fun barRow(label: String, value: Int, colorRes: Int): View {
        val context = requireContext()
        val row = Ui.row(context)
        val name = Ui.text(context, label, 12f, R.color.text_muted)
        name.layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 96), ViewGroup.LayoutParams.WRAP_CONTENT)
        val progress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = value.coerceAtMost(100)
            progressTintList = android.content.res.ColorStateList.valueOf(context.getColor(colorRes))
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(context, 8), 1f)
        }
        row.addView(name)
        row.addView(progress)
        row.addView(Ui.text(context, value.toString(), 12f, R.color.text_primary))
        return row
    }
}
