package com.hono.bgrecorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** フィルターの種類 */
object FilterMode {
    const val NORMAL = 0
    const val NIGHT = 1
    const val LANDSCAPE = 2
}

private const val VERTEX_SHADER = """
    uniform mat4 uSTMatrix;
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    varying vec2 vTextureCoord;
    void main() {
        gl_Position = aPosition;
        vTextureCoord = (uSTMatrix * aTextureCoord).xy;
    }
"""

private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform samplerExternalOES sTexture;
    uniform int uFilterMode;

    vec3 applySaturation(vec3 color, float sat) {
        float gray = dot(color, vec3(0.299, 0.587, 0.114));
        return mix(vec3(gray), color, sat);
    }

    void main() {
        vec4 c = texture2D(sTexture, vTextureCoord);
        vec3 rgb = c.rgb;
        if (uFilterMode == 1) {
            // 暗所用: 明るさ・コントラストを上げる
            rgb = rgb * 1.35;
            rgb = (rgb - 0.5) * 1.15 + 0.5;
            rgb = applySaturation(rgb, 1.1);
        } else if (uFilterMode == 2) {
            // 風景用: 彩度・コントラストを上げる
            rgb = (rgb - 0.5) * 1.12 + 0.5;
            rgb = applySaturation(rgb, 1.4);
        }
        gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
    }
"""

private val QUAD_COORDS = floatArrayOf(
    -1f, -1f, 0f,
    1f, -1f, 0f,
    -1f, 1f, 0f,
    1f, 1f, 0f
)
private val TEX_COORDS = floatArrayOf(
    0f, 0f,
    1f, 0f,
    0f, 1f,
    1f, 1f
)

/**
 * カメラの映像（OESテクスチャ）をフィルター付きで、動画エンコーダーの入力Surfaceへ
 * 描画するためのGLパイプライン。EGL・シェーダーのセットアップと毎フレームの描画を担当する。
 */
class GLFilterPipeline(
    private val encoderSurface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0
    private val stMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var cameraInputSurface: Surface
        private set

    init {
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_COORDS); position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(TEX_COORDS); position(0) }

        setupEgl()
        makeCurrent()
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        textureId = createOesTexture()

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        cameraInputSurface = Surface(surfaceTexture)
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw RuntimeException("eglChooseConfig failed")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile failed: $log")
        }
        return shader
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            throw RuntimeException("program link failed: $log")
        }
        return prog
    }

    /** カメラから新しいフレームが来たときに呼ぶ。エンコーダーのSurfaceへ描画する。 */
    fun drawFrame(filterMode: Int, presentationTimeNanos: Long) {
        makeCurrent()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        val matrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val filterLoc = GLES20.glGetUniformLocation(program, "uFilterMode")
        val samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glUniformMatrix4fv(matrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1i(filterLoc, filterMode)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNanos)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        try {
            surfaceTexture.release()
        } catch (e: Exception) { /* 無視 */ }
        try {
            cameraInputSurface.release()
        } catch (e: Exception) { /* 無視 */ }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}
