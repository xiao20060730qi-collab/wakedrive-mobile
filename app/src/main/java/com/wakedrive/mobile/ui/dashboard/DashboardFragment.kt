package com.wakedrive.mobile.ui.dashboard

import android.content.Intent
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
import com.wakedrive.mobile.ui.AdminActivity
import com.wakedrive.mobile.ui.MonitorActivity
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch
import org.json.JSONObject

class DashboardFragment : Fragment() {

    private lateinit var root: LinearLayout
    private lateinit var metricsTotal: android.widget.TextView
    private lateinit var metricsPending: android.widget.TextView
    private lateinit var metricsHeavy: android.widget.TextView
    private lateinit var recentContainer: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        scroll.setBackgroundColor(requireContext().getColor(R.color.bg_deep))
        root = Ui.scrollContainer(context)
        scroll.addView(root)

        root.addView(Ui.text(context, "驾驶工作台", 24f, R.color.text_primary, true))
        root.addView(Ui.text(context, "实时掌握驾驶员、车辆和疲劳风险，让每一次提醒都有依据。", 12f, R.color.text_muted))

        val metrics = Ui.card(context)
        val metricsRow = Ui.row(context)
        fun metric(title: String): android.widget.TextView {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            column.addView(Ui.text(context, title, 11f, R.color.text_muted))
            val value = Ui.text(context, "--", 22f, R.color.text_primary, true)
            column.addView(value)
            metricsRow.addView(column)
            return value
        }
        metricsTotal = metric("今日疲劳事件")
        metricsPending = metric("待处理告警")
        metricsHeavy = metric("今日重度疲劳")
        metrics.addView(Ui.text(context, "当前账号数据", 12f, R.color.text_muted))
        metrics.addView(metricsRow)
        root.addView(metrics)

        val actions = Ui.card(context)
        val start = Ui.button(context, "▶ 开始实时监测", primary = true)
        start.setOnClickListener { startActivity(Intent(context, MonitorActivity::class.java)) }
        actions.addView(start)
        val eventsButton = Ui.button(context, "▤ 处理事件")
        eventsButton.setOnClickListener { (activity as? com.wakedrive.mobile.ui.MainActivity)?.openTab("events") }
        actions.addView(eventsButton)
        root.addView(actions)

        val engineering = Ui.card(context)
        engineering.addView(Ui.text(context, "⚙ 工程模拟演示", 15f, R.color.text_primary, true))
        engineering.addView(Ui.text(context, "无需等待真实疲劳即可演示完整告警链路；事件会保存到当前账号并带「工程模式模拟」标记。", 11f, R.color.text_muted))
        val simulationRow1 = Ui.row(context)
        val simulationRow2 = Ui.row(context)
        fun simButton(label: String, sim: String, row: LinearLayout) {
            val button = Ui.button(context, label)
            button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(context, 8) }
            button.setOnClickListener {
                startActivity(Intent(context, MonitorActivity::class.java).putExtra(MonitorActivity.EXTRA_SIM, sim))
            }
            row.addView(button)
        }
        simButton("模拟开启监测", "start", simulationRow1)
        simButton("重度疲劳演示", "fatigue", simulationRow1)
        simButton("手机分心演示", "phone", simulationRow2)
        simButton("无人脸弹窗演示", "lost", simulationRow2)
        engineering.addView(simulationRow1)
        engineering.addView(simulationRow2)
        root.addView(engineering)

        val recent = Ui.card(context)
        recent.addView(Ui.text(context, "最近事件", 15f, R.color.text_primary, true))
        recentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recent.addView(recentContainer)
        root.addView(recent)

        loadData()
        return scroll
    }

    private fun loadData() {
        lifecycleScope.launch {
            val me = ApiClient.me()
            if (me?.optString("role") == "admin" && isAdded) {
                val context = requireContext()
                val adminCard = Ui.card(context)
                adminCard.addView(Ui.text(context, "管理员后台", 15f, R.color.text_primary, true))
                val adminButton = Ui.button(context, "进入管理员后台 →")
                adminButton.setOnClickListener { startActivity(Intent(context, AdminActivity::class.java)) }
                adminCard.addView(adminButton)
                root.addView(adminCard)
            }
            val summary = ApiClient.summary("today")
            if (summary != null && isAdded) {
                metricsTotal.text = summary.optInt("totalRecords", 0).toString()
                val severity = summary.optJSONObject("severityDistribution") ?: JSONObject()
                val type = summary.optJSONObject("typeDistribution") ?: JSONObject()
                metricsPending.text = (type.optInt("PHONE_USE") + type.optInt("FACE_LOST")).toString()
                metricsHeavy.text = severity.optInt("重度疲劳", 0).toString()
            }
            val (events, error) = ApiClient.fetchEvents(5)
            if (!isAdded) return@launch
            recentContainer.removeAllViews()
            if (events == null) {
                recentContainer.addView(Ui.text(requireContext(), error ?: "加载失败", 12f, R.color.text_muted))
            } else if (events.length() == 0) {
                recentContainer.addView(Ui.text(requireContext(), "暂无事件记录", 12f, R.color.text_muted))
            } else {
                val labels = mapOf(
                    "EYE_CLOSE" to "闭眼", "YAWN" to "打哈欠", "PHONE_USE" to "使用手机",
                    "HEAD_DOWN" to "低头", "HEAD_AWAY" to "视线偏离", "FACE_LOST" to "未检测到人脸", "DRINK" to "喝水/抬手",
                )
                for (i in 0 until events.length()) {
                    val item = events.optJSONObject(i) ?: continue
                    val row = Ui.text(
                        requireContext(),
                        "${labels[item.optString("type")] ?: "事件"} · ${item.optString("severity")} · ${item.optString("createdAt").take(16).replace('T', ' ')}",
                        12f,
                        R.color.text_muted,
                    )
                    recentContainer.addView(row)
                }
            }
        }
    }
}
