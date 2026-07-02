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
import android.os.Bundle
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
    private var wasStreamingBeforeRotation = false

    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraCaptureBuilder: CaptureRequest.Builder? = null

    external fun initCameraStream(ip: String, port: Int, mtu: Int, width: Int, height: Int): Surface
    private external fun startCameraStream()
    private external fun stopCameraStream()

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
                val cameraId = cameraManager.cameraIdList[0]
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
            val ip = preferences.getString("ip", "") ?: ""
            val port = preferences.getInt("port", 5004)
            val mtu = preferences.getInt("mtu", 1500)
            val streamWidth = preferences.getInt("stream_width", 1280)
            val streamHeight = preferences.getInt("stream_height", 720)

            if (ip.isEmpty()) {
                Toast.makeText(this, "Please configure a valid IP address first.", Toast.LENGTH_SHORT).show()
                return
            }
            if (localPreviewSurface == null) {
                Toast.makeText(this, "Preview surface is not ready yet.", Toast.LENGTH_SHORT).show()
                return
            }

            startCameraPipeline(ip, port, mtu, streamWidth, streamHeight)
            isStreaming = true
            startStreamBtn.text = "Stop Streaming"
            toggleInputFields(false)
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
    private fun startCameraPipeline(ip: String, port: Int, mtu: Int, width: Int, height: Int) {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        nativeSurface = initCameraStream(ip, port, mtu, width, height)

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

                captureBuilder.addTarget(nativeSurface!!)
                captureBuilder.addTarget(localPreviewSurface!!)

                val outputSurfaces = listOf(nativeSurface!!, localPreviewSurface!!)
                camera.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        cameraCaptureBuilder = captureBuilder

                        session.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler)
                        startCameraStream()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Failed to configure session.", Toast.LENGTH_SHORT).show() }
                    }
                }, backgroundHandler)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    private fun stopCameraPipeline() {
        stopCameraStream()
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraCaptureBuilder = null
        cameraDevice?.close()
        cameraDevice = null
        nativeSurface?.release()
        nativeSurface = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraPipeline()
    }

    fun <T> updateCameraParameter(key: CaptureRequest.Key<T>, value: T) {
        cameraCaptureBuilder?.set(key, value)
        try {
            cameraCaptureBuilder?.let { builder ->
                cameraCaptureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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