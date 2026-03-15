package com.retrovibecam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.animation.ObjectAnimator
import android.view.Surface
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var btnRecord: ImageButton
    private lateinit var btnLock: ImageButton
    private var isLocked = false
    private val renderer = CameraFilterRenderer()
    private lateinit var palettes: List<Palette>
    private var currentPaletteName: String = ""

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var glSurface: Surface? = null
    private var glSurfaceTexture: SurfaceTexture? = null

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    // API 29+: MediaStore URI to finalise after recording
    private var recordingUri: Uri? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    // API < 29: file written to public Movies dir
    private var recordingFile: File? = null

    private lateinit var recordingIndicator: View
    private lateinit var recordingDot: View
    private lateinit var tvRecordingTime: TextView
    private var dotAnimator: ObjectAnimator? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private val timerTick = object : Runnable {
        override fun run() {
            recordingSeconds++
            val m = recordingSeconds / 60
            val s = recordingSeconds % 60
            tvRecordingTime.text = "%02d:%02d".format(m, s)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val requestCameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setupCamera()
            else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }

    private val requestStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) renderer.requestCapture()
            else Toast.makeText(this, "Storage permission denied", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        palettes = loadPalettes(this)
        currentPaletteName = palettes.firstOrNull()?.name?.replace(" ", "_") ?: "NoFilter"

        setSupportActionBar(findViewById(R.id.toolbar))
        recordingIndicator = findViewById(R.id.recordingIndicator)
        recordingDot = findViewById(R.id.recordingDot)
        tvRecordingTime = findViewById(R.id.tvRecordingTime)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(RecordableEGLConfigChooser())
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        renderer.requestRender = { glSurfaceView.requestRender() }
        renderer.onCapture = { bitmap -> saveImageToGallery(bitmap) }

        val rvPalettes = findViewById<RecyclerView>(R.id.rvPalettes)
        val paletteAdapter = PaletteStripAdapter(palettes) { index ->
            renderer.setPalette(palettes[index])
            currentPaletteName = palettes[index].name.replace(" ", "_")
        }
        rvPalettes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvPalettes.adapter = paletteAdapter

        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                renderer.requestCapture()
            }
        }

        btnRecord = findViewById(R.id.btnRecord)
        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        btnLock = findViewById(R.id.btnLock)
        btnLock.setOnClickListener { toggleExposureLock() }

        findViewById<ImageButton>(R.id.btnFlip).setOnClickListener {
            flipCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            requestCameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Video recording ──────────────────────────────────────────────────────

    private fun startRecording() {
        val w = glSurfaceView.width
        val h = glSurfaceView.height
        if (w == 0 || h == 0) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        // Encoders require dimensions that are multiples of 16
        val videoW = (w / 16) * 16
        val videoH = (h / 16) * 16
        if (videoW == 0 || videoH == 0) {
            Toast.makeText(this, "Camera view too small to record", Toast.LENGTH_SHORT).show()
            return
        }

        btnRecord.isEnabled = false

        // Run blocking setup (MediaStore I/O + codec init) off the main thread
        Thread {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }

            var uri: Uri? = null
            var pfd: ParcelFileDescriptor? = null
            var file: File? = null

            try {
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(videoW, videoH)
                recorder.setVideoFrameRate(30)
                recorder.setVideoEncodingBitRate(4_000_000)
                recorder.setMaxDuration(300_000)

                val filename = "RetroVibeCam_${currentPaletteName}_${System.currentTimeMillis()}.mp4"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RetroVibeCam")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("MediaStore insert returned null")
                    pfd = contentResolver.openFileDescriptor(uri, "w")
                        ?: throw IOException("Could not open file descriptor")
                    recorder.setOutputFile(pfd.fileDescriptor)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    dir.mkdirs()
                    file = File(dir, filename)
                    recorder.setOutputFile(file.absolutePath)
                }

                recorder.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        runOnUiThread { stopRecording() }
                    }
                }
                recorder.setOnErrorListener { _, what, extra ->
                    Log.e("RetroVibeCam", "MediaRecorder error what=$what extra=$extra")
                    runOnUiThread { stopRecording() }
                }
                recorder.prepare()
            } catch (e: Exception) {
                Log.e("RetroVibeCam", "MediaRecorder prepare failed: ${e.message}", e)
                recorder.release()
                uri?.let { contentResolver.delete(it, null, null) }
                pfd?.close()
                file?.delete()
                runOnUiThread {
                    btnRecord.isEnabled = true
                    btnRecord.setImageResource(R.drawable.ic_record)
                    Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // prepare() succeeded — wait for GL thread to create the EGL recorder surface,
            // then only start the recorder if that succeeded.
            val recorderSurface = recorder.surface
            val latch = CountDownLatch(1)
            val eglReady = AtomicBoolean(false)
            glSurfaceView.queueEvent {
                renderer.startRecordingOnGLThread(recorderSurface, videoW, videoH, latch, eglReady)
            }
            latch.await()

            if (!eglReady.get()) {
                recorder.release()
                uri?.let { contentResolver.delete(it, null, null) }
                pfd?.close()
                file?.delete()
                runOnUiThread {
                    btnRecord.isEnabled = true
                    btnRecord.setImageResource(R.drawable.ic_record)
                    Toast.makeText(this, "Recording failed: EGL surface error", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            recorder.start()

            runOnUiThread {
                mediaRecorder = recorder
                recordingUri = uri
                recordingPfd = pfd
                recordingFile = file
                isRecording = true
                btnRecord.isEnabled = true
                btnRecord.setImageResource(R.drawable.ic_stop)
                recordingSeconds = 0
                tvRecordingTime.text = "00:00"
                recordingIndicator.visibility = View.VISIBLE
                dotAnimator = ObjectAnimator.ofFloat(recordingDot, "alpha", 1f, 0f).apply {
                    duration = 800
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
                timerHandler.postDelayed(timerTick, 1000)
            }
        }.start()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        btnRecord.setImageResource(R.drawable.ic_record)
        timerHandler.removeCallbacks(timerTick)
        dotAnimator?.cancel()
        dotAnimator = null
        recordingDot.alpha = 1f
        recordingIndicator.visibility = View.GONE

        // Tell GL thread to stop rendering to recorder surface (fire and forget)
        glSurfaceView.queueEvent { renderer.stopRecordingOnGLThread() }

        // Stop and finalise recorder synchronously on the main thread
        var success = false
        try {
            mediaRecorder?.stop()
            success = true
        } catch (e: RuntimeException) {
            Log.e("RetroVibeCam", "MediaRecorder stop failed", e)
        }
        mediaRecorder?.release()
        mediaRecorder = null

        if (success) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recordingUri?.let { uri ->
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, "Video saved to gallery", Toast.LENGTH_SHORT).show()
                }
            } else {
                recordingFile?.let { file ->
                    MediaScannerConnection.scanFile(
                        this, arrayOf(file.absolutePath), arrayOf("video/mp4")
                    ) { _, _ ->
                        runOnUiThread {
                            Toast.makeText(this, "Video saved to gallery", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            recordingUri?.let { contentResolver.delete(it, null, null) }
            recordingFile?.delete()
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
        }

        recordingPfd?.close(); recordingPfd = null
        recordingUri = null
        recordingFile = null
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    private fun saveImageToGallery(bitmap: Bitmap) {
        val filename = "RetroVibeCam_${currentPaletteName}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RetroVibeCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            Toast.makeText(this, "Photo saved to gallery", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("RetroVibeCam", "Save failed", e)
            Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
        } finally {
            bitmap.recycle()
        }
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private fun setupCamera() {
        renderer.onSurfaceReady = { surface, surfaceTexture ->
            glSurface = surface
            glSurfaceTexture = surfaceTexture
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    cameraSelector = when {
                        cameraProvider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider!!.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> throw IllegalStateException("No camera available")
                    }
                    bindCamera()
                } catch (e: Exception) {
                    Log.e("RetroVibeCam", "Camera setup failed", e)
                    Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val surface = glSurface ?: return
        val surfaceTexture = glSurfaceTexture ?: return
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request: SurfaceRequest ->
            surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
        }
        try {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(this, cameraSelector, preview)
            isLocked = false
            updateLockUI()
        } catch (e: Exception) {
            Log.e("RetroVibeCam", "Camera bind failed", e)
            Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLockUI() {
        if (isLocked) {
            btnLock.setImageResource(R.drawable.ic_lock_closed)
            btnLock.setBackgroundResource(R.drawable.bg_lock_button_active)
        } else {
            btnLock.setImageResource(R.drawable.ic_lock_open)
            btnLock.setBackgroundResource(R.drawable.bg_icon_button)
        }
    }

    private fun toggleExposureLock() {
        val camera = activeCamera ?: return
        isLocked = !isLocked
        try {
            Camera2CameraControl.from(camera.cameraControl)
                .captureRequestOptions = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, isLocked)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, isLocked)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                    .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                    .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    .build()
        } catch (e: Exception) {
            Log.w("RetroVibeCam", "Could not set exposure lock: ${e.message}")
            isLocked = !isLocked
        }
        updateLockUI()
    }

    private fun flipCamera() {
        val provider = cameraProvider ?: return
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA &&
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        ) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else return
        bindCamera()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) stopRecording()
        glSurfaceView.onPause()
    }

    /**
     * Chooses an EGL config with EGL_RECORDABLE_ANDROID so the same context config
     * can be used for both the display surface and the MediaRecorder encoder surface,
     * preventing EGL_BAD_MATCH in eglMakeCurrent.
     */
    private inner class RecordableEGLConfigChooser : GLSurfaceView.EGLConfigChooser {
        private val EGL_RENDERABLE_TYPE = 0x3040
        private val EGL_OPENGL_ES2_BIT = 4
        private val EGL_RECORDABLE_ANDROID = 0x3142

        override fun chooseConfig(
            egl: javax.microedition.khronos.egl.EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay
        ): javax.microedition.khronos.egl.EGLConfig {
            return tryChoose(egl, display, intArrayOf(EGL_RECORDABLE_ANDROID, 1,
                        javax.microedition.khronos.egl.EGL10.EGL_NONE))
                ?: tryChoose(egl, display, intArrayOf(javax.microedition.khronos.egl.EGL10.EGL_NONE))
                ?: throw RuntimeException("No compatible EGL config found")
        }

        private fun tryChoose(
            egl: javax.microedition.khronos.egl.EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay,
            extra: IntArray
        ): javax.microedition.khronos.egl.EGLConfig? {
            val attribs = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                javax.microedition.khronos.egl.EGL10.EGL_SURFACE_TYPE,
                javax.microedition.khronos.egl.EGL10.EGL_WINDOW_BIT,
            ) + extra
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val num = IntArray(1)
            egl.eglChooseConfig(display, attribs, configs, 1, num)
            return if (num[0] > 0) configs[0] else null
        }
    }
}
