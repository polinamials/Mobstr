package com.example.mobstr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import java.net.Inet4Address
import java.net.NetworkInterface

class StreamSettingsFragment : Fragment() {
    private lateinit var rtspUrl: TextView
    private lateinit var rtspPort: EditText
    private lateinit var mtu: EditText
    private lateinit var bitrate: EditText
    private lateinit var resolution: Spinner
    private lateinit var preferences: SharedPreferences
    private var sizes = emptyList<Size>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_stream_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        preferences = requireActivity().getSharedPreferences("MobstrPrefs", 0)
        rtspUrl = view.findViewById(R.id.rtspUrlText)
        rtspPort = view.findViewById(R.id.rtspPortInput)
        mtu = view.findViewById(R.id.mtuNumInput)
        bitrate = view.findViewById(R.id.bitrateInput)
        resolution = view.findViewById(R.id.resolutionSpinner)
        migrateLegacyBitrateDefault()
        rtspPort.setText(preferences.getInt("rtsp_port", 8554).toString())
        mtu.setText(preferences.getInt("mtu", 1200).toString())
        bitrate.setText(preferences.getInt("bitrate_kbps", 8_000).toString())
        updateUrl()
        rtspPort.doAfterTextChanged {
            val value = it.toString().toIntOrNull()?.takeIf { value -> value in 1024..65535 } ?: return@doAfterTextChanged
            preferences.edit { putInt("rtsp_port", value) }
            updateUrl()
        }
        mtu.doAfterTextChanged {
            val value = it.toString().toIntOrNull()?.takeIf { value -> value in 256..1472 } ?: return@doAfterTextChanged
            preferences.edit { putInt("mtu", value) }
        }
        bitrate.doAfterTextChanged {
            val value = it.toString().toIntOrNull()?.takeIf { value -> value in 128..100_000 } ?: return@doAfterTextChanged
            preferences.edit { putInt("bitrate_kbps", value) }
        }
        setupResolutions()
    }

    private fun migrateLegacyBitrateDefault() {
        if (preferences.getBoolean("bitrate_default_v2_migrated", false)) return
        val usedLegacyDefault = preferences.getInt("bitrate_kbps", 2_000) == 2_000
        preferences.edit {
            if (usedLegacyDefault) putInt("bitrate_kbps", 8_000)
            putBoolean("bitrate_default_v2_migrated", true)
        }
    }

    private fun updateUrl() {
        val port = rtspPort.text.toString().toIntOrNull() ?: 8554
        rtspUrl.text = "rtsp://${localIpv4()}:$port/live"
    }

    private fun localIpv4(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
    }.getOrNull() ?: "phone-ip"

    private fun setupResolutions() {
        val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        sizes = map.getOutputSizes(ImageFormat.PRIVATE)?.distinct()?.sortedByDescending { it.width.toLong() * it.height } ?: return
        resolution.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
            sizes.map { "${it.width}×${it.height}" }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val width = preferences.getInt("stream_width", 1280)
        val height = preferences.getInt("stream_height", 720)
        resolution.setSelection(sizes.indexOfFirst { it.width == width && it.height == height }.coerceAtLeast(0), false)
        resolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preferences.edit { putInt("stream_width", sizes[position].width); putInt("stream_height", sizes[position].height) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    fun setInputFieldsEnabled(enabled: Boolean) {
        if (::rtspPort.isInitialized) listOf(rtspPort, mtu, bitrate, resolution).forEach { it.isEnabled = enabled }
    }
}
