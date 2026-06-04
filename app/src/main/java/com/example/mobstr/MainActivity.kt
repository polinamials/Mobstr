package com.example.mobstr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var cameraDevice: CameraDevice? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var nativeSurface: Surface? = null

    private external fun initCameraStream(ip: String, port: Int): Surface
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

        val startStreamBtn = findViewById<Button>(R.id.startStreamBtn)
        startStreamBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
            } else {
                if (startStreamBtn.text == "Start Streaming") {
                    startCameraPipeline()
                    startStreamBtn.text = "Stop Streaming"
                } else {
                    stopCameraPipeline()
                    startStreamBtn.text = "Start Streaming"
                }
            }
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val startStreamBtn = findViewById<Button>(R.id.startStreamBtn)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (startStreamBtn.text == "Start Streaming") {
                startCameraPipeline()
                startStreamBtn.text = "Stop Streaming"
            } else {
                stopCameraPipeline()
                startStreamBtn.text = "Start Streaming"
            }
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.CAMERA)
    private fun startCameraPipeline() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Initialize stream controller and get the GPU surface
        nativeSurface = initCameraStream("192.168.0.175", 5004)

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                captureBuilder.addTarget(nativeSurface!!)

                // Start camera capture in a background thread
                camera.createCaptureSession(listOf(nativeSurface!!), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        session.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler)

                        // Start the stream controller's polling loop
                        startCameraStream()

                        runOnUiThread { Toast.makeText(this@MainActivity, "Started Streaming!", Toast.LENGTH_SHORT).show() }
                    }
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {}
                }, backgroundHandler)
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    private fun stopCameraPipeline() {
        stopCameraStream()

        cameraDevice?.close()
        cameraDevice = null

        nativeSurface?.release()
        nativeSurface = null

        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        runOnUiThread { Toast.makeText(this@MainActivity, "Stopped Streaming!", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraPipeline()
    }
}