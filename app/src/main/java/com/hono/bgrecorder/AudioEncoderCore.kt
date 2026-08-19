package com.hono.bgrecorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder

/**
 * マイクからPCM音声を取り込み、AACにエンコードしてmuxerに書き込むクラス。
 * 独自スレッドでループし、一時停止中はマイクから読んだデータを捨てて
 * エンコーダーには渡さない（＝タイムスタンプは「録画が進んだ分だけ」進む）。
 */
class AudioEncoderCore(private val muxer: MuxerWrapper) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 128_000
    }

    private val encoder: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var paused = false

    // 録音済みサンプル数から算出する再生位置（一時停止中は増えないので、
    // 一時停止しても音声と映像がズレにくい）
    private var totalSamplesWritten = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else SAMPLE_RATE
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        audioRecord?.startRecording()
        running = true
        thread = Thread(this::loop, "AudioEncoderThread").apply { start() }
    }

    private fun loop() {
        val pcmBuf = ByteArray(4096)
        while (running) {
            val ar = audioRecord ?: break
            val read = ar.read(pcmBuf, 0, pcmBuf.size)
            if (read <= 0) continue
            if (paused) continue // 一時停止中は読み捨てる（タイムスタンプを進めない）

            feedEncoder(pcmBuf, read)
            drainEncoder(false)
        }
        // 終了処理：残りをフラッシュしてEOSを送る
        drainEncoder(true)
    }

    private fun feedEncoder(data: ByteArray, len: Int) {
        val inIndex = encoder.dequeueInputBuffer(10_000)
        if (inIndex < 0) return
        val inBuffer = encoder.getInputBuffer(inIndex) ?: return
        inBuffer.clear()
        inBuffer.put(data, 0, len)

        val samples = len / 2 // 16bit mono
        val ptsUs = totalSamplesWritten * 1_000_000L / SAMPLE_RATE
        totalSamplesWritten += samples

        encoder.queueInputBuffer(inIndex, 0, len, ptsUs, 0)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            val inIndex = encoder.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxer.setAudioFormat(encoder.outputFormat)
                }
                outIndex >= 0 -> {
                    val outBuffer = encoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeAudioSample(outBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // 無視
        }
        try {
            encoder.stop()
        } catch (e: Exception) {
            // 無視
        }
        encoder.release()
    }
}
