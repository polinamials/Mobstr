package com.example.mobstr

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.viewfinder.CameraViewfinder
import androidx.camera.viewfinder.ViewfinderSurfaceRequest
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private var cameraDevice: CameraDevice? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var nativeSurface: Surface? = null
    private var localPreviewSurface: Surface? = null

    private lateinit var startStreamBtn: Button
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var cameraSurfacePreview: CameraViewfinder
    private lateinit var preferences: SharedPreferences

    private var isStreaming = false
    private var isStarting = false
    private var nativeInitialized = false
    private var wasStreamingBeforeRotation = false

    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraCaptureBuilder: CaptureRequest.Builder? = null
    @Volatile private var latestCaptureResult: TotalCaptureResult? = null
    private val latestKnownValues = ConcurrentHashMap<String, Any>()
    @Volatile private var cameraControlsNeedRefresh = false
    @Volatile private var latestAwbGains: android.hardware.camera2.params.RggbChannelVector? = null
    @Volatile private var latestColorTransform: android.hardware.camera2.params.ColorSpaceTransform? = null
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            latestCaptureResult = result
            result.keys.forEach { resultKey ->
                @Suppress("UNCHECKED_CAST")
                result.get(resultKey as CaptureResult.Key<Any>)?.let { latestKnownValues[resultKey.name] = it }
            }
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { latestAwbGains = it }
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { latestColorTransform = it }
            if (cameraControlsNeedRefresh) {
                cameraControlsNeedRefresh = false
                runOnUiThread {
                    (supportFragmentManager.findFragmentByTag("f1") as? CameraControlsFragment)?.refreshFromCamera()
                }
            }
        }
    }

    private external fun initCameraStream(rtspPort: Int, mtu: Int, width: Int, height: Int, bitrate: Int): Surface?
    private external fun startCameraStream()
    private external fun stopCameraStream()
    private external fun getNativeDiagnostics(): String

    fun streamDiagnostics(): String = getNativeDiagnostics()

    @Suppress("UNCHECKED_CAST")
    fun currentCameraValue(key: CaptureRequest.Key<*>): Any? {
        latestCaptureResult?.let { result ->
            result.keys.firstOrNull { it.name == key.name }?.let {
                return result.get(it as CaptureResult.Key<Any>)
            }
        }
        latestKnownValues[key.name]?.let { return it }
        val resultOnly = key == CaptureRequest.SENSOR_EXPOSURE_TIME ||
            key == CaptureRequest.SENSOR_SENSITIVITY ||
            key == CaptureRequest.SENSOR_FRAME_DURATION ||
            key == CaptureRequest.LENS_FOCUS_DISTANCE ||
            key == CaptureRequest.COLOR_CORRECTION_GAINS ||
            key == CaptureRequest.COLOR_CORRECTION_TRANSFORM
        return if (resultOnly) null else cameraCaptureBuilder?.get(key as CaptureRequest.Key<Any>)
    }

    companion object {
        init {
            System.loadLibrary("mobstr")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = getSharedPreferences("MobstrPrefs", 0)
        startStreamBtn = findViewById(R.id.startStreamBtn)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        cameraSurfacePreview = findViewById(R.id.cameraSurfacePreview)

        if (savedInstanceState != null) {
            wasStreamingBeforeRotation = savedInstanceState.getBoolean("is_streaming_key", false)
        }

        setupViewfinder()

        viewPager.adapter = ViewPagerAdapter(this)

        setupTabsWithMediator()

        startStreamBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
            } else {
                toggleStreamingPipeline()
            }
        }
    }

    private fun setupTabsWithMediator() {
        tabLayout.post {
            if (!isDestroyed && !isFinishing) {
                val mediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = when (position) {
                        0 -> "Settings"
                        1 -> "Controls"
                        2 -> "Diagnostics"
                        else -> null
                    }
                }
                mediator.attach()
            }
        }
    }

    private fun setupViewfinder() {
        cameraSurfacePreview.post {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                val cameraId = preferredBackCameraId(cameraManager)
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                val streamWidth = preferences.getInt("stream_width", 1280)
                val streamHeight = preferences.getInt("stream_height", 720)
                val previewResolution = Size(streamWidth, streamHeight)

                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                val request = ViewfinderSurfaceRequest.Builder(previewResolution)
                    .setLensFacing(CameraMetadata.LENS_FACING_BACK)
                    .setSensorOrientation(sensorOrientation)
                    .setImplementationMode(CameraViewfinder.ImplementationMode.COMPATIBLE)
                    .build()

                val future = cameraSurfacePreview.requestSurfaceAsync(request)
                future.addListener({
                    try {
                        localPreviewSurface = future.get()

                        if (wasStreamingBeforeRotation && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            wasStreamingBeforeRotation = false
                            toggleStreamingPipeline()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(this))

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_streaming_key", isStreaming)
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    private fun toggleStreamingPipeline() {
        if (!isStreaming) {
            if (isStarting) return
            val port = preferences.getInt("rtsp_port", 8554).coerceIn(1024, 65535)
            val mtu = preferences.getInt("mtu", 1200).coerceIn(256, 1472)
            val streamWidth = preferences.getInt("stream_width", 1280)
            val streamHeight = preferences.getInt("stream_height", 720)
            val bitrate = preferences.getInt("bitrate_kbps", 8_000).coerceIn(128, 100_000) * 1_000
            if (localPreviewSurface == null) {
                Toast.makeText(this, "Preview surface is not ready yet.", Toast.LENGTH_SHORT).show()
                return
            }

            isStarting = true
            startStreamBtn.isEnabled = false
            startStreamBtn.text = "Starting…"
            toggleInputFields(false)
            startCameraPipeline(port, mtu, streamWidth, streamHeight, bitrate)
        } else {
            stopCameraPipeline()
            isStreaming = false
            startStreamBtn.text = "Start Streaming"
            toggleInputFields(true)
        }
    }

    private fun toggleInputFields(enabled: Boolean) {
        val currentFragment = supportFragmentManager.findFragmentByTag("f0")
        if (currentFragment is StreamSettingsFragment) {
            currentFragment.setInputFieldsEnabled(enabled)
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleStreamingPipeline()
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    private fun startCameraPipeline(port: Int, mtu: Int, width: Int, height: Int, bitrate: Int) {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        nativeSurface = initCameraStream(port, mtu, width, height, bitrate)
        if (nativeSurface == null) {
            failStart("Could not initialize the H.264 encoder or RTSP server.")
            return
        }
        nativeInitialized = true

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = preferredBackCameraId(cameraManager)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

                captureBuilder.addTarget(nativeSurface!!)
                captureBuilder.addTarget(localPreviewSurface!!)
                CameraControlStore(preferences).applyTo(captureBuilder, characteristics)

                val outputSurfaces = listOf(nativeSurface!!, localPreviewSurface!!)
                val sessionCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        cameraCaptureBuilder = captureBuilder
                        cameraControlsNeedRefresh = true

                        session.setRepeatingRequest(captureBuilder.build(), captureCallback, backgroundHandler)
                        startCameraStream()
                        runOnUiThread {
                            isStarting = false
                            isStreaming = true
                            startStreamBtn.isEnabled = true
                            startStreamBtn.text = "Stop Streaming"
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        failStart("Failed to configure the camera session.")
                    }
                }
                val executor = java.util.concurrent.Executor { command -> backgroundHandler?.post(command) }
                camera.createCaptureSession(SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputSurfaces.map(::OutputConfiguration),
                    executor,
                    sessionCallback,
                ))
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); failStart("Camera disconnected.") }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); failStart("Camera error: $error") }
        }, backgroundHandler)
    }

    private fun failStart(message: String) = runOnUiThread {
        stopCameraPipeline()
        isStarting = false
        isStreaming = false
        startStreamBtn.isEnabled = true
        startStreamBtn.text = "Start Streaming"
        toggleInputFields(true)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopCameraPipeline() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraCaptureBuilder = null
        latestCaptureResult = null
        cameraControlsNeedRefresh = false
        cameraDevice?.close()
        cameraDevice = null
        if (nativeInitialized) stopCameraStream()
        nativeInitialized = false
        nativeSurface?.release()
        nativeSurface = null
        backgroundThread?.quitSafely()
        if (Thread.currentThread() != backgroundThread) {
            runCatching { backgroundThread?.join(1_000) }
        }
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraPipeline()
    }

    fun <T> updateCameraParameter(key: CaptureRequest.Key<T>, value: T) {
        val store = CameraControlStore(preferences)
        value?.let { latestKnownValues[key.name] = it as Any }
        if (key == CaptureRequest.CONTROL_AE_MODE) {
            store.put(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        if (key == CaptureRequest.CONTROL_AWB_MODE) {
            store.put(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        val builder = cameraCaptureBuilder ?: return
        latestCaptureResult = null
        if (key == CaptureRequest.CONTROL_AE_MODE) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        if (key == CaptureRequest.CONTROL_AWB_MODE) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            if (value == CaptureRequest.CONTROL_AWB_MODE_OFF) {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                latestAwbGains?.let {
                    builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, it)
                    store.put(CaptureRequest.COLOR_CORRECTION_GAINS, it)
                }
                latestColorTransform?.let {
                    builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
                    store.put(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
                }
            } else {
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            }
        }
        if ((key == CaptureRequest.COLOR_CORRECTION_GAINS || key == CaptureRequest.COLOR_CORRECTION_TRANSFORM) &&
            store.getInt(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) == CaptureRequest.CONTROL_AWB_MODE_OFF) {
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        }
        builder.set(key, value)
        try {
            cameraCaptureSession?.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resetCameraParameters() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(preferredBackCameraId(manager))
        val retainedAutomaticValues = latestKnownValues.filterKeys { name ->
            name == CaptureRequest.SENSOR_EXPOSURE_TIME.name ||
                name == CaptureRequest.SENSOR_SENSITIVITY.name ||
                name == CaptureRequest.SENSOR_FRAME_DURATION.name ||
                name == CaptureRequest.LENS_FOCUS_DISTANCE.name ||
                name == CaptureRequest.COLOR_CORRECTION_GAINS.name ||
                name == CaptureRequest.COLOR_CORRECTION_TRANSFORM.name
        }
        latestKnownValues.clear()
        latestKnownValues.putAll(retainedAutomaticValues)
        val store = CameraControlStore(preferences)
        store.resetToDefaults(characteristics)
        latestKnownValues[CaptureRequest.CONTROL_AE_MODE.name] = CaptureRequest.CONTROL_AE_MODE_ON
        latestKnownValues[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION.name] = 0
        latestKnownValues[CaptureRequest.CONTROL_AE_LOCK.name] = false
        latestKnownValues[CaptureRequest.CONTROL_AWB_MODE.name] = CaptureRequest.CONTROL_AWB_MODE_AUTO
        latestKnownValues[CaptureRequest.CONTROL_AWB_LOCK.name] = false
        latestKnownValues[CaptureRequest.FLASH_MODE.name] = CaptureRequest.FLASH_MODE_OFF
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1f) == true) {
            latestKnownValues[CaptureRequest.CONTROL_ZOOM_RATIO.name] = 1f
        }

        val camera = cameraDevice ?: return
        val session = cameraCaptureSession ?: return
        val encoderSurface = nativeSurface ?: return
        val previewSurface = localPreviewSurface ?: return
        runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encoderSurface)
                addTarget(previewSurface)
            }
            store.applyTo(builder, characteristics)
            cameraCaptureBuilder = builder
            latestCaptureResult = null
            cameraControlsNeedRefresh = true
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        }
    }

    private inner class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StreamSettingsFragment()
                1 -> CameraControlsFragment()
                2 -> DiagnosticsFragment()
                else -> throw IllegalArgumentException("Invalid layout space placement")
            }
        }
    }
}
