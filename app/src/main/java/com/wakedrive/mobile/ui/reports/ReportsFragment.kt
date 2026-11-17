package com.wakedrive.mobile.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch
import org.json.JSONObject

class ReportsFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private lateinit var status: android.widget.TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(context.getColor(R.color.bg_deep)) }
        val root = Ui.scrollContainer(context)
        scroll.addView(root)

        root.addView(Ui.text(context, "监测报告", 24f, R.color.text_primary, true))
        root.addView(Ui.text(context, "每次结束监测都会生成一份报告：风险曲线、事件与覆盖时长。", 12f, R.color.text_muted))
        status = Ui.text(context, "加载中…", 12f, R.color.text_muted)
        root.addView(status)
        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        load()
        return scroll
    }

    private fun load() {
        status.text = "加载中…"
        lifecycleScope.launch {
            val (array, error) = ApiClient.fetchReports()
            if (!isAdded) return@launch
            listContainer.removeAllViews()
            if (array == null) {
                status.text = error ?: "报告加载失败"
                return@launch
            }
            status.text = if (array.length() == 0) "暂无报告，结束一次监测后自动生成" else "共 ${array.length()} 份报告"
            for (i in 0 until array.length()) {
                val report = array.optJSONObject(i) ?: continue
                listContainer.addView(reportCard(report))
            }
        }
    }

    private fun reportCard(report: JSONObject): View {
        val context = requireContext()
        val card = Ui.card(context)
        card.addView(Ui.text(context, report.optString("title", "监测报告"), 15f, R.color.text_primary, true))
        val summary = report.optJSONObject("summary") ?: JSONObject()
        val duration = summary.optLong("durationMs")
        card.addView(
            Ui.text(
                context,
                "开始：${report.optString("startedAt").take(19).replace('T', ' ')}\n覆盖：${duration / 60000} 分 ${(duration / 1000) % 60} 秒 · 事件 ${summary.optInt("eventCount")} 个 · 峰值 ${summary.optInt("peakScore", 0)}",
                12f,
                R.color.text_muted,
            ),
        )
        card.setOnClickListener { openDetail(report.optString("id")) }
        return card
    }

    private fun openDetail(id: String) {
        lifecycleScope.launch {
            val report = ApiClient.fetchReport(id) ?: run {
                status.text = "报告详情加载失败"
                return@launch
            }
            if (!isAdded) return@launch
            val context = requireContext()
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
            val curve = RiskCurveView(context)
            curve.setSamples(report.optJSONArray("samples"))
            layout.addView(curve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 160)))
            val summary = report.optJSONObject("summary") ?: JSONObject()
            layout.addView(
                Ui.text(
                    context,
                    "峰值 ${summary.optInt("peakScore", 0)} · 事件 ${summary.optInt("eventCount")} · 覆盖率 ${"%.0f".format(summary.optDouble("coverage", 0.0) * 100)}%",
                    12f,
                    R.color.text_muted,
                ),
            )
            val severity = summary.optJSONObject("severityCounts") ?: JSONObject()
            layout.addView(Ui.text(context, "轻度 ${severity.optInt("mild")} · 中度 ${severity.optInt("medium")} · 重度 ${severity.optInt("high")}", 12f, R.color.text_muted))
            val events = report.optJSONArray("events")
            if (events != null && events.length() > 0) {
                val labels = mapOf(
                    "EYE_CLOSE" to "闭眼", "YAWN" to "打哈欠", "PHONE_USE" to "使用手机",
                    "HEAD_DOWN" to "低头", "HEAD_AWAY" to "视线偏离", "FACE_LOST" to "未检测到人脸", "DRINK" to "喝水/抬手",
                )
                for (i in 0 until minOf(events.length(), 10)) {
                    val event = events.optJSONObject(i) ?: continue
                    layout.addView(
                        Ui.text(
                            context,
                            "${event.optLong("offsetMs") / 1000}s · ${labels[event.optString("type")] ?: "事件"} · ${event.optString("severity")}",
                            11f,
                            R.color.text_muted,
                        ),
                    )
                }
            }
            AlertDialog.Builder(context)
                .setTitle("报告详情")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .show()
        }
    }
}
