package com.wakedrive.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.databinding.ActivityLoginBinding
import com.wakedrive.mobile.net.ApiClient
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ApiClient.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)

        binding.loginButton.setOnClickListener { submit() }
        binding.registerLink.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        binding.adminLoginButton.setOnClickListener { startActivity(Intent(this, AdminActivity::class.java)) }
        binding.serverHint.text = "服务器：${ApiClient.baseUrl}（长按修改）"
        binding.serverHint.setOnLongClickListener {
            editServer()
            true
        }
    }

    private fun editServer() {
        val input = android.widget.EditText(this).apply {
            setText(ApiClient.baseUrl)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("后端地址")
            .setMessage("填写后端 API 根地址，例如 https://wakedrive.xyz/api 或 http://192.168.1.10:8000/api")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                ApiClient.setBaseUrl(input.text?.toString().orEmpty())
                binding.serverHint.text = "服务器：${ApiClient.baseUrl}（长按修改）"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submit() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        binding.errorText.visibility = View.GONE
        if (username.length < 3 || password.isEmpty()) {
            showError("请输入用户名和密码")
            return
        }
        binding.loginButton.isEnabled = false
        binding.loginButton.text = "正在登录…"
        lifecycleScope.launch {
            val (ok, message) = ApiClient.login(username, password)
            binding.loginButton.isEnabled = true
            binding.loginButton.text = getString(R.string.login)
            if (ok) {
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } else {
                showError(message ?: getString(R.string.login_failed))
            }
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
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
