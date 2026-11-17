package com.wakedrive.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.databinding.ActivityRegisterBinding
import com.wakedrive.mobile.net.ApiClient
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)
        binding.registerButton.setOnClickListener { submit() }
        binding.loginLink.setOnClickListener { finish() }
    }

    private fun submit() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        binding.errorText.visibility = View.GONE
        if (username.length < 3) {
            showError("用户名至少需要 3 位")
            return
        }
        if (password.length < 8) {
            showError("密码至少需要 8 位")
            return
        }
        binding.registerButton.isEnabled = false
        binding.registerButton.text = "正在注册…"
        lifecycleScope.launch {
            val (ok, message) = ApiClient.register(username, password)
            if (ok) {
                startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                finish()
            } else {
                binding.registerButton.isEnabled = true
                binding.registerButton.text = "注册并登录"
                showError(message ?: "注册失败")
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
