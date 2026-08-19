package com.hono.bgrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.hono.bgrecorder.action.START"
        const val ACTION_PAUSE = "com.hono.bgrecorder.action.PAUSE"
        const val ACTION_RESUME = "com.hono.bgrecorder.action.RESUME"
        const val ACTION_STOP = "com.hono.bgrecorder.action.STOP"
        const val EXTRA_FILTER_MODE = "filter_mode"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIF_ID = 1001
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val BIT_RATE = 8_000_000
        private const val PREF_MIGRATED = "migrated_to_private_storage_v1"
    }

    enum class State { IDLE, RECORDING, PAUSED }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    var state: State = State.IDLE
        private set
    var stateListener: ((State) -> Unit)? = null

    private var filterMode = FilterMode.NORMAL

    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var glPipeline: GLFilterPipeline? = null
    private var videoEncoder: VideoEncoderCore? = null
    private var audioEncoder: AudioEncoderCore? = null
    private var muxer: MuxerWrapper? = null
    private var tempFile: File? = null

    // 一時停止しても映像のタイムスタンプが飛ばないようにするための時計
    private var recordingStartNanos = 0L
    private var pausedAccumNanos = 0L
    private var pauseStartNanos = 0L

    override fun onCreate() {
        super.onCreate()
        bgThread = HandlerThread("RecordingServiceBg").apply { start() }
        bgHandler = Handler(bgThread.looper)
        createNotificationChannel()
        bgHandler.post { migrateOldPublicRecordingsIfNeeded() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                filterMode = intent.getIntExtra(EXTRA_FILTER_MODE, FilterMode.NORMAL)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIF_ID, buildNotification())
                }
                bgHandler.post { startRecordingInternal() }
            }
            ACTION_PAUSE -> bgHandler.post { pauseRecordingInternal() }
            ACTION_RESUME -> bgHandler.post { resumeRecordingInternal() }
            ACTION_STOP -> bgHandler.post { stopRecordingInternal() }
        }
        return START_NOT_STICKY
    }

    fun setFilterMode(mode: Int) {
        filterMode = mode
    }

    // ---- カメラ・エンコーダーのセットアップ（バックグラウンドスレッドで実行） ----

    private fun startRecordingInternal() {
        if (state != State.IDLE) return

        val hasCamera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCamera || !hasMic) {
            stopSelf()
            return
        }

        tempFile = File(cacheDir, "bgrecorder_temp_${System.currentTimeMillis()}.mp4")
        val muxerLocal = MuxerWrapper(tempFile!!.absolutePath)
        muxer = muxerLocal

        val videoEncoderLocal = VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT, BIT_RATE, muxerLocal)
        videoEncoder = videoEncoderLocal

        val glPipelineLocal = GLFilterPipeline(videoEncoderLocal.inputSurface, VIDEO_WIDTH, VIDEO_HEIGHT)
        glPipeline = glPipelineLocal

        recordingStartNanos = SystemClock.elapsedRealtimeNanos()
        pausedAccumNanos = 0L

        glPipelineLocal.surfaceTexture.setOnFrameAvailableListener({
            if (state == State.RECORDING) {
                val pts = SystemClock.elapsedRealtimeNanos() - recordingStartNanos - pausedAccumNanos
                glPipelineLocal.drawFrame(filterMode, pts)
                videoEncoderLocal.drain(false)
            } else {
                // 一時停止中でもバッファは消費しておく（詰まり防止）
                glPipelineLocal.surfaceTexture.updateTexImage()
            }
        }, bgHandler)

        val audioEncoderLocal = AudioEncoderCore(muxerLocal)
        audioEncoder = audioEncoderLocal
        audioEncoderLocal.start()

        openCamera(glPipelineLocal.cameraInputSurface)

        state = State.RECORDING
        postStateChanged()
        updateNotification()
    }

    private fun openCamera(targetSurface: android.view.Surface) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createCaptureSession(device, targetSurface)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun createCaptureSession(device: CameraDevice, targetSurface: android.view.Surface) {
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        requestBuilder.addTarget(targetSurface)
        requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        device.createCaptureSession(
            listOf(targetSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, bgHandler)
                    } catch (e: Exception) {
                        // 無視（クローズされた後などに呼ばれる場合がある）
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    stopSelf()
                }
            },
            bgHandler
        )
    }

    private fun pauseRecordingInternal() {
        if (state != State.RECORDING) return
        pauseStartNanos = SystemClock.elapsedRealtimeNanos()
        state = State.PAUSED
        audioEncoder?.paused = true
        postStateChanged()
        updateNotification()
    }

    private fun resumeRecordingInternal() {
        if (state != State.PAUSED) return
        pausedAccumNanos += SystemClock.elapsedRealtimeNanos() - pauseStartNanos
        state = State.RECORDING
        audioEncoder?.paused = false
        postStateChanged()
        updateNotification()
    }

    private fun stopRecordingInternal() {
        if (state == State.IDLE) return
        state = State.IDLE

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) { /* 無視 */ }
        try {
            cameraDevice?.close()
        } catch (e: Exception) { /* 無視 */ }
        captureSession = null
        cameraDevice = null

        videoEncoder?.drain(true)
        audioEncoder?.stop()
        videoEncoder?.release()
        glPipeline?.release()
        muxer?.release()

        videoEncoder = null
        glPipeline = null
        audioEncoder = null
        muxer = null

        saveToPrivateStorage()

        postStateChanged()
        stopForeground(true)
        stopSelf()
    }

    /**
     * 録画データはMediaStore（アルバム等から見える公開領域）には一切保存しない。
     * アプリの内部ストレージ（他アプリからはroot無しで絶対に見えない領域）に保存し、
     * 唯一の閲覧経路であるBGViewer（RecordingsProvider経由）からだけ見えるようにする。
     */
    private fun saveToPrivateStorage() {
        val file = tempFile ?: return
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        try {
            val name = "REC_${fileTimestamp()}.mp4"
            val dest = File(RecordingsStorage.dir(this), name)
            file.copyTo(dest, overwrite = true)
        } catch (e: Exception) {
            // 無視（保存に失敗しても一時ファイルは残しておく）
            return
        }
        file.delete()
    }

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())

    /**
     * 旧バージョンでMediaStore（公開のMovies/BGRecorder）に保存してしまった録画を、
     * 内部ストレージへ一度だけ移動する。移動後は公開領域から削除するため、
     * アップデート後は他アプリから過去の録画も見えなくなる。
     */
    private fun migrateOldPublicRecordingsIfNeeded() {
        val prefs = getSharedPreferences("bgrecorder_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_MIGRATED, false)) return

        try {
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(Environment.DIRECTORY_MOVIES + "/BGRecorder%")
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "migrated_${id}.mp4"
                    val srcUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    try {
                        val dest = File(RecordingsStorage.dir(this), name)
                        contentResolver.openInputStream(srcUri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        contentResolver.delete(srcUri, null, null)
                    } catch (e: Exception) {
                        // この1件が失敗しても他の移行は続ける
                    }
                }
            }
        } catch (e: Exception) {
            // 移行に失敗しても録画機能自体には影響させない
        }

        prefs.edit().putBoolean(PREF_MIGRATED, true).apply()
    }

    private fun postStateChanged() {
        val s = state
        Handler(mainLooper).post { stateListener?.invoke(s) }
    }

    // ---- 通知 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun buildNotification(): Notification {
        val paused = state == State.PAUSED
        val title = if (paused) getString(R.string.notif_paused) else getString(R.string.notif_recording)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (paused) {
            builder.addAction(R.drawable.ic_notification, getString(R.string.action_resume), actionPendingIntent(ACTION_RESUME))
        } else {
            builder.addAction(R.drawable.ic_notification, getString(R.string.action_pause), actionPendingIntent(ACTION_PAUSE))
        }
        builder.addAction(R.drawable.ic_notification, getString(R.string.action_stop), actionPendingIntent(ACTION_STOP))

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        bgThread.quitSafely()
    }
}
