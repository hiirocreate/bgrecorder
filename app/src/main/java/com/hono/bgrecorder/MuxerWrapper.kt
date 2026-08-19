package com.hono.bgrecorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * 映像・音声、2つのエンコーダーが別々のスレッドから書き込んでくるのを
 * 1つのMP4ファイルにまとめるためのラッパー。
 * MediaMuxerは「両方のトラックのフォーマットが確定してから start() を呼ぶ」
 * 必要があるため、その同期を担当する。
 */
class MuxerWrapper(outputPath: String) {
    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    @Volatile private var started = false
    private val lock = Object()

    var expectAudio = true

    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            videoFormat = format
            maybeStart()
        }
    }

    fun setAudioFormat(format: MediaFormat) {
        synchronized(lock) {
            audioFormat = format
            maybeStart()
        }
    }

    private fun maybeStart() {
        if (started) return
        val vf = videoFormat
        if (vf == null) return
        if (expectAudio && audioFormat == null) return

        videoTrackIndex = muxer.addTrack(vf)
        if (expectAudio) {
            audioTrackIndex = muxer.addTrack(audioFormat!!)
        }
        muxer.start()
        started = true
        lock.notifyAll()
    }

    fun isStarted(): Boolean = started

    fun writeVideoSample(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            while (!started) lock.wait(2000)
            if (videoTrackIndex >= 0) muxer.writeSampleData(videoTrackIndex, buffer, info)
        }
    }

    fun writeAudioSample(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            while (!started) lock.wait(2000)
            if (audioTrackIndex >= 0) muxer.writeSampleData(audioTrackIndex, buffer, info)
        }
    }

    fun release() {
        try {
            if (started) muxer.stop()
        } catch (e: Exception) {
            // すでに停止している等は無視
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            // 無視
        }
    }
}
