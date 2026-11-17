package com.wakedrive.mobile.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.util.Ui
import kotlinx.coroutines.launch

class AiFragment : Fragment() {

    private lateinit var feed: LinearLayout
    private lateinit var feedScroll: ScrollView
    private lateinit var input: EditText
    private var busy = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg_deep))
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }
        shell.addView(Ui.text(context, "AI 驾驶分析", 24f, R.color.text_primary, true))
        shell.addView(Ui.text(context, "可回答通用问题；登录后可按需结合当前账号的事件与设置。", 12f, R.color.text_muted))

        feed = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        feedScroll = ScrollView(context)
        feedScroll.addView(feed)
        shell.addView(feedScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addMessage("ai", "你好，我可以回答通用问题，也可以分析驾驶安全。直接输入问题或点「分析当前状态」。")

        val compose = Ui.row(context)
        input = EditText(context).apply { hint = "例如：如何预防疲劳驾驶"; maxLines = 2 }
        input.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val send = Ui.button(context, "发送", primary = true)
        send.setOnClickListener { ask() }
        compose.addView(input)
        compose.addView(send)
        shell.addView(compose)

        val quick = Ui.button(context, "✦ 分析当前状态")
        quick.setOnClickListener {
            input.setText("分析当前驾驶员的疲劳状态并给出建议")
            ask()
        }
        shell.addView(quick)
        return shell
    }

    private fun addMessage(kind: String, text: String) {
        val context = requireContext()
        val bubble = Ui.text(
            context,
            (if (kind == "ai") "WakeDrive AI\n" else "你\n") + text,
            12f,
            if (kind == "ai") R.color.text_primary else R.color.cyan,
        )
        bubble.setBackgroundColor(if (kind == "ai") android.graphics.Color.parseColor("#0D2130") else android.graphics.Color.parseColor("#164D68"))
        bubble.setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        feed.addView(bubble)
        feedScroll.post { feedScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun ask() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || busy) return
        busy = true
        input.setText("")
        addMessage("user", question)
        addMessage("ai", "正在分析…")
        val pending = feed.getChildAt(feed.childCount - 1) as TextView
        lifecycleScope.launch {
            val reply = ApiClient.aiChat(question)
            val message = reply ?: localFallback(question)
            if (isAdded) {
                pending.text = "WakeDrive AI\n" + message
            }
            busy = false
        }
    }

    private suspend fun localFallback(question: String): String {
        val list = ApiClient.fetchEvents(50).first
        val total = list?.length() ?: 0
        if (question.contains("闭眼") || question.contains("眼睛")) {
            var count = 0
            if (list != null) for (i in 0 until list.length()) if (list.optJSONObject(i)?.optString("type") == "EYE_CLOSE") count += 1
            return if (count > 0) "当前记录中检测到 $count 次闭眼事件，持续闭眼时请尽快停车休息。" else "当前记录中没有检测到闭眼事件。"
        }
        if (question.contains("哈欠")) {
            var count = 0
            if (list != null) for (i in 0 until list.length()) if (list.optJSONObject(i)?.optString("type") == "YAWN") count += 1
            return if (count > 0) "当前记录中检测到 $count 次打哈欠事件，短时间连续出现时请尽快休息。" else "当前记录中没有检测到打哈欠事件。"
        }
        if (question.contains("预防") || question.contains("避免")) {
            return "预防疲劳驾驶应先保证睡眠，避免长时间连续驾驶；每两小时停车休息至少 20 分钟；困倦时不要硬撑。"
        }
        val advice = if (total >= 5) "当前账号已有 $total 条事件记录，建议优先复核并在安全地点休息。" else "当前账号事件较少，请保持专注并按时休息。"
        return "在线 AI 暂不可用。$advice"
    }
}
