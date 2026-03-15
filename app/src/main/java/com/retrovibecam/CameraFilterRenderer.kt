package com.retrovibecam

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraFilterRenderer : GLSurfaceView.Renderer {

    var requestRender: (() -> Unit)? = null
    var onSurfaceReady: ((Surface, SurfaceTexture) -> Unit)? = null
    var onCapture: ((Bitmap) -> Unit)? = null

    @Volatile private var captureRequested = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    // EGL state (captured on GL thread in onSurfaceCreated)
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: android.opengl.EGLConfig? = null
    private var recorderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isRecording = false
    private var recorderWidth = 0
    private var recorderHeight = 0

    fun requestCapture() {
        captureRequested = true
        requestRender?.invoke()
    }

    /**
     * Returns true if the recorder EGL surface was created successfully.
     * Blocks the calling (background) thread via latch so recorder.start()
     * is only called after frames can flow.
     * Must be dispatched via glSurfaceView.queueEvent { }.
     */
    fun startRecordingOnGLThread(
        surface: Surface, width: Int, height: Int,
        latch: CountDownLatch, result: java.util.concurrent.atomic.AtomicBoolean
    ) {
        try {
            recorderWidth = width
            recorderHeight = height
            val config = chooseRecorderConfig() ?: run {
                android.util.Log.e("RetroVibeCam", "No compatible recorder EGL config found")
                return
            }
            val attribs = intArrayOf(EGL14.EGL_NONE)
            recorderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, attribs, 0)
            if (recorderEglSurface == EGL14.EGL_NO_SURFACE) {
                android.util.Log.e("RetroVibeCam", "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}")
                return
            }
            isRecording = true
            result.set(true)
        } finally {
            latch.countDown()
        }
    }

    /**
     * Returns the context's own EGL config for the recorder surface.
     * GLSurfaceView is configured with RecordableEGLConfigChooser (EGL_RECORDABLE_ANDROID=1),
     * so using the same config for the encoder surface guarantees eglMakeCurrent compatibility.
     */
    private fun chooseRecorderConfig(): android.opengl.EGLConfig? = eglConfig

    /** Called on the GL thread via glSurfaceView.queueEvent. No callback needed. */
    fun stopRecordingOnGLThread() {
        isRecording = false
        if (recorderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, recorderEglSurface)
            recorderEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // Palette state
    @Volatile private var pendingPalette: FloatArray? = null
    @Volatile private var pendingPassthrough: Boolean? = true
    private var currentPalette = FloatArray(PALETTE_SHADER_SIZE * 3)
    private var currentPassthrough = true
    private var paletteDirty = true

    fun setPalette(palette: Palette) {
        pendingPassthrough = palette.passthrough
        if (!palette.passthrough) pendingPalette = palette.toFlat()
        paletteDirty = true
    }

    private val vertexShader = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES sTexture;
        uniform sampler2D uBayerTexture;
        uniform vec3 uPalette[$PALETTE_SHADER_SIZE];
        uniform float uPassthrough;
        varying vec2 vTexCoord;

        float colourDistanceSq(vec3 e1, vec3 e2) {
            float rmean = (e1.r + e2.r) / 2.0;
            float r = e1.r - e2.r;
            float g = e1.g - e2.g;
            float b = e1.b - e2.b;
            return ((512.0 + rmean) * r * r) / 256.0
                 + 4.0 * g * g
                 + ((767.0 - rmean) * b * b) / 256.0;
        }

        void main() {
            if (uPassthrough > 0.5) {
                gl_FragColor = texture2D(sTexture, vTexCoord);
                return;
            }
            vec2 bayerCoord = mod(gl_FragCoord.xy, 4.0) / 4.0;
            float threshold = texture2D(uBayerTexture, bayerCoord).r - 0.5;
            vec3 c = clamp(texture2D(sTexture, vTexCoord).rgb * 255.0 + threshold * 64.0, 0.0, 255.0);
            vec3 result = uPalette[0];
            float minD = colourDistanceSq(c, uPalette[0]);
            for (int i = 1; i < $PALETTE_SHADER_SIZE; i++) {
                float d = colourDistanceSq(c, uPalette[i]);
                if (d < minD) { minD = d; result = uPalette[i]; }
            }
            gl_FragColor = vec4(result / 255.0, 1.0);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(
        -1f, -1f,  0f, 0f,
         1f, -1f,  1f, 0f,
        -1f,  1f,  0f, 1f,
         1f,  1f,  1f, 1f,
    )

    private var program = 0
    private var textureId = 0
    private var bayerTextureId = 0
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var surfaceTexture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false
    private val lock = Any()

    // Cached uniform and attribute locations — set once after program link
    private var locPosition = 0
    private var locTexCoord = 0
    private var locTexMatrix = 0
    private var locSTexture = 0
    private var locBayerTexture = 0
    private var locPassthrough = 0
    private var locPalette = 0

    // Pre-allocated capture buffer — resized in onSurfaceChanged
    private var captureBuffer: ByteBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Capture EGL state for later recorder surface creation
        eglDisplay = EGL14.eglGetCurrentDisplay()
        eglContext = EGL14.eglGetCurrentContext()
        // Query the config ID the context was created with, then retrieve that exact config.
        // Using the same config avoids EGL_BAD_MATCH when making the recorder surface current.
        val configId = IntArray(1)
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONFIG_ID, configId, 0)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay,
            intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE), 0,
            configs, 0, 1, numConfigs, 0
        )
        eglConfig = configs[0]

        // Camera OES texture (unit 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // 4×4 Bayer ordered-dithering matrix (unit 1)
        // Values 0–15 scaled to 0–255 (×17) so they map to [0.0, 1.0] as GL_LUMINANCE bytes.
        // threshold = sampled value − 0.5  →  [−0.5, 0.5)
        val bayerData = ByteBuffer.allocateDirect(16).put(byteArrayOf(
              0,  136.toByte(),  34, 170.toByte(),
            204.toByte(),   68, 238.toByte(), 102,
             51, 187.toByte(),  17, 153.toByte(),
            255.toByte(),  119, 221.toByte(),  85
        )).also { it.rewind() }
        GLES20.glGenTextures(1, ids, 0)
        bayerTextureId = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bayerTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 4, 4, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bayerData)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        program = createProgram(vertexShader, fragmentShader)

        // Cache all locations once — they are constant for the lifetime of the program
        locPosition     = GLES20.glGetAttribLocation(program, "aPosition")
        locTexCoord     = GLES20.glGetAttribLocation(program, "aTexCoord")
        locTexMatrix    = GLES20.glGetUniformLocation(program, "uTexMatrix")
        locSTexture     = GLES20.glGetUniformLocation(program, "sTexture")
        locBayerTexture = GLES20.glGetUniformLocation(program, "uBayerTexture")
        locPassthrough  = GLES20.glGetUniformLocation(program, "uPassthrough")
        locPalette      = GLES20.glGetUniformLocation(program, "uPalette[0]")

        val bb = ByteBuffer.allocateDirect(quadVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(quadVertices)
        vertexBuffer.position(0)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(lock) { frameAvailable = true }
            requestRender?.invoke()
        }

        val surface = Surface(surfaceTexture)
        Handler(Looper.getMainLooper()).post {
            onSurfaceReady?.invoke(surface, surfaceTexture)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        // Pre-allocate capture buffer for this surface size
        captureBuffer = ByteBuffer.allocateDirect(width * height * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingPassthrough?.let { currentPassthrough = it; pendingPassthrough = null }
        pendingPalette?.let { currentPalette = it; pendingPalette = null }

        synchronized(lock) {
            if (frameAvailable) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        // Draw to display
        drawScene(viewportWidth, viewportHeight)

        // Also draw to recorder surface if recording
        if (isRecording && recorderEglSurface != EGL14.EGL_NO_SURFACE) {
            val displaySurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            EGL14.eglMakeCurrent(eglDisplay, recorderEglSurface, recorderEglSurface, eglContext)
            drawScene(recorderWidth, recorderHeight)
            EGL14.eglSwapBuffers(eglDisplay, recorderEglSurface)
            EGL14.eglMakeCurrent(eglDisplay, displaySurface, displaySurface, eglContext)
        }

        // Screenshot capture (from display)
        if (captureRequested) {
            captureRequested = false
            val buffer = captureBuffer ?: return
            buffer.rewind()
            GLES20.glReadPixels(0, 0, viewportWidth, viewportHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val matrix = Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            bitmap.recycle()
            Handler(Looper.getMainLooper()).post { onCapture?.invoke(flipped) }
        }
    }

    private fun drawScene(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val stride = 4 * 4

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glEnableVertexAttribArray(locPosition)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glEnableVertexAttribArray(locTexCoord)

        GLES20.glUniformMatrix4fv(locTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(locSTexture, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bayerTextureId)
        GLES20.glUniform1i(locBayerTexture, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform1f(locPassthrough, if (currentPassthrough) 1f else 0f)

        if (paletteDirty) {
            GLES20.glUniform3fv(locPalette, PALETTE_SHADER_SIZE, currentPalette, 0)
            paletteDirty = false
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(locPosition)
        GLES20.glDisableVertexAttribArray(locTexCoord)
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vertSrc)
            GLES20.glCompileShader(it)
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fragSrc)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }
}
