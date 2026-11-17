package com.wakedrive.mobile.ui.models

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.wakedrive.mobile.R
import com.wakedrive.mobile.util.Ui

class ModelsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(context.getColor(R.color.bg_deep)) }
        val root = Ui.scrollContainer(context)
        scroll.addView(root)

        root.addView(Ui.text(context, "模型清单", 24f, R.color.text_primary, true))
        root.addView(Ui.text(context, "全部模型在手机本地推理，画面不上传服务器；事件与抓拍仅在触发时保存到当前账号。", 12f, R.color.text_muted))

        val ready = listOf(
            Triple("MediaPipe Face Landmarker", "人脸关键点 · 眨眼 / 张嘴 blendshape", "本地任务模型 3.8MB"),
            Triple("YOLO11n", "手机检测（COCO 类 67）", "ONNX 10.9MB · ONNX Runtime"),
            Triple("YOLOv8n-pose", "通话 / 喝水抬手姿态", "ONNX 13.5MB · ONNX Runtime"),
            Triple("6DRepNet360", "头部姿态：低头 / 视线偏离", "ONNX 94MB · ONNX Runtime"),
        )
        root.addView(Ui.text(context, "已接入（4）", 15f, R.color.cyan, true))
        ready.forEach { (name, use, detail) ->
            val card = Ui.card(context)
            card.addView(Ui.text(context, name, 15f, R.color.text_primary, true))
            card.addView(Ui.text(context, use, 12f, R.color.text_muted))
            card.addView(Ui.text(context, "$detail · 已接入", 11f, R.color.safe))
            root.addView(card)
        }

        root.addView(Ui.text(context, "待接入（2）", 15f, R.color.text_muted, true))
        listOf(
            "MobileNetV3-Small" to "缺少驾驶任务标注数据，暂不冒险接入",
            "因果 TCN" to "缺少按帧标注的疲劳时序数据，公开权重无法直接用于本任务",
        ).forEach { (name, reason) ->
            val card = Ui.card(context)
            card.addView(Ui.text(context, name, 15f, R.color.text_muted, true))
            card.addView(Ui.text(context, reason, 11f, R.color.text_muted))
            root.addView(card)
        }
        return scroll
    }
}
