package com.example.mobstr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment

class StreamSettingsFragment : Fragment() {

    private lateinit var recvIpTextInput: EditText
    private lateinit var recvPortNumInput: EditText
    private lateinit var mtuNumInput: EditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var preferences: SharedPreferences

    private var supportedResolutions: List<Size> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_stream_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recvIpTextInput = view.findViewById(R.id.recvIpTextInput)
        recvPortNumInput = view.findViewById(R.id.recvPortNumInput)
        mtuNumInput = view.findViewById(R.id.mtuNumInput)
        resolutionSpinner = view.findViewById(R.id.resolutionSpinner)

        preferences = requireActivity().getSharedPreferences("MobstrPrefs", 0)

        recvIpTextInput.setText(preferences.getString("ip", ""))
        recvPortNumInput.setText(preferences.getInt("port", 5004).toString())
        mtuNumInput.setText(preferences.getInt("mtu", 1500).toString())

        recvIpTextInput.doAfterTextChanged { text ->
            preferences.edit { putString("ip", text.toString().trim()) }
        }
        recvPortNumInput.doAfterTextChanged { text ->
            val portValue = text.toString().trim().toIntOrNull() ?: 5004
            preferences.edit { putInt("port", portValue) }
        }
        mtuNumInput.doAfterTextChanged { text ->
            val mtuValue = text.toString().trim().toIntOrNull() ?: 1500
            preferences.edit { putInt("mtu", mtuValue) }
        }

        setupResolutionDropdown()
    }

    private fun setupResolutionDropdown() {
        try {
            val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val map: StreamConfigurationMap? = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            if (map != null) {
                val sizes = map.getOutputSizes(ImageFormat.PRIVATE)

                if (sizes != null) {
                    supportedResolutions = sizes.sortedWith(Comparator { s1, s2 ->
                        (s2.width * s2.height).compareTo(s1.width * s1.height)
                    })

                    val resolutionStrings = supportedResolutions.map { "${it.width}x${it.height}" }

                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        resolutionStrings
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    resolutionSpinner.adapter = adapter

                    val savedWidth = preferences.getInt("stream_width", 1280)
                    val savedHeight = preferences.getInt("stream_height", 720)
                    val matchingIndex = supportedResolutions.indexOfFirst { it.width == savedWidth && it.height == savedHeight }

                    if (matchingIndex != -1) {
                        resolutionSpinner.setSelection(matchingIndex)
                    }

                    resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val selectedSize = supportedResolutions[position]
                            preferences.edit {
                                putInt("stream_width", selectedSize.width)
                                putInt("stream_height", selectedSize.height)
                            }
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setInputFieldsEnabled(enabled: Boolean) {
        if (::recvIpTextInput.isInitialized) {
            recvIpTextInput.isEnabled = enabled
            recvPortNumInput.isEnabled = enabled
            mtuNumInput.isEnabled = enabled
            resolutionSpinner.isEnabled = enabled
        }
    }
}