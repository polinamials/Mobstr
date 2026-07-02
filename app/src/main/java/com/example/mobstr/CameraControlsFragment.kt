package com.example.mobstr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment

class CameraControlsFragment : Fragment() {

    private lateinit var controlsContainer: GridLayout
    private lateinit var characteristics: CameraCharacteristics

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera_controls, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controlsContainer = view.findViewById(R.id.controlsContainer)

        setupCameraCharacteristics()
        generateDynamicUi()
    }

    private fun setupCameraCharacteristics() {
        try {
            val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateDynamicUi() {
        if (!::characteristics.isInitialized) return
        controlsContainer.removeAllViews()

        val context = requireContext()
        var currentRow = 0

        for (param in cameraSettingsRegistry) {
            val labelView = TextView(context).apply {
                text = param.label
                textSize = 14f
                setPadding(0, 16, 16, 16)
            }

            val labelParams = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(currentRow)
                columnSpec = GridLayout.spec(0)
                setGravity(android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START)
            }
            controlsContainer.addView(labelView, labelParams)

            val rightHandView: View = when (param) {
                is CameraParam.RangeParam -> {
                    val hardwareRange = characteristics.get(param.characteristicKey) ?: continue

                    SeekBar(context).apply {
                        max = (hardwareRange.upper as Number).toInt()
                        min = (hardwareRange.lower as Number).toInt()
                        progress = min

                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                                labelView.text = "${param.label}: $progress"

                                if (fromUser) {
                                    val valueToApply = when (hardwareRange.lower) {
                                        is Long -> progress.toLong()
                                        is Float -> progress.toFloat()
                                        else -> progress
                                    } as Any

                                    (activity as? MainActivity)?.updateCameraParameter(
                                        param.key as CaptureRequest.Key<Any>,
                                        valueToApply
                                    )
                                }
                            }
                            override fun onStartTrackingTouch(sb: SeekBar?) {}
                            override fun onStopTrackingTouch(sb: SeekBar?) {}
                        })
                    }
                }

                is CameraParam.EnumParam<*, *> -> {
                    val availableModes = characteristics.get(param.characteristicKey) ?: continue
                    val modesList = when (availableModes) {
                        is IntArray -> availableModes.toTypedArray()
                        is FloatArray -> availableModes.toTypedArray()
                        is Array<*> -> availableModes
                        else -> emptyArray()
                    }

                    val stringLabels = modesList.map { it.toString() }
                    val spinner = Spinner(context)
                    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, stringLabels)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinner.adapter = adapter

                    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                            val selectedRawMode = modesList[pos] as Any
                            (activity as? MainActivity)?.updateCameraParameter(
                                param.key as CaptureRequest.Key<Any>,
                                selectedRawMode
                            )
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                    spinner
                }

                is CameraParam.BoolParam -> {
                    val isSupported = characteristics.get(param.characteristicKey) as? Boolean ?: true

                    CheckBox(context).apply {
                        text = "Enabled"
                        isEnabled = isSupported
                        setOnCheckedChangeListener { _, isChecked ->
                            val valueToApply: Any = if (isChecked) 1 else 0
                            (activity as? MainActivity)?.updateCameraParameter(
                                param.key as CaptureRequest.Key<Any>,
                                valueToApply
                            )
                        }
                    }
                }
            }

            val controlParams = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(currentRow)
                columnSpec = GridLayout.spec(1)
                width = 0
                setGravity(android.view.Gravity.FILL_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL)
            }
            controlsContainer.addView(rightHandView, controlParams)

            currentRow++
        }
    }
}