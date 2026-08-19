package com.hono.bgrecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * カメラ映像（GLで描画したフレーム）をエンコードするクラス。
 * GLの描画先として `inputSurface` を使う（MediaCodecの「Surface入力」モード）。
 *
 * 画質を保ったままファイルサイズを抑えるため、端末が対応していればH.265(HEVC)を使う。
 * ただし「HEVCのエンコーダーが存在する」ことと「今回の解像度・ビットレートで実際に
 * configureが通る」ことは別なので、実際にconfigureまで試してみて、失敗したら
 * その場でH.264(AVC)に切り替える（＝ここで例外を外に漏らさないことが重要。
 * 漏らすとバックグラウンドスレッドで拾われずアプリごと落ちる）。
 */
class VideoEncoderCore(
    width: Int,
    height: Int,
    requestedBitRate: Int,
    private val muxer: MuxerWrapper,
) {
    val inputSurface: Surface
    private val encoder: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var eosSent = false
    val usingHevc: Boolean

    companion object {
        // HEVCはH.264より同じ画質をより低いビットレートで出せるため、H.264換算のビットレートに
        // この係数をかけて使う。容量をもう少し抑えたいという要望を受けて0.6→0.55に引き下げた。
        private const val HEVC_BITRATE_SCALE = 0.55
    }

    private class Built(val codec: MediaCodec, val surface: Surface, val hevc: Boolean)

    init {
        // HEVC→AVCの順で試し、それぞれ「可変ビットレート(VBR)あり」→「なし」の順で試す。
        // VBRは静止シーンでビットレートを自動的に下げてくれるため、体感画質を保ったまま
        // 平均ファイルサイズを抑えられる（ほとんどの機種で対応しているが、稀に非対応の
        // 端末・エンコーダーがあるため、その場合は自動的にVBR無しにフォールバックする）。
        val built = tryBuild(width, height, requestedBitRate, preferHevc = true, useVbr = true)
            ?: tryBuild(width, height, requestedBitRate, preferHevc = true, useVbr = false)
            ?: tryBuild(width, height, requestedBitRate, preferHevc = false, useVbr = true)
            ?: tryBuild(width, height, requestedBitRate, preferHevc = false, useVbr = false)
            ?: throw RuntimeException("H.264エンコーダーの初期化にも失敗しました")
        encoder = built.codec
        inputSurface = built.surface
        usingHevc = built.hevc
        encoder.start()
    }

    private fun tryBuild(width: Int, height: Int, requestedBitRate: Int, preferHevc: Boolean, useVbr: Boolean): Built? {
        val mimeType = if (preferHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val effectiveBitRate = if (preferHevc) (requestedBitRate * HEVC_BITRATE_SCALE).toInt() else requestedBitRate

        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (useVbr) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
            }
            val c = MediaCodec.createEncoderByType(mimeType)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            Built(c, surface, preferHevc)
        } catch (e: Exception) {
            try {
                codec?.release()
            } catch (e2: Exception) {
                // 無視
            }
            null
        }
    }

    /** エンコーダーの出力キューにたまったデータをmuxerに書き込む。定期的に呼ぶ。 */
    fun drain(endOfStream: Boolean) {
        if (endOfStream) {
            try {
                encoder.signalEndOfInputStream()
            } catch (e: Exception) {
                // 無視（すでに停止している場合など）
            }
        }

        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxer.setVideoFormat(encoder.outputFormat)
                }
                outIndex >= 0 -> {
                    val outBuffer = encoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeVideoSample(outBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    fun release() {
        try {
            encoder.stop()
        } catch (e: Exception) {
            // 無視
        }
        encoder.release()
    }
}
