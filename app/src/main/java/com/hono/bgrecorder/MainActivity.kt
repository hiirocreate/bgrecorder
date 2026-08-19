package com.hono.bgrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var idlePanel: LinearLayout
    private lateinit var recordingPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var pauseResumeBtn: Button
    private lateinit var filterButtons: List<Button>
    private lateinit var facingButtons: List<Button>

    private var selectedFilter = FilterMode.NORMAL
    private var selectedFacing = CameraCharacteristics.LENS_FACING_BACK

    private var recordingService: RecordingService? = null
    private var bound = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            recordingService?.stateListener = { state -> runOnUiThread { onServiceStateChanged(state) } }
            recordingService?.errorListener = { message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
            onServiceStateChanged(recordingService!!.state)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            openCameraPreviewIfReady()
        } else {
            Toast.makeText(this, "カメラ・マイク・通知の権限を許可してください", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)

        val lastCrash = CrashLogger.readAndClear(this)
        if (lastCrash != null) {
            showCrashDialog(lastCrash)
            return
        }

        bgThread = HandlerThread("MainActivityCamera").apply { start() }
        bgHandler = Handler(bgThread.looper)

        setContentView(buildUi())

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            openCameraPreviewIfReady()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }

        bound = bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** 前回クラッシュしていた場合、その内容をコピー可能なダイアログで表示する（ログを見る手段が無くても送れるように） */
    private fun showCrashDialog(text: String) {
        val textView = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            textSize = 12f
        }
        val scrollView = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle("前回のクラッシュ内容（長押しでコピーできます）")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("閉じる") { _, _ -> recreate() }
            .show()
    }

    // ---- 画面構築（レイアウトXMLは使わずコードだけで組み立てる） ----
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCameraPreviewIfReady()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // ---- 撮影前パネル：カメラ切替 + フィルター選択 + 録画開始ボタン ----
        idlePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 48)
        }

        val facingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val facingLabels = listOf(
            "背面" to CameraCharacteristics.LENS_FACING_BACK,
            "前面" to CameraCharacteristics.LENS_FACING_FRONT,
        )
        facingButtons = facingLabels.map { (label, facing) ->
            Button(this).apply {
                text = label
                setOnClickListener {
                    if (selectedFacing != facing) {
                        selectedFacing = facing
                        updateFacingButtonStyles()
                        closePreviewCamera()
                        openCameraPreviewIfReady()
                    }
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(8, 8, 8, 8)
                facingRow.addView(this, lp)
            }
        }
        idlePanel.addView(facingRow)
        updateFacingButtonStyles()

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val labels = listOf("通常" to FilterMode.NORMAL, "暗所" to FilterMode.NIGHT, "風景" to FilterMode.LANDSCAPE)
        filterButtons = labels.map { (label, mode) ->
            Button(this).apply {
                text = label
                setOnClickListener {
                    selectedFilter = mode
                    updateFilterButtonStyles()
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(8, 8, 8, 8)
                filterRow.addView(this, lp)
            }
        }
        idlePanel.addView(filterRow)
        updateFilterButtonStyles()

        val startBtn = Button(this).apply {
            text = "● 録画開始"
            setOnClickListener { startRecording() }
        }
        idlePanel.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })

        root.addView(
            idlePanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        // ---- 録画中パネル：状態表示 + 一時停止/再開 + 停止 ----
        recordingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 48)
            visibility = View.GONE
        }
        statusText = TextView(this).apply {
            text = "● 録画中"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        recordingPanel.addView(statusText)

        val hint = TextView(this).apply {
            text = "ホームボタンを押すと他のアプリを使いながら録画を続けられます"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        recordingPanel.addView(hint)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        pauseResumeBtn = Button(this).apply {
            text = "一時停止"
            setOnClickListener { togglePauseResume() }
        }
        val stopBtn = Button(this).apply {
            text = "■ 停止"
            setOnClickListener { stopRecording() }
        }
        val lp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }
        val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }
        btnRow.addView(pauseResumeBtn, lp1)
        btnRow.addView(stopBtn, lp2)
        recordingPanel.addView(btnRow)

        root.addView(
            recordingPanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        return root
    }

    private fun updateFilterButtonStyles() {
        val labels = listOf(FilterMode.NORMAL, FilterMode.NIGHT, FilterMode.LANDSCAPE)
        filterButtons.forEachIndexed { i, btn ->
            val isSelected = labels[i] == selectedFilter
            btn.alpha = if (isSelected) 1.0f else 0.5f
        }
    }

    private fun updateFacingButtonStyles() {
        val facings = listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)
        facingButtons.forEachIndexed { i, btn ->
            val isSelected = facings[i] == selectedFacing
            btn.alpha = if (isSelected) 1.0f else 0.5f
        }
        // 前面カメラのプレビューは鏡写しにして、自然な「セルフィー」表示にする
        // （録画データ自体は反転しない。プレビュー表示だけの見た目の調整）
        if (::textureView.isInitialized) {
            textureView.scaleX = if (selectedFacing == CameraCharacteristics.LENS_FACING_FRONT) -1f else 1f
        }
    }

    // ---- 撮影前プレビュー（フィルターなしの単純なCamera2プレビュー） ----
    private fun openCameraPreviewIfReady() {
        if (!textureView.isAvailable) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cameraDevice != null) return

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == selectedFacing
        } ?: manager.cameraIdList.firstOrNull() ?: return

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startPreviewSession(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); cameraDevice = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            // 権限がない場合は何もしない
        }
    }

    private fun startPreviewSession(device: CameraDevice) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1920, 1080)
        val surface = android.view.Surface(texture)
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(surface)

        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, bgHandler)
                    } catch (e: Exception) { /* 無視 */ }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            bgHandler
        )
    }

    private fun closePreviewCamera() {
        try { captureSession?.close() } catch (e: Exception) { /* 無視 */ }
        try { cameraDevice?.close() } catch (e: Exception) { /* 無視 */ }
        captureSession = null
        cameraDevice = null
    }

    // ---- 録画操作 ----
    private fun startRecording() {
        closePreviewCamera() // Activity側のプレビューを手放してからサービスに渡す
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FILTER_MODE, selectedFilter)
            putExtra(RecordingService.EXTRA_CAMERA_FACING, selectedFacing)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun togglePauseResume() {
        val svc = recordingService ?: return
        val action = if (svc.state == RecordingService.State.PAUSED) {
            RecordingService.ACTION_RESUME
        } else {
            RecordingService.ACTION_PAUSE
        }
        startService(Intent(this, RecordingService::class.java).apply { this.action = action })
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP })
    }

    private fun onServiceStateChanged(state: RecordingService.State) {
        when (state) {
            RecordingService.State.IDLE -> {
                idlePanel.visibility = View.VISIBLE
                recordingPanel.visibility = View.GONE
                openCameraPreviewIfReady()
            }
            RecordingService.State.RECORDING -> {
                idlePanel.visibility = View.GONE
                recordingPanel.visibility = View.VISIBLE
                statusText.text = "● 録画中"
                pauseResumeBtn.text = "一時停止"
            }
            RecordingService.State.PAUSED -> {
                idlePanel.visibility = View.GONE
                recordingPanel.visibility = View.VISIBLE
                statusText.text = "❚❚ 一時停止中"
                pauseResumeBtn.text = "再開"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closePreviewCamera()
        bgThread.quitSafely()
        if (bound) {
            unbindService(connection)
        }
    }
}
