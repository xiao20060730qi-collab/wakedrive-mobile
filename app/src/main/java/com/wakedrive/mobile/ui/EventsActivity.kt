package com.wakedrive.mobile.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wakedrive.mobile.databinding.ActivityEventsBinding
import com.wakedrive.mobile.net.ApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class EventsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventsBinding
    private val adapter = EventAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)
        binding.eventsList.layoutManager = LinearLayoutManager(this)
        binding.eventsList.adapter = adapter
        binding.refreshButton.setOnClickListener { load() }
        load()
    }

    private fun load() {
        binding.refreshButton.isEnabled = false
        binding.eventsStatus.visibility = View.VISIBLE
        binding.eventsStatus.text = "加载中…"
        lifecycleScope.launch {
            val (array, error) = ApiClient.fetchEvents(50)
            binding.refreshButton.isEnabled = true
            if (array == null) {
                binding.eventsStatus.text = error ?: "加载失败"
                return@launch
            }
            val list = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { list.add(it) }
            }
            adapter.submit(list)
            binding.eventsStatus.text = if (list.isEmpty()) "暂无事件记录" else "共 ${list.size} 条事件"
            binding.eventsStatus.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
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
