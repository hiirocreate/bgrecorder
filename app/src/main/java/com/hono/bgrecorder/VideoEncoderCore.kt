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
 * 対応していない端末では自動的にH.264(AVC)にフォールバックする。
 * H.265は同じビットレートでもH.264より高効率なので、H.265使用時はビットレートを
 * 下げてファイルサイズを縮小しつつ、体感画質はH.264のフルビットレートと同等になるようにしている。
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
        private const val HEVC_BITRATE_SCALE = 0.6

        private fun hevcSupported(): Boolean {
            return try {
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                codec.release()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    init {
        val hevcOk = hevcSupported()
        usingHevc = hevcOk
        val mimeType = if (hevcOk) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val effectiveBitRate = if (hevcOk) (requestedBitRate * HEVC_BITRATE_SCALE).toInt() else requestedBitRate

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
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
