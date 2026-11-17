package com.wakedrive.mobile.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.databinding.ActivityAdminBinding
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Mobile administrator console: accounts, events with snapshots, and (xiaoqi only) admin management. */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private var currentAdmin: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)
        binding.adminRefresh.setOnClickListener { load() }
        binding.adminLogoutButton.setOnClickListener {
            lifecycleScope.launch {
                ApiClient.adminLogout()
                finish()
            }
        }
        lifecycleScope.launch {
            currentAdmin = ApiClient.adminMe()
            if (currentAdmin == null) showLoginDialog() else load()
        }
    }

    private fun showLoginDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val username = EditText(this).apply { hint = "管理员账号" }
        val password = EditText(this).apply { hint = "密码"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(username)
        layout.addView(password)
        val dialog = AlertDialog.Builder(this)
            .setTitle("管理员登录")
            .setView(layout)
            .setPositiveButton("登录", null)
            .setNegativeButton("取消") { _, _ -> finish() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) return@setOnClickListener
            lifecycleScope.launch {
                val (ok, message) = ApiClient.adminLogin(name, pass)
                if (ok) {
                    dialog.dismiss()
                    currentAdmin = ApiClient.adminMe()
                    load()
                } else {
                    binding.adminStatus.text = message ?: "管理员登录失败"
                }
            }
        }
    }

    private fun load() {
        binding.adminStatus.text = "加载中…"
        binding.adminContainer.removeAllViews()
        lifecycleScope.launch {
            val me = currentAdmin ?: ApiClient.adminMe()
            currentAdmin = me
            if (me == null) {
                binding.adminStatus.text = "请先登录管理员账号"
                showLoginDialog()
                return@launch
            }
            binding.adminTitle.text = "管理员后台 · ${me.optString("username")}"
            val result = ApiClient.adminUsers(100)
            if (result == null) {
                binding.adminStatus.text = "账户列表加载失败"
                return@launch
            }
            val items = result.optJSONArray("items") ?: JSONArray()
            binding.adminStatus.text = "共 ${items.length()} 个账户 · 点击账户查看疲劳事件"
            for (i in 0 until items.length()) {
                val user = items.optJSONObject(i) ?: continue
                binding.adminContainer.addView(userCard(user, me))
            }
            if (me.optString("username").equals("xiaoqi", ignoreCase = true)) {
                val manage = Ui.button(this@AdminActivity, "⚙ 管理员账号管理（xiaoqi）", primary = true)
                manage.setOnClickListener { showAdminManagement(me) }
                binding.adminContainer.addView(manage)
            }
        }
    }

    private fun userCard(user: JSONObject, me: JSONObject): View {
        val card = Ui.card(this)
        val latest = user.optJSONObject("latest")
        val severity = latest?.optString("severity") ?: "未监测"
        card.addView(Ui.text(this, "${user.optString("username")}  [${user.optString("role", "user")}]", 14f, R.color.text_primary, true))
        card.addView(Ui.text(this, "最新状态：$severity · ID ${user.optLong("id")}", 11f, R.color.text_muted))
        val row = Ui.row(this)
        val events = Ui.button(this, "查看事件")
        events.setOnClickListener { showEvents(user.optLong("id"), user.optString("username")) }
        row.addView(events)
        if (user.optString("role") != "admin") {
            val remove = Ui.button(this, "删除账号")
            remove.setOnClickListener { confirmDeleteUser(user.optLong("id"), user.optString("username")) }
            row.addView(remove)
        }
        card.addView(row)
        return card
    }

    private fun showEvents(userId: Long, username: String) {
        lifecycleScope.launch {
            val result = ApiClient.adminUserEvents(userId)
            if (result == null) {
                binding.adminStatus.text = "事件加载失败"
                return@launch
            }
            val layout = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 12, 40, 0) }
            val scroll = android.widget.ScrollView(this@AdminActivity)
            scroll.addView(layout)
            val items = result.optJSONArray("items") ?: JSONArray()
            val labels = mapOf(
                "EYE_CLOSE" to "闭眼", "YAWN" to "打哈欠", "PHONE_USE" to "使用手机",
                "HEAD_DOWN" to "低头", "HEAD_AWAY" to "视线偏离", "FACE_LOST" to "未检测到人脸", "DRINK" to "喝水/抬手",
            )
            if (items.length() == 0) layout.addView(Ui.text(this@AdminActivity, "该账户暂无疲劳事件", 12f, R.color.text_muted))
            for (i in 0 until minOf(items.length(), 30)) {
                val event = items.optJSONObject(i) ?: continue
                layout.addView(
                    Ui.text(
                        this@AdminActivity,
                        "${labels[event.optString("type")] ?: "事件"} · ${event.optString("severity")} · ${event.optString("createdAt").take(19).replace('T', ' ')}\n${event.optString("message")}",
                        11f,
                        R.color.text_muted,
                    ),
                )
                val snapshot = event.optString("snapshot")
                if (snapshot.startsWith("data:image/")) {
                    try {
                        val raw = Base64.decode(snapshot.substringAfter(","), Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        if (bitmap != null) {
                            val image = ImageView(this@AdminActivity)
                            image.setImageBitmap(bitmap)
                            image.adjustViewBounds = true
                            layout.addView(image, LinearLayout.LayoutParams(Ui.dp(this@AdminActivity, 160), ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                    } catch (error: Exception) {
                        // ignore malformed snapshot
                    }
                }
            }
            AlertDialog.Builder(this@AdminActivity)
                .setTitle("$username 的疲劳事件")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun confirmDeleteUser(userId: Long, username: String) {
        AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("确定删除账号「$username」及其全部疲劳事件吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val (ok, message) = ApiClient.adminDeleteUser(userId)
                    binding.adminStatus.text = if (ok) "已删除账号 $username" else (message ?: "删除失败")
                    if (ok) load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAdminManagement(me: JSONObject) {
        lifecycleScope.launch {
            val (array, error) = ApiClient.adminListAdmins()
            val layout = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 12, 40, 0) }
            if (array == null) {
                layout.addView(Ui.text(this@AdminActivity, error ?: "管理员列表加载失败", 12f, R.color.text_muted))
            } else {
                for (i in 0 until array.length()) {
                    val admin = array.optJSONObject(i) ?: continue
                    val row = Ui.row(this@AdminActivity)
                    val label = Ui.text(
                        this@AdminActivity,
                        admin.optString("username") + if (admin.optLong("id") == me.optLong("id")) "（当前登录）" else "",
                        13f,
                        R.color.text_primary,
                    )
                    label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    row.addView(label)
                    val reset = Ui.button(this@AdminActivity, "改密")
                    reset.setOnClickListener { resetPassword(admin.optLong("id"), admin.optString("username")) }
                    row.addView(reset)
                    if (admin.optLong("id") != me.optLong("id")) {
                        val remove = Ui.button(this@AdminActivity, "删除")
                        remove.setOnClickListener { deleteAdmin(admin.optLong("id"), admin.optString("username")) }
                        row.addView(remove)
                    }
                    layout.addView(row)
                }
            }
            val dialog = AlertDialog.Builder(this@AdminActivity)
                .setTitle("管理员账号管理")
                .setView(layout)
                .setPositiveButton("新增管理员") { _, _ -> createAdmin() }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun resetPassword(adminId: Long, username: String) {
        val input = EditText(this).apply { hint = "新密码（至少 8 位）" }
        AlertDialog.Builder(this)
            .setTitle("重置 $username 的密码")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val password = input.text.toString()
                if (password.length < 8) {
                    binding.adminStatus.text = "密码至少需要 8 位"
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val (ok, message) = ApiClient.adminResetPassword(adminId, password)
                    binding.adminStatus.text = if (ok) "已重置 $username 的密码" else (message ?: "重置失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteAdmin(adminId: Long, username: String) {
        AlertDialog.Builder(this)
            .setTitle("删除管理员")
            .setMessage("确定删除管理员「$username」吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val (ok, message) = ApiClient.adminDeleteAdmin(adminId)
                    binding.adminStatus.text = if (ok) "已删除管理员 $username" else (message ?: "删除失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createAdmin() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val username = EditText(this).apply { hint = "新管理员用户名" }
        val password = EditText(this).apply { hint = "初始密码（至少 8 位）" }
        layout.addView(username)
        layout.addView(password)
        AlertDialog.Builder(this)
            .setTitle("新增管理员")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = username.text.toString().trim()
                val pass = password.text.toString()
                if (name.length < 3 || pass.length < 8) {
                    binding.adminStatus.text = "用户名至少 3 位，密码至少 8 位"
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val (ok, message) = ApiClient.adminCreateAdmin(name, pass)
                    binding.adminStatus.text = if (ok) "已新增管理员 $name" else (message ?: "新增失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applySystemInsets(view: android.view.View) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
    }

}
