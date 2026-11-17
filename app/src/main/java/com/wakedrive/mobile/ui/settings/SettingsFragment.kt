package com.wakedrive.mobile.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.alarm.AlarmController
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.ui.AdminActivity
import com.wakedrive.mobile.ui.LoginActivity
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch
import org.json.JSONObject

class SettingsFragment : Fragment() {

    private lateinit var fields: Map<String, EditText>
    private lateinit var alarmSwitch: Switch
    private lateinit var styleSpinner: Spinner
    private lateinit var status: android.widget.TextView
    private lateinit var root: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(context.getColor(R.color.bg_deep)) }
        root = Ui.scrollContainer(context)
        scroll.addView(root)

        root.addView(Ui.text(context, "系统设置", 24f, R.color.text_primary, true))
        root.addView(Ui.text(context, "设置保存到当前账号，并与网页端共用。", 12f, R.color.text_muted))

        val card = Ui.card(context)
        val builder = mutableMapOf<String, EditText>()
        fun field(key: String, label: String, value: String, numeric: Boolean = false) {
            card.addView(Ui.text(context, label, 11f, R.color.text_muted))
            val input = EditText(context).apply {
                setText(value)
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            builder[key] = input
            card.addView(input)
        }
        field("driverName", "驾驶员姓名", "")
        field("plateNumber", "车牌号", "")
        field("earThreshold", "EAR 闭眼阈值", "0.25", numeric = true)
        field("marThreshold", "MAR 张嘴阈值", "0.55", numeric = true)
        field("mildFrames", "轻度帧数", "2", numeric = true)
        field("mediumFrames", "中度帧数", "5", numeric = true)
        field("heavyFrames", "重度帧数", "9", numeric = true)
        field("cooldownMs", "告警冷却（毫秒）", "5000", numeric = true)
        fields = builder

        val alarmRow = Ui.row(context)
        alarmRow.addView(Ui.text(context, "启用告警声", 12f, R.color.text_primary))
        alarmSwitch = Switch(context).apply { isChecked = true }
        alarmRow.addView(alarmSwitch)
        card.addView(alarmRow)
        card.addView(Ui.text(context, "告警风格", 11f, R.color.text_muted))
        styleSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("bell", "silent"))
        }
        card.addView(styleSpinner)
        root.addView(card)

        status = Ui.text(context, "", 12f, R.color.text_muted)
        root.addView(status)

        val save = Ui.button(context, "保存设置", primary = true)
        save.setOnClickListener { save() }
        root.addView(save)

        val testRow = Ui.row(context)
        val test = Ui.button(context, "测试警报")
        test.setOnClickListener { testAlarm() }
        testRow.addView(test)
        val server = Ui.button(context, "服务器地址")
        server.setOnClickListener { editServer() }
        testRow.addView(server)
        root.addView(testRow)

        val logout = Ui.button(context, "退出登录")
        logout.setOnClickListener {
            lifecycleScope.launch {
                ApiClient.logout()
                startActivity(Intent(context, LoginActivity::class.java))
                activity?.finish()
            }
        }
        root.addView(logout)

        load()
        return scroll
    }

    private fun load() {
        lifecycleScope.launch {
            val settings = ApiClient.getSettings() ?: run {
                if (isAdded) status.text = "设置加载失败"
                return@launch
            }
            if (!isAdded) return@launch
            fields["driverName"]?.setText(settings.optString("driverName"))
            fields["plateNumber"]?.setText(settings.optString("plateNumber"))
            fields["earThreshold"]?.setText(settings.optDouble("earThreshold", 0.25).toString())
            fields["marThreshold"]?.setText(settings.optDouble("marThreshold", 0.55).toString())
            fields["mildFrames"]?.setText(settings.optInt("mildFrames", 2).toString())
            fields["mediumFrames"]?.setText(settings.optInt("mediumFrames", 5).toString())
            fields["heavyFrames"]?.setText(settings.optInt("heavyFrames", 9).toString())
            fields["cooldownMs"]?.setText(settings.optInt("cooldownMs", 5000).toString())
            alarmSwitch.isChecked = settings.optBoolean("alarmEnabled", true)
            val style = settings.optString("alarmStyle", "bell")
            styleSpinner.setSelection(if (style == "silent") 1 else 0)
            val me = ApiClient.me()
            status.text = "当前账号：${me?.optString("username") ?: "—"}"
            if (me?.optString("role") == "admin" && isAdded) {
                val adminButton = Ui.button(requireContext(), "管理员后台 →")
                adminButton.setOnClickListener { startActivity(Intent(requireContext(), AdminActivity::class.java)) }
                root.addView(adminButton)
            }
        }
    }

    private fun save() {
        lifecycleScope.launch {
            val patch = JSONObject().apply {
                put("driverName", fields["driverName"]?.text?.toString()?.trim().orEmpty())
                put("plateNumber", fields["plateNumber"]?.text?.toString()?.trim().orEmpty())
                fields["earThreshold"]?.text?.toString()?.toDoubleOrNull()?.let { put("earThreshold", it) }
                fields["marThreshold"]?.text?.toString()?.toDoubleOrNull()?.let { put("marThreshold", it) }
                fields["mildFrames"]?.text?.toString()?.toIntOrNull()?.let { put("mildFrames", it) }
                fields["mediumFrames"]?.text?.toString()?.toIntOrNull()?.let { put("mediumFrames", it) }
                fields["heavyFrames"]?.text?.toString()?.toIntOrNull()?.let { put("heavyFrames", it) }
                fields["cooldownMs"]?.text?.toString()?.toIntOrNull()?.let { put("cooldownMs", it) }
                put("alarmEnabled", alarmSwitch.isChecked)
                put("alarmStyle", if (styleSpinner.selectedItemPosition == 1) "silent" else "bell")
            }
            val (ok, message) = ApiClient.putSettings(patch)
            if (isAdded) status.text = if (ok) "设置已保存" else (message ?: "保存失败")
        }
    }

    private fun testAlarm() {
        val alarm = AlarmController(requireContext())
        alarm.configure(alarmSwitch.isChecked, if (styleSpinner.selectedItemPosition == 1) "silent" else "bell", 3000)
        alarm.event("harsh", "测试警报：重度疲劳，请立即停车休息。")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ alarm.close() }, 3500)
    }

    private fun editServer() {
        val context = requireContext()
        val input = EditText(context).apply { setText(ApiClient.baseUrl) }
        AlertDialog.Builder(context)
            .setTitle("后端地址")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                ApiClient.setBaseUrl(input.text?.toString().orEmpty())
                status.text = "服务器：${ApiClient.baseUrl}（需重新登录）"
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
