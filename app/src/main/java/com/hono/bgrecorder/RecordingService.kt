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
        const val EXTRA_CAMERA_FACING = "camera_facing"
        const val EXTRA_VIDEO_WIDTH = "video_width"
        const val EXTRA_VIDEO_HEIGHT = "video_height"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIF_ID = 1001
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
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

    /** 録画開始に失敗したり、録画中に致命的なエラーが起きたときにUI側へ知らせるためのコールバック */
    var errorListener: ((String) -> Unit)? = null

    private var filterMode = FilterMode.NORMAL
    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var requestedWidth = DEFAULT_WIDTH
    private var requestedHeight = DEFAULT_HEIGHT

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
                cameraFacing = intent.getIntExtra(EXTRA_CAMERA_FACING, CameraCharacteristics.LENS_FACING_BACK)
                requestedWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, DEFAULT_WIDTH)
                requestedHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, DEFAULT_HEIGHT)
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
    // このメソッド以下は全て bgHandler（専用スレッド）上で動く。想定外の例外が
    // 1つでも外に漏れるとアプリ全体がクラッシュするため、必ずtry/catchで受け止め、
    // 失敗時は確実にリソース解放してIDLEへ戻す。

    private fun startRecordingInternal() {
        if (state != State.IDLE) return

        val hasCamera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCamera || !hasMic) {
            handleStartFailure("カメラ・マイクの権限がありません")
            return
        }

        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == cameraFacing
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                handleStartFailure("使用できるカメラが見つかりませんでした")
                return
            }

            // 「希望の解像度」ではなく「カメラが実際にサポートしている解像度」を使う。
            // これをしないと、カメラの実出力とエンコード先のサイズが食い違い、映像が
            // 引き伸ばされる（横長になる等）ことがある。
            val resolvedSize = CameraSizeUtil.chooseSupportedSize(manager, cameraId, requestedWidth, requestedHeight)
            val videoWidth = resolvedSize.width
            val videoHeight = resolvedSize.height
            val bitRate = CameraSizeUtil.bitRateFor(videoWidth, videoHeight)

            // カメラセンサーは機種によらずほぼ横向きが基準（SENSOR_ORIENTATION）であり、
            // 本アプリの撮影画面は縦向き固定のため、そのままでは縦持ちで撮った動画が
            // 横倒しの映像として保存されてしまう。動画本体は横向きのまま作り、
            // 「再生時にこの角度だけ回転させる」というメタデータだけをMP4に付与することで、
            // ピクセルを作り直すコストなしに、縦動画は縦動画として正しく再生されるようにする。
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // 撮影画面は縦向き固定（＝端末は常に「自然な向き」で使われる）ため、必要な回転角は
            // 前面・背面どちらのカメラでもSENSOR_ORIENTATIONそのもの（前面カメラだけ別式にする必要はない。
            // 鏡写しは録画データではなくプレビュー表示だけに適用しており、この回転角の計算とは無関係）。
            val rotationHint = ((sensorOrientation % 360) + 360) % 360

            tempFile = File(cacheDir, "bgrecorder_temp_${System.currentTimeMillis()}.mp4")
            val muxerLocal = MuxerWrapper(tempFile!!.absolutePath)
            muxerLocal.setOrientationHint(rotationHint)
            muxer = muxerLocal

            val videoEncoderLocal = VideoEncoderCore(videoWidth, videoHeight, bitRate, muxerLocal)
            videoEncoder = videoEncoderLocal

            val glPipelineLocal = GLFilterPipeline(videoEncoderLocal.inputSurface, videoWidth, videoHeight)
            glPipeline = glPipelineLocal

            recordingStartNanos = SystemClock.elapsedRealtimeNanos()
            pausedAccumNanos = 0L

            glPipelineLocal.surfaceTexture.setOnFrameAvailableListener({
                try {
                    if (state == State.RECORDING) {
                        val pts = SystemClock.elapsedRealtimeNanos() - recordingStartNanos - pausedAccumNanos
                        glPipelineLocal.drawFrame(filterMode, pts)
                        videoEncoderLocal.drain(false)
                    } else {
                        // 一時停止中でもバッファは消費しておく（詰まり防止）
                        glPipelineLocal.surfaceTexture.updateTexImage()
                    }
                } catch (e: Exception) {
                    // 描画・エンコード中の例外はここで必ず食い止める（漏らすとアプリごと落ちる）。
                    // ただし、すでに停止操作によってIDLEへ戻っている場合は、解放済みのリソースに
                    // 触れたことによる後始末上の例外にすぎないので、ユーザーへエラー表示はしない
                    // （停止操作のたびに「エラーが発生しました」と出てしまっていた問題の修正）。
                    if (state != State.IDLE) {
                        notifyError("録画中にエラーが発生したため停止しました")
                        stopRecordingInternal()
                    }
                }
            }, bgHandler)

            val audioEncoderLocal = AudioEncoderCore(muxerLocal)
            audioEncoder = audioEncoderLocal
            audioEncoderLocal.onFatalError = {
                // 同様に、すでに停止済み/停止中であれば通知しない（エラー時のみ通知する）
                if (state != State.IDLE) {
                    notifyError("録音中にエラーが発生したため停止しました")
                    bgHandler.post { stopRecordingInternal() }
                }
            }
            audioEncoderLocal.start()

            openCamera(glPipelineLocal.cameraInputSurface, cameraId)

            state = State.RECORDING
            postStateChanged()
            updateNotification()
        } catch (e: Exception) {
            handleStartFailure("録画を開始できませんでした")
        }
    }

    private fun openCamera(targetSurface: android.view.Surface, cameraId: String) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

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
                    if (state != State.IDLE) {
                        handleStartFailure("カメラの起動に失敗しました")
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            handleStartFailure("カメラを開けませんでした")
        }
    }

    private fun createCaptureSession(device: CameraDevice, targetSurface: android.view.Surface) {
        try {
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
                        if (state != State.IDLE) {
                            handleStartFailure("カメラの設定に失敗しました")
                        }
                    }
                },
                bgHandler
            )
        } catch (e: Exception) {
            handleStartFailure("カメラの設定に失敗しました")
        }
    }

    private fun pauseRecordingInternal() {
        if (state != State.RECORDING) return
        try {
            pauseStartNanos = SystemClock.elapsedRealtimeNanos()
            state = State.PAUSED
            audioEncoder?.paused = true
            postStateChanged()
            updateNotification()
        } catch (e: Exception) {
            notifyError("一時停止に失敗しました")
        }
    }

    private fun resumeRecordingInternal() {
        if (state != State.PAUSED) return
        try {
            pausedAccumNanos += SystemClock.elapsedRealtimeNanos() - pauseStartNanos
            state = State.RECORDING
            audioEncoder?.paused = false
            postStateChanged()
            updateNotification()
        } catch (e: Exception) {
            notifyError("再開に失敗しました")
        }
    }

    /** 録画開始～セットアップ中に失敗した場合の後始末。必ずIDLEへ戻し、確保しかけたリソースを解放する。 */
    private fun handleStartFailure(message: String) {
        if (state == State.IDLE && videoEncoder == null && glPipeline == null && audioEncoder == null && muxer == null) {
            // まだ何も確保していない場合はシンプルに終了するだけでよい
            notifyError(message)
            stopForeground(true)
            stopSelf()
            return
        }
        notifyError(message)
        stopRecordingInternal(discardOutput = true)
    }

    private fun stopRecordingInternal(discardOutput: Boolean = false) {
        if (state == State.IDLE && videoEncoder == null && glPipeline == null && audioEncoder == null && muxer == null) return
        state = State.IDLE

        try {
            try {
                captureSession?.stopRepeating()
                captureSession?.close()
            } catch (e: Exception) { /* 無視 */ }
            try {
                cameraDevice?.close()
            } catch (e: Exception) { /* 無視 */ }
            captureSession = null
            cameraDevice = null

            try { videoEncoder?.drain(true) } catch (e: Exception) { /* 無視 */ }
            try { audioEncoder?.stop() } catch (e: Exception) { /* 無視 */ }
            try { videoEncoder?.release() } catch (e: Exception) { /* 無視 */ }
            try { glPipeline?.release() } catch (e: Exception) { /* 無視 */ }
            try { muxer?.release() } catch (e: Exception) { /* 無視 */ }
        } catch (e: Exception) {
            // 想定外の例外があっても、下のfinallyで後始末は必ず行う
        } finally {
            videoEncoder = null
            glPipeline = null
            audioEncoder = null
            muxer = null

            if (discardOutput) {
                try { tempFile?.delete() } catch (e: Exception) { /* 無視 */ }
                tempFile = null
            } else {
                saveToPrivateStorage()
            }

            postStateChanged()
            stopForeground(true)
            stopSelf()
        }
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

    private fun notifyError(message: String) {
        val listener = errorListener
        Handler(mainLooper).post { listener?.invoke(message) }
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
