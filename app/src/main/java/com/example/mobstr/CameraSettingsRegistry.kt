package com.example.mobstr

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range

enum class UiElement { CHECKBOX, SLIDER, DROPDOWN }

sealed class CameraParam<T> {
    abstract val key: CaptureRequest.Key<T>
    abstract val characteristicKey: CameraCharacteristics.Key<*>?
    abstract val label: String

    val uiElement: UiElement
        get() = when (this) {
            is RangeParam -> UiElement.SLIDER
            is EnumParam<*, *> -> UiElement.DROPDOWN
            is BoolParam -> UiElement.CHECKBOX
        }

    data class RangeParam<T : Comparable<T>>(
        override val key: CaptureRequest.Key<T>,
        override val characteristicKey: CameraCharacteristics.Key<Range<T>>,
        override val label: String
    ) : CameraParam<T>()

    data class EnumParam<T, A>(
        override val key: CaptureRequest.Key<T>,
        override val characteristicKey: CameraCharacteristics.Key<A>,
        override val label: String
    ) : CameraParam<T>()

    data class BoolParam<T>(
        override val key: CaptureRequest.Key<T>,
        override val characteristicKey: CameraCharacteristics.Key<*>?,
        override val label: String
    ) : CameraParam<T>()
}

val cameraSettingsRegistry = listOf(
    CameraParam.EnumParam(
        CaptureRequest.CONTROL_AE_MODE,
        CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
        "Exposure Mode"
    ),

    CameraParam.RangeParam(
        CaptureRequest.SENSOR_EXPOSURE_TIME,
        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
        "Exposure (ns)"
    ),

    CameraParam.EnumParam(
        CaptureRequest.LENS_APERTURE,
        CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES,
        "Aperture (f/N)"
    ),

    CameraParam.RangeParam(
        CaptureRequest.SENSOR_SENSITIVITY,
        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
        "ISO"
    ),

    CameraParam.EnumParam(
        CaptureRequest.CONTROL_AWB_MODE,
        CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        "Auto-White Balance Mode"
    ),
    CameraParam.EnumParam(
        CaptureRequest.CONTROL_AF_MODE,
        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        "Auto-Focus"
    ),
    CameraParam.EnumParam(
        CaptureRequest.NOISE_REDUCTION_MODE,
        CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
        "Noise Reduction"
    ),

    CameraParam.BoolParam(
        CaptureRequest.FLASH_MODE,
        CameraCharacteristics.FLASH_INFO_AVAILABLE,
        "Flash"
    )
)