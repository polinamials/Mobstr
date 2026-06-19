package com.example.mobstr

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private var cameraDevice: CameraDevice? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var nativeSurface: Surface? = null
    private lateinit var startStreamBtn: Button
    private lateinit var recvIpTextInput: EditText
    private lateinit var recvPortNumInput: EditText
    private lateinit var mtuNumInput: EditText
    private lateinit var preferences: SharedPreferences

    external fun initCameraStream(ip: String, port: Int, mtu: Int): Surface
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

        startStreamBtn = findViewById<Button>(R.id.startStreamBtn)
        recvIpTextInput = findViewById<EditText>(R.id.recvIpTextInput)
        recvPortNumInput = findViewById<EditText>(R.id.recvPortNumInput)
        mtuNumInput = findViewById<EditText>(R.id.mtuNumInput)

        preferences = getSharedPreferences("MobstrPrefs", 0)

        val savedIp = preferences.getString("ip", "")
        val savedPort = preferences.getInt("port", 5004)
        val savedMtu = preferences.getInt("mtu", 1500)

        recvIpTextInput.setText(savedIp)
        recvPortNumInput.setText(savedPort.toString())
        mtuNumInput.setText(savedMtu.toString())

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

        recvIpTextInput.doAfterTextChanged { text ->
            preferences.edit {
                putString("ip", text.toString())
            }
        }

        recvPortNumInput.doAfterTextChanged { text ->
            val portValue = text.toString().trim().toIntOrNull() ?: 5004
            preferences.edit {
                putInt("port", portValue)
            }
        }

        mtuNumInput.doAfterTextChanged { text ->
            val mtuValue = text.toString().trim().toIntOrNull() ?: 1500
            preferences.edit {
                putInt("mtu", mtuValue)
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

        nativeSurface = initCameraStream(
            recvIpTextInput.text.toString(),
            recvPortNumInput.text.toString().toInt(),
            mtuNumInput.text.toString().toInt()
        )

        recvIpTextInput.isEnabled = false
        recvPortNumInput.isEnabled = false
        mtuNumInput.isEnabled = false

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
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

        recvIpTextInput.isEnabled = true
        recvPortNumInput.isEnabled = true
        mtuNumInput.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraPipeline()
    }
}