package com.wakedrive.mobile.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.wakedrive.mobile.R
import com.wakedrive.mobile.databinding.ActivityMainBinding
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.ui.ai.AiFragment
import com.wakedrive.mobile.ui.dashboard.DashboardFragment
import com.wakedrive.mobile.ui.events.EventsFragment
import com.wakedrive.mobile.ui.models.ModelsFragment
import com.wakedrive.mobile.ui.reports.ReportsFragment
import com.wakedrive.mobile.ui.settings.SettingsFragment
import com.wakedrive.mobile.ui.stats.StatsFragment
import com.wakedrive.mobile.util.Ui

class MainActivity : AppCompatActivity() {

    private data class TabSpec(val key: String, val label: String, val icon: String)
    private data class TabView(val container: LinearLayout, val icon: TextView, val label: TextView)

    private lateinit var binding: ActivityMainBinding
    private val tabs = listOf(
        TabSpec("dashboard", "工作台", "⌂"),
        TabSpec("monitor", "监测", "◉"),
        TabSpec("models", "模型", "◇"),
        TabSpec("events", "事件", "▤"),
        TabSpec("reports", "报告", "▧"),
        TabSpec("stats", "统计", "▥"),
        TabSpec("ai", "AI", "✦"),
        TabSpec("settings", "设置", "⚙"),
    )
    private val tabViews = mutableMapOf<String, TabView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ApiClient.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)
        buildTabs()
        show("dashboard")
    }

    private fun buildTabs() {
        tabs.forEach { spec ->
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 4))
                isClickable = true
                setOnClickListener { onTab(spec.key) }
            }
            val icon = TextView(this).apply {
                text = spec.icon
                textSize = 16f
                gravity = Gravity.CENTER
            }
            val label = TextView(this).apply {
                text = spec.label
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 2), 0, Ui.dp(this@MainActivity, 2))
            }
            container.addView(icon)
            container.addView(label)
            tabViews[spec.key] = TabView(container, icon, label)
            binding.tabBar.addView(container)
        }
    }

    fun openTab(key: String) = onTab(key)

    private fun onTab(key: String) {
        if (key == "monitor") {
            startActivity(Intent(this, MonitorActivity::class.java))
            return
        }
        show(key)
    }

    private fun show(key: String) {
        tabViews.forEach { (name, view) ->
            val active = name == key
            val color = ContextCompat.getColor(this, if (active) R.color.cyan else R.color.text_muted)
            view.icon.setTextColor(color)
            view.label.setTextColor(color)
            view.label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.container.background = if (active) {
                GradientDrawable().apply {
                    cornerRadius = Ui.dp(this@MainActivity, 999).toFloat()
                    setColor(Color.parseColor("#14384A"))
                    setStroke(Ui.dp(this@MainActivity, 1), Color.parseColor("#2F6E86"))
                }
            } else {
                null
            }
        }
        val fragment: Fragment = when (key) {
            "models" -> ModelsFragment()
            "events" -> EventsFragment()
            "reports" -> ReportsFragment()
            "stats" -> StatsFragment()
            "ai" -> AiFragment()
            "settings" -> SettingsFragment()
            else -> DashboardFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.shellContent, fragment)
            .commit()
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
