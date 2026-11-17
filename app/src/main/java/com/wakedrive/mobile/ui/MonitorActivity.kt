package com.wakedrive.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wakedrive.mobile.R
import com.wakedrive.mobile.alarm.AlarmController
import com.wakedrive.mobile.databinding.ActivityMonitorBinding
import com.wakedrive.mobile.net.ApiClient
import com.wakedrive.mobile.risk.RiskCoordinator
import com.wakedrive.mobile.risk.RiskResult
import com.wakedrive.mobile.risk.SessionRecorder
import com.wakedrive.mobile.risk.TemporalRiskTracker
import com.wakedrive.mobile.speed.SpeedMonitor
import com.wakedrive.mobile.util.BitmapUtils
import com.wakedrive.mobile.vision.FaceAnalyzer
import com.wakedrive.mobile.vision.FaceMetrics
import com.wakedrive.mobile.vision.HeadPose
import com.wakedrive.mobile.vision.HeadPoseEstimator
import com.wakedrive.mobile.vision.PhoneBox
import com.wakedrive.mobile.vision.PhoneDetector
import com.wakedrive.mobile.vision.PoseDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class MonitorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SIM = "sim"
        private const val FACE_INTERVAL_MS = 300L
        private const val DET_INTERVAL_MS = 900L
        private const val POSE_INTERVAL_MS = 1200L
        private const val HEAD_INTERVAL_MS = 700L
        private const val FRAME_MIN_GAP_MS = 100L
        private const val CALL_SUSTAIN_MS = 1800L
        private const val CALL_COOLDOWN_MS = 12_000L
        private const val DRINK_SUSTAIN_MS = 1500L
        private const val DRINK_COOLDOWN_MS = 25_000L
        private const val HEAD_SUSTAIN_MS = 2200L
        private const val HEAD_COOLDOWN_MS = 12_000L
        private const val FACE_LOST_REMIND_MS = 12_000L
        private const val FACE_LOST_REMIND_REPEAT_MS = 30_000L
        private const val FACE_LOST_LONG_MS = 40_000L
        private const val REST_STOP_COOLDOWN_MS = 600_000L

        private val TYPE_LABELS = mapOf(
            "EYE_CLOSE" to "闭上眼",
            "YAWN" to "打哈欠",
            "PHONE_USE" to "使用手机",
            "HEAD_DOWN" to "低头",
            "HEAD_AWAY" to "视线偏离",
            "FACE_LOST" to "未检测到人脸",
            "DRINK" to "喝水/抬手",
        )
    }

    private lateinit var binding: ActivityMonitorBinding
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var faceAnalyzer: FaceAnalyzer? = null
    private var phoneDetector: PhoneDetector? = null
    private var poseDetector: PoseDetector? = null
    private var headEstimator: HeadPoseEstimator? = null
    private var alarm: AlarmController? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var tracker = TemporalRiskTracker()
    private val coordinator = RiskCoordinator()
    private var settingsJson: JSONObject? = null

    @Volatile private var running = false
    @Volatile private var latestBitmap: android.graphics.Bitmap? = null
    @Volatile private var latestFace: FaceMetrics? = null
    private var lastFrameAt = 0L
    private var lastFaceRunAt = 0L
    private var lastDetAt = 0L
    private var lastPoseAt = 0L
    private var lastHeadAt = 0L
    private var detRuns = 0
    private var poseRuns = 0

    private var phoneBox: PhoneBox? = null
    private var phoneVisible = false
    private var callingSince = 0L
    private var lastCallEventAt = 0L
    private var drinkSince = 0L
    private var lastDrinkEventAt = 0L
    private val drinkTimes = ArrayDeque<Long>()

    private var downSince = 0L
    private var awaySince = 0L
    private var lastDownAt = 0L
    private var lastAwayAt = 0L

    private var lastFaceSeenAt = 0L
    private var lastFaceReminderAt = 0L
    private var faceLostFired = false
    private var faceLostAlarm = false
    private var faceLostDialog: AlertDialog? = null
    private var eventCount = 0
    private val timelineLines = mutableListOf<String>()

    private var speedMonitor: SpeedMonitor? = null
    private var bannerHideAt = 0L
    @Volatile private var usingFrontCamera = true
    private var recorder: SessionRecorder? = null
    private var calibrating = false
    private var lastRestStopAt = 0L
    private var pendingSim: String? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMonitoring() else toast(getString(R.string.camera_permission_required))
    }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val faceLossTicker = object : Runnable {
        override fun run() {
            if (!running) return
            if (faceAnalyzer == null) {
                mainHandler.postDelayed(this, 1000)
                return
            }
            val now = SystemClock.elapsedRealtime()
            val lost = now - lastFaceSeenAt
            if (lost >= FACE_LOST_LONG_MS) {
                if (!faceLostFired) {
                    faceLostFired = true
                    faceLostAlarm = true
                    alarm?.update("harsh", "长时间未检测到人脸，请正视前方道路！")
                    showFaceLostDialog("已超过 ${lost / 1000} 秒未检测到人脸，请正视前方道路。低头或遮挡都会中断检测。")
                    persistEvent(
                        type = "FACE_LOST",
                        severity = "中度疲劳",
                        ear = 0f,
                        mar = 0f,
                        headPose = "未检测到人脸",
                        message = "已超过 ${lost / 1000} 秒未检测到人脸，请正视前方道路并确认摄像头视线。",
                    )
                }
            } else {
                if (faceLostFired || faceLostAlarm) recoverFaceLoss(notify = true)
                if (lost >= FACE_LOST_REMIND_MS && now - lastFaceReminderAt >= FACE_LOST_REMIND_REPEAT_MS) {
                    lastFaceReminderAt = now
                    alarm?.event("gentle", "未检测到人脸，请保持正视前方。")
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ApiClient.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets(binding.root)
        alarm = AlarmController(this)
        binding.toggleButton.isEnabled = false
        binding.cameraStatus.text = "模型加载中…"
        pendingSim = intent.getStringExtra(EXTRA_SIM)

        binding.toggleButton.setOnClickListener { toggleMonitoring() }
        binding.eventsButton.setOnClickListener { startActivity(Intent(this, EventsActivity::class.java)) }
        binding.logoutButton.setOnClickListener { logout() }
        binding.calibrateButton.setOnClickListener { beginCalibration() }
        binding.aiButton.setOnClickListener { showAiDialog() }
        binding.engineerButton.setOnClickListener { showEngineerDialog() }

        loadSettings()
        loadModels()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = ApiClient.getSettings() ?: return@launch
            settingsJson = settings
            tracker = TemporalRiskTracker(
                earThreshold = settings.optDouble("earThreshold", 0.25).toFloat(),
                marThreshold = settings.optDouble("marThreshold", 0.55).toFloat(),
                mildFrames = settings.optInt("mildFrames", 2),
                mediumFrames = settings.optInt("mediumFrames", 5),
                heavyFrames = settings.optInt("heavyFrames", 9),
                detectIntervalMs = FACE_INTERVAL_MS.toInt(),
            )
            alarm?.configure(
                settings.optBoolean("alarmEnabled", true),
                settings.optString("alarmStyle", "bell"),
                settings.optInt("cooldownMs", 5000),
            )
        }
    }

    private fun loadModels() {
        modelExecutor.execute {
            val face = try { FaceAnalyzer(applicationContext) } catch (error: Throwable) { null }
            val phone = try { PhoneDetector(applicationContext) } catch (error: Throwable) { null }
            val pose = try { PoseDetector(applicationContext) } catch (error: Throwable) { null }
            val head = try { HeadPoseEstimator(applicationContext) } catch (error: Throwable) { null }
            faceAnalyzer = face
            phoneDetector = phone
            poseDetector = pose
            headEstimator = head
            runOnUiThread {
                binding.toggleButton.isEnabled = face != null || phone != null || pose != null || head != null
                binding.cameraStatus.text = if (face == null) {
                    "人脸模型在当前设备不可用，其余模型已就绪"
                } else {
                    getString(R.string.monitoring_ready)
                }
                if (face == null && phone == null && pose == null && head == null) {
                    binding.cameraStatus.text = "模型加载失败，请检查 assets/models 中的模型文件"
                    toast("模型加载失败，请检查模型文件")
                }
                if (pendingSim != null && !running) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startMonitoring()
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }
    }

    private fun toggleMonitoring() {
        if (running) {
            if (speedMonitor?.driving == true) {
                toast("车辆行驶中监测不能关闭；车速为零持续 3 分钟确认休息后将自动结束")
                return
            }
            stopMonitoring()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startMonitoring()
            } else {
                requestCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startMonitoring() {
        if (running) return
        running = true
        lastFaceSeenAt = SystemClock.elapsedRealtime()
        lastFaceReminderAt = 0L
        faceLostFired = false
        faceLostAlarm = false
        tracker.reset()
        coordinator.reset()
        recorder = SessionRecorder(settings = settingsJson ?: JSONObject()).also { it.start() }
        binding.toggleButton.text = getString(R.string.stop_monitoring)
        binding.cameraStatus.text = "正在查找人脸"
        bindCamera()
        startSpeedMonitor()
        mainHandler.post(faceLossTicker)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        pendingSim?.let { sim ->
            pendingSim = null
            mainHandler.postDelayed({ simulate(sim) }, 2500)
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }
                val selector = try {
                    if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        usingFrontCamera = true
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        usingFrontCamera = false
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                } catch (error: Exception) {
                    usingFrontCamera = false
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (error: Exception) {
                running = false
                binding.toggleButton.text = getString(R.string.start_monitoring)
                binding.cameraStatus.text = "摄像头启动失败：${error.message ?: "未知错误"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < FRAME_MIN_GAP_MS) {
            proxy.close()
            return
        }
        lastFrameAt = now
        val rotation = proxy.imageInfo.rotationDegrees
        val bitmap = try {
            BitmapUtils.rotate(BitmapUtils.imageToBitmap(proxy), rotation)
        } catch (error: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (bitmap == null) return
        latestBitmap = bitmap

        if (now - lastFaceRunAt >= FACE_INTERVAL_MS) {
            lastFaceRunAt = now
            faceAnalyzer?.let { analyzer ->
                val metrics = analyzer.analyze(bitmap)
                onFaceMetrics(metrics, now)
            }
        }

        if (now - lastDetAt >= DET_INTERVAL_MS) {
            lastDetAt = now
            phoneDetector?.let { detector ->
                phoneBox = detector.detect(bitmap)
                phoneVisible = phoneBox != null
                detRuns += 1
            }
        }

        if (now - lastPoseAt >= POSE_INTERVAL_MS) {
            lastPoseAt = now
            val phone = phoneVisible
            val pose = poseDetector?.detect(bitmap, phone)
            val face = latestFace
            val phoneNearFace = phone && face?.faceDetected == true && face.height > 0f &&
                phoneBox?.let {
                    val faceCenterX = face.left + face.width / 2f
                    val faceCenterY = face.top + face.height / 2f
                    val distance = hypot(
                        (it.centerX / bitmap.width - faceCenterX).toDouble(),
                        (it.centerY / bitmap.height - faceCenterY).toDouble(),
                    ).toFloat()
                    distance < face.height * 0.6f
                } == true
            val calling = (phone && phoneNearFace) || pose?.callingHeld == true
            poseRuns += 1
            handleCalling(calling, now)
            handleDrink(pose?.handToMouth == true, now)
        }

        if (now - lastHeadAt >= HEAD_INTERVAL_MS) {
            lastHeadAt = now
            val face = latestFace
            if (face != null && face.faceDetected) {
                headEstimator?.estimate(bitmap, face)?.let { handleHeadPose(it, now) }
            }
        }
    }

    private fun onFaceMetrics(metrics: FaceMetrics, now: Long) {
        latestFace = metrics
        if (metrics.faceDetected) {
            lastFaceSeenAt = now
            if (faceLostFired || faceLostAlarm) recoverFaceLoss(notify = true)
        }
        val result = tracker.update(metrics.faceDetected, metrics.ear, metrics.mar, metrics.blinkScore, metrics.yawnScore, now)
        recorder?.add(metrics.faceDetected, result.eyeClosed, result.score, result.severity, result.reasons)
        runOnUiThread { render(result) }

        if (!faceLostAlarm) {
            when (result.severity) {
                "medium" -> alarm?.update("medium")
                "high" -> alarm?.update("high")
                else -> alarm?.stop()
            }
        }

        val event = coordinator.handle(result, now)
        if (event != null) {
            runOnUiThread { showBanner("${event.severity}：${event.message}") }
            persistEvent(
                type = event.type,
                severity = event.severity,
                ear = event.earValue,
                mar = event.marValue,
                headPose = event.headPose,
                message = event.message,
            )
        }
    }

    private fun render(result: RiskResult) {
        binding.scoreText.text = "疲劳指数 ${result.score}"
        binding.riskText.text = if (!result.faceDetected) "未检测到人脸" else result.risk
        binding.riskText.setTextColor(
            ContextCompat.getColor(
                this,
                when (result.severity) {
                    "high" -> R.color.danger
                    "medium", "mild" -> R.color.warning
                    "normal" -> R.color.safe
                    else -> R.color.text_muted
                },
            )
        )
        val ear = result.ear?.let { String.format("%.2f", it) } ?: "--"
        val yawn = (result.yawnScore * 100).toInt()
        binding.metricsText.text = "EAR $ear  ·  闭眼 ${result.closedDurationMs} ms  ·  哈欠 $yawn%  ·  本次告警 $eventCount"
        if (result.faceDetected) {
            val face = latestFace
            val bitmap = latestBitmap
            if (face != null) {
                binding.overlayView.setFace(
                    face.left, face.top, face.width, face.height,
                    mirrored = usingFrontCamera,
                    srcWidth = bitmap?.width ?: 3,
                    srcHeight = bitmap?.height ?: 4,
                )
            }
            binding.cameraStatus.text = getString(R.string.face_detected)
        } else {
            binding.overlayView.clearFace()
            binding.cameraStatus.text = getString(R.string.face_not_detected)
        }
    }

    private fun handleCalling(calling: Boolean, now: Long) {
        if (!calling) {
            callingSince = 0L
            return
        }
        if (callingSince == 0L) callingSince = now
        if (now - callingSince >= CALL_SUSTAIN_MS && now - lastCallEventAt >= CALL_COOLDOWN_MS) {
            lastCallEventAt = now
            callingSince = 0L
            val message = "检测到手持手机贴近耳侧的通话姿态，请专注驾驶。"
            alarm?.event("harsh", message)
            runOnUiThread { showBanner("分心：$message") }
            persistEvent("PHONE_USE", "中度疲劳", latestFace?.ear ?: 0f, latestFace?.mar ?: 0f, "分心使用手机", message)
        }
    }

    private fun handleDrink(handToMouth: Boolean, now: Long) {
        if (!handToMouth) {
            drinkSince = 0L
            return
        }
        if (drinkSince == 0L) drinkSince = now
        if (now - drinkSince >= DRINK_SUSTAIN_MS && now - lastDrinkEventAt >= DRINK_COOLDOWN_MS) {
            lastDrinkEventAt = now
            drinkSince = 0L
            drinkTimes.addLast(now)
            while (drinkTimes.isNotEmpty() && now - drinkTimes.first() >= 180_000) drinkTimes.removeFirst()
            val frequent = drinkTimes.size >= 3
            val severity = if (frequent) "中度疲劳" else "轻度疲劳"
            val message = if (frequent) {
                "3 分钟内多次检测到喝水或吸烟等抬手动作，请专注驾驶。"
            } else {
                "检测到喝水或手持物品动作，请把双手放回方向盘。"
            }
            alarm?.event(if (frequent) "harsh" else "gentle", message)
            runOnUiThread { showBanner(if (frequent) "重复抬手：$message" else message) }
            persistEvent("DRINK", severity, latestFace?.ear ?: 0f, latestFace?.mar ?: 0f, "抬手动作", message)
        }
    }

    private fun handleHeadPose(pose: HeadPose, now: Long) {
        if (pose.pitch <= -25f) {
            if (downSince == 0L) downSince = now
            if (now - downSince >= HEAD_SUSTAIN_MS && now - lastDownAt >= HEAD_COOLDOWN_MS) {
                lastDownAt = now
                downSince = 0L
                val degrees = abs(pose.pitch).toInt()
                val message = "头部下俯约 ${degrees}°，持续 ${HEAD_SUSTAIN_MS / 1000} 秒，请抬头正视前方。"
                runOnUiThread { showBanner("轻度疲劳：$message") }
                persistEvent("HEAD_DOWN", "轻度疲劳", latestFace?.ear ?: 0f, latestFace?.mar ?: 0f, "低头", message)
            }
        } else {
            downSince = 0L
        }
        if (abs(pose.yaw) >= 32f) {
            if (awaySince == 0L) awaySince = now
            if (now - awaySince >= HEAD_SUSTAIN_MS && now - lastAwayAt >= HEAD_COOLDOWN_MS) {
                lastAwayAt = now
                awaySince = 0L
                val degrees = abs(pose.yaw).toInt()
                val message = "视线偏离前方约 ${degrees}°，持续 ${HEAD_SUSTAIN_MS / 1000} 秒，请留意路况。"
                runOnUiThread { showBanner("中度疲劳：$message") }
                persistEvent("HEAD_AWAY", "中度疲劳", latestFace?.ear ?: 0f, latestFace?.mar ?: 0f, "左顾右盼", message)
            }
        } else {
            awaySince = 0L
        }
    }

    private fun persistEvent(
        type: String,
        severity: String,
        ear: Float,
        mar: Float,
        headPose: String,
        message: String,
    ) {
        eventCount += 1
        recorder?.addEvent(
            type = type,
            severityChinese = severity,
            reason = message,
            ear = ear,
            mar = mar,
            closedDurationMs = null,
            yawnDurationMs = null,
        )
        val label = TYPE_LABELS[type] ?: type
        timelineLines.add(
            0,
            "${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())}  $label · $severity",
        )
        if (timelineLines.size > 4) timelineLines.removeAt(timelineLines.size - 1)
        runOnUiThread {
            binding.timelineText.text = "本次事件时间轴：\n" + timelineLines.joinToString("\n")
        }
        val payload = JSONObject().apply {
            put("type", type)
            put("severity", severity)
            put("createdAt", Instant.now().toString())
            put("earValue", ear.toDouble().coerceIn(0.0, 2.0))
            put("marValue", mar.toDouble().coerceIn(0.0, 5.0))
            put("routeName", "")
            put("headPose", headPose)
            put("message", message)
        }
        latestBitmap?.let { bitmap ->
            BitmapUtils.jpegBase64(bitmap)?.let { payload.put("snapshot", it) }
        }
        lifecycleScope.launch {
            val (ok, code) = ApiClient.createEvent(payload)
            if (!ok && code == 401) toast("登录已失效，请重新登录")
        }
        if (severity == "重度疲劳") maybeRecommendRestStops()
    }

    private fun maybeRecommendRestStops() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestStopAt < REST_STOP_COOLDOWN_MS) return
        val location = lastKnownLocation() ?: return
        lastRestStopAt = now
        lifecycleScope.launch {
            val items = ApiClient.restStops(location.latitude, location.longitude) ?: return@launch
            if (items.length() == 0) return@launch
            showRestStopDialog(items)
        }
    }

    private fun lastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (error: SecurityException) {
            null
        }
    }

    private fun showRestStopDialog(items: JSONArray) {
        val labels = mutableListOf<String>()
        val links = mutableListOf<String?>()
        for (i in 0 until minOf(items.length(), 3)) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name", "服务区")
            val distance = item.optInt("distanceMeters", 0)
            labels.add("${i + 1}. $name · ${if (distance >= 1000) "%.1f 公里".format(distance / 1000f) else "$distance 米"}")
            val lng = item.optDouble("lng", Double.NaN)
            val lat = item.optDouble("lat", Double.NaN)
            links.add(if (lat.isNaN() || lng.isNaN()) null else "https://uri.amap.com/navigation?to=$lng,$lat,${Uri.encode(name)}&mode=car&coordinate=gaode&callnative=1")
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("检测到重度疲劳，附近休息点")
            .setItems(labels.toTypedArray()) { _, which ->
                val link = links.getOrNull(which) ?: return@setItems
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                } catch (error: Exception) {
                    toast("无法打开地图导航")
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun beginCalibration() {
        if (!running) {
            toast("请先开启监测再校准")
            return
        }
        if (calibrating) return
        if (faceAnalyzer == null) {
            toast("当前设备不支持人脸模型，无法校准")
            return
        }
        calibrating = true
        binding.calibrateButton.isEnabled = false
        val samples = mutableListOf<Pair<Float, Float>>()
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < 10_000) {
                latestFace?.takeIf { it.faceDetected && it.ear != null && it.mar != null && it.blinkScore < 0.35f }?.let {
                    samples.add(it.ear!! to it.mar!!)
                }
                val elapsed = (SystemClock.elapsedRealtime() - start) / 1000
                binding.cameraStatus.text = "正在校准正常状态… ${elapsed.coerceAtMost(10)}/10 秒，请自然睁眼、正视镜头"
                delay(300)
            }
            calibrating = false
            binding.calibrateButton.isEnabled = true
            binding.cameraStatus.text = getString(R.string.face_detected)
            if (samples.size < 30) {
                toast("有效样本不足（${samples.size}/30），请正视摄像头保持自然睁眼后重试")
                return@launch
            }
            val ears = samples.map { it.first }.sorted()
            val mars = samples.map { it.second }.sorted()
            val ear = ears[ears.size / 2]
            val mar = mars[mars.size / 2]
            val earThreshold = (ear * 0.72f).coerceIn(0.08f, 0.4f)
            val marThreshold = (mar * 3f + 0.15f).coerceIn(0.35f, 0.8f)
            AlertDialog.Builder(this@MonitorActivity)
                .setTitle("校准完成")
                .setMessage("有效样本 ${samples.size} 个。建议 EAR 阈值 ${"%.3f".format(earThreshold)}，MAR 阈值 ${"%.3f".format(marThreshold)}。应用后保存到当前账号并立即生效。")
                .setPositiveButton("应用校准") { _, _ -> applyCalibration(earThreshold, marThreshold) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun applyCalibration(earThreshold: Float, marThreshold: Float) {
        lifecycleScope.launch {
            val patch = JSONObject().put("earThreshold", earThreshold.toDouble()).put("marThreshold", marThreshold.toDouble())
            val (ok, message) = ApiClient.putSettings(patch)
            if (!ok) {
                toast(message ?: "校准保存失败")
                return@launch
            }
            settingsJson?.put("earThreshold", earThreshold.toDouble())?.put("marThreshold", marThreshold.toDouble())
            tracker = TemporalRiskTracker(
                earThreshold = earThreshold,
                marThreshold = marThreshold,
                mildFrames = settingsJson?.optInt("mildFrames", 2) ?: 2,
                mediumFrames = settingsJson?.optInt("mediumFrames", 5) ?: 5,
                heavyFrames = settingsJson?.optInt("heavyFrames", 9) ?: 9,
                detectIntervalMs = FACE_INTERVAL_MS.toInt(),
            )
            toast("个人阈值已保存并生效")
        }
    }

    private fun showAiDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val answer = TextView(this).apply {
            text = "你好，我可以结合当前监测状态分析疲劳风险。"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        val input = EditText(this).apply {
            hint = "例如：现在需要休息吗？"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val send = Button(this).apply { text = "解析当前状态" }
        layout.addView(answer)
        layout.addView(input)
        layout.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("✦ AI 驾驶解析")
            .setView(layout)
            .setNegativeButton("关闭", null)
            .show()
        send.setOnClickListener {
            val face = latestFace
            val question = input.text.toString().trim().ifBlank {
                val state = "${if (face?.faceDetected == true) "人脸已就绪" else "未检测到人脸"}，本次告警 $eventCount 条"
                "分析当前驾驶员的疲劳状态并给出建议。当前：$state"
            }
            answer.text = "正在分析…"
            lifecycleScope.launch {
                val reply = ApiClient.aiChat(question)
                answer.text = reply ?: localAiFallback()
            }
        }
    }

    private fun localAiFallback(): String {
        val face = latestFace
        val parts = mutableListOf<String>()
        parts.add("本次监测已记录 $eventCount 条告警")
        if (face?.faceDetected == true) {
            face.ear?.let { parts.add("EAR ${"%.2f".format(it)}") }
            parts.add("眨眼分数 ${(face.blinkScore * 100).toInt()}%")
            parts.add("张嘴分数 ${(face.yawnScore * 100).toInt()}%")
        } else {
            parts.add("当前未检测到人脸，请调整坐姿正视镜头")
        }
        val advice = if (eventCount >= 5) "告警较多，建议尽快在安全地点停车休息。" else "请保持专注并按时休息。"
        return "在线 AI 暂不可用，以下是本地监测分析：${parts.joinToString("，")}。$advice"
    }

    private fun showEngineerDialog() {
        val options = arrayOf(
            "模拟重度疲劳",
            "模拟持续哈欠",
            "模拟手机分心",
            "模拟喝水动作",
            "触发长时间无人脸弹窗",
            "模拟车速 60km/h",
            "模拟车速 0",
            "重置告警",
        )
        AlertDialog.Builder(this)
            .setTitle("⚙ 工程模式（模拟演示）")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> simulate("fatigue")
                    1 -> simulate("yawn")
                    2 -> simulate("phone")
                    3 -> simulate("drink")
                    4 -> simulate("lost")
                    5 -> simulate("speed60")
                    6 -> simulate("speed0")
                    else -> simulate("reset")
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun simulate(kind: String) {
        when (kind) {
            "fatigue" -> {
                alarm?.event("harsh", "（模拟）危险，检测到重度疲劳，请立即停车休息。")
                showBanner("重度疲劳：模拟事件已触发")
                persistEvent("EYE_CLOSE", "重度疲劳", 0.12f, 0.2f, "", "（工程模式模拟）持续闭眼 3.2 秒，近 60 秒闭眼占比 55%。")
            }
            "yawn" -> {
                alarm?.update("medium", "（模拟）检测到持续哈欠，请尽快休息。")
                showBanner("中度疲劳：模拟哈欠")
                persistEvent("YAWN", "中度疲劳", 0.3f, 0.72f, "", "（工程模式模拟）持续张嘴 2.6 秒。")
            }
            "phone" -> {
                alarm?.event("harsh", "请勿在驾驶时使用手机！")
                showBanner("分心：模拟手机通话")
                persistEvent("PHONE_USE", "中度疲劳", 0.25f, 0.3f, "分心使用手机", "（工程模式模拟）检测到手持手机贴近耳侧的通话姿态。")
            }
            "drink" -> {
                alarm?.event("gentle", "请注意，请把双手放回方向盘。")
                showBanner("喝水：模拟抬手动作")
                persistEvent("DRINK", "轻度疲劳", 0.3f, 0.2f, "抬手动作", "（工程模式模拟）检测到喝水动作。")
            }
            "lost" -> {
                faceLostFired = true
                faceLostAlarm = true
                alarm?.update("harsh", "长时间未检测到人脸，请正视前方道路！")
                showFaceLostDialog("（工程模式模拟）长时间未检测到人脸，请正视前方道路。")
                persistEvent("FACE_LOST", "中度疲劳", 0f, 0f, "未检测到人脸", "（工程模式模拟）长时间未检测到人脸。")
            }
            "speed60" -> {
                if (speedMonitor == null) toast("请先开启监测再进行车速模拟")
                else speedMonitor?.simulate(16.7)
            }
            "speed0" -> {
                if (speedMonitor == null) toast("请先开启监测再进行车速模拟")
                else speedMonitor?.simulate(0.0)
            }
            "reset" -> {
                alarm?.stop()
                toast("告警已重置")
            }
        }
    }

    private fun showFaceLostDialog(text: String) {
        if (faceLostDialog?.isShowing == true) return
        faceLostDialog = AlertDialog.Builder(this)
            .setTitle("长时间未检测到人脸")
            .setMessage(text)
            .setPositiveButton("我知道了，保持正视前方", null)
            .setCancelable(true)
            .show()
    }

    private fun recoverFaceLoss(notify: Boolean) {
        val wasFired = faceLostFired
        faceLostFired = false
        faceLostAlarm = false
        alarm?.stop()
        faceLostDialog?.dismiss()
        faceLostDialog = null
        if (notify && wasFired) toast("已重新检测到人脸")
    }

    private fun showBanner(text: String) {
        val banner = binding.alarmBanner
        banner.text = text
        banner.visibility = View.VISIBLE
        bannerHideAt = SystemClock.elapsedRealtime() + 4000
        mainHandler.postDelayed({
            if (SystemClock.elapsedRealtime() >= bannerHideAt) banner.visibility = View.GONE
        }, 4200)
    }

    private fun startSpeedMonitor() {
        if (speedMonitor != null) return
        speedMonitor = SpeedMonitor(
            context = this,
            onSpeedChanged = { kmh -> binding.speedText.text = "车速：${"%.0f".format(kmh)} km/h" },
            onDriving = {
                if (!running) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startMonitoring()
                        toast("检测到车辆行驶，监测已自动开启")
                    }
                }
            },
            onStoppedForRest = {
                if (running) {
                    stopMonitoring()
                    toast("车速持续为零 3 分钟，已确认下车休息，监测自动结束")
                }
            },
        ).also { it.start() }
    }

    private fun stopMonitoring() {
        if (!running && recorder == null) return
        running = false
        mainHandler.removeCallbacks(faceLossTicker)
        try {
            cameraProvider?.unbindAll()
        } catch (error: Exception) {
            // ignore
        }
        speedMonitor?.stop()
        speedMonitor = null
        alarm?.stop()
        tracker.reset()
        coordinator.reset()
        latestFace = null
        latestBitmap = null
        phoneBox = null
        phoneVisible = false
        callingSince = 0L
        drinkSince = 0L
        downSince = 0L
        awaySince = 0L
        binding.overlayView.clearFace()
        binding.toggleButton.text = getString(R.string.start_monitoring)
        binding.cameraStatus.text = getString(R.string.monitoring_ready)
        binding.scoreText.text = "疲劳指数 --"
        binding.riskText.text = "等待监测"
        binding.riskText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        binding.metricsText.text = "EAR --  ·  闭眼 0 ms  ·  哈欠 0%  ·  本次告警 $eventCount"
        binding.speedText.text = "车速：-- km/h"

        val finished = recorder?.finish()
        recorder = null
        if (finished != null) {
            lifecycleScope.launch {
                val (ok, message) = ApiClient.createReport(finished)
                toast(if (ok) "报告已保存到当前账号" else (message ?: "报告保存失败"))
            }
        }
    }

    private fun logout() {
        stopMonitoring()
        lifecycleScope.launch {
            ApiClient.logout()
            startActivity(Intent(this@MonitorActivity, LoginActivity::class.java))
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (running) stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        modelExecutor.execute {
            faceAnalyzer?.close()
            phoneDetector?.close()
            poseDetector?.close()
            headEstimator?.close()
        }
        modelExecutor.shutdown()
        alarm?.close()
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
