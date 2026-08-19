package com.hono.bgrecorder

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import kotlin.math.abs

/**
 * カメラが実際にサポートしている出力サイズを調べるためのユーティリティ。
 *
 * これまで「1920x1080で撮る」と決め打ちでSurfaceTextureのバッファサイズを設定していたが、
 * 端末によってはカメラがそのサイズを直接サポートしておらず、内部的に別サイズで撮影した映像を
 * バッファへ引き伸ばして書き込む（＝録画結果が横に伸びる）ことがあった。
 * 必ず「カメラが対応している実際のサイズ」を選び、そのサイズをそのままエンコーダーにも
 * 使うことで、この引き伸ばしを防ぐ。
 */
object CameraSizeUtil {

    fun chooseSupportedSize(manager: CameraManager, cameraId: String, desiredWidth: Int, desiredHeight: Int): Size {
        val fallback = Size(desiredWidth, desiredHeight)
        return try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fallback
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            if (sizes == null || sizes.isEmpty()) return fallback

            // 1. 完全一致があればそれを使う
            sizes.firstOrNull { it.width == desiredWidth && it.height == desiredHeight }?.let { return it }

            // 2. 希望のアスペクト比に近いサイズの中から、希望の解像度に一番近い面積のものを選ぶ
            val desiredAspect = desiredWidth.toDouble() / desiredHeight.toDouble()
            val sameAspect = sizes.filter { s ->
                val a = s.width.toDouble() / s.height.toDouble()
                abs(a - desiredAspect) < 0.02
            }
            val pool = if (sameAspect.isNotEmpty()) sameAspect else sizes.toList()
            val desiredArea = desiredWidth.toLong() * desiredHeight.toLong()

            pool.minByOrNull { s -> abs((s.width.toLong() * s.height.toLong()) - desiredArea) } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /**
     * 解像度に応じたビットレート（1080p=6Mbpsを基準に画素数で比例させ、極端な値にならないようクランプ）。
     *
     * 以前は1080p=8Mbpsを基準にしていたが、動画の容量が大きすぎるとの要望を受けて引き下げた。
     * 画質そのものはビットレートの絶対値だけで決まるわけではなく、可変ビットレート（VBR、
     * VideoEncoderCore側で設定）と組み合わせることで、静止シーンでは自動的にビットレートを
     * 下げつつ動きの多いシーンではしっかり使う、という配分により、体感画質を保ちながら
     * 平均容量を抑えられる。
     */
    fun bitRateFor(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val basePixels = 1920L * 1080L
        val base = 6_000_000.0
        val scaled = base * (pixels.toDouble() / basePixels.toDouble())
        return scaled.toInt().coerceIn(2_000_000, 20_000_000)
    }
}
