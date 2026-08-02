package com.example.mobstr

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import androidx.annotation.RequiresApi

sealed class CameraParam<T>(open val key: CaptureRequest.Key<T>, open val label: String) {
    data class RangeParam<T : Comparable<T>>(
        override val key: CaptureRequest.Key<T>,
        val characteristicKey: CameraCharacteristics.Key<Range<T>>,
        override val label: String,
        val unitScale: Long = 1,
        val unit: String = "",
    ) : CameraParam<T>(key, label)

    data class EnumParam<T, A>(
        override val key: CaptureRequest.Key<T>,
        val characteristicKey: CameraCharacteristics.Key<A>,
        override val label: String,
        val labels: Map<Any, String> = emptyMap(),
    ) : CameraParam<T>(key, label)

    data class BoolParam(
        override val key: CaptureRequest.Key<Boolean>,
        override val label: String,
    ) : CameraParam<Boolean>(key, label)

    data class FloatUpperParam(
        override val key: CaptureRequest.Key<Float>,
        val upperKey: CameraCharacteristics.Key<Float>,
        override val label: String,
        val unit: String = "",
    ) : CameraParam<Float>(key, label)

    data class FixedFloatParam(
        override val key: CaptureRequest.Key<Float>,
        override val label: String,
        val minimum: Float,
        val maximum: Float,
    ) : CameraParam<Float>(key, label)

    data class TorchParam(
        override val key: CaptureRequest.Key<Int> = CaptureRequest.FLASH_MODE,
        override val label: String = "Torch",
    ) : CameraParam<Int>(key, label)

    enum class Special { FPS, FRAME_DURATION, AE_REGION, AWB_REGION, AF_REGION,
        COLOR_GAINS, COLOR_MATRIX, TONEMAP_CURVE }

    data class SpecialParam<T>(
        override val key: CaptureRequest.Key<T>,
        override val label: String,
        val kind: Special,
    ) : CameraParam<T>(key, label)
}

private val aeLabels = mapOf<Any, String>(0 to "Manual", 1 to "Auto")
private val awbLabels = mapOf<Any, String>(0 to "Off", 1 to "Auto")
private val afLabels = mapOf<Any, String>(0 to "Off", 1 to "Auto", 2 to "Macro", 3 to "Continuous video", 4 to "Continuous picture", 5 to "EDOF")
private val qualityLabels = mapOf<Any, String>(0 to "Off", 1 to "Fast", 2 to "High quality", 3 to "Minimal", 4 to "Zero shutter lag")
val cameraSettingsRegistry = buildList {
addAll(listOf(
    CameraParam.EnumParam(CaptureRequest.CONTROL_AE_MODE, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, "Exposure mode", aeLabels),
    CameraParam.RangeParam(CaptureRequest.SENSOR_EXPOSURE_TIME, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, "Exposure", 1_000L, "µs"),
    CameraParam.RangeParam(CaptureRequest.SENSOR_SENSITIVITY, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, "ISO"),
    CameraParam.RangeParam(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, "Exposure compensation", unit = "EV"),
    CameraParam.SpecialParam(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, "Frame-rate range", CameraParam.Special.FPS),
    CameraParam.SpecialParam(CaptureRequest.SENSOR_FRAME_DURATION, "Frame duration", CameraParam.Special.FRAME_DURATION),
    CameraParam.BoolParam(CaptureRequest.CONTROL_AE_LOCK, "AE lock"),
    CameraParam.SpecialParam(CaptureRequest.CONTROL_AE_REGIONS, "AE metering region", CameraParam.Special.AE_REGION),

    CameraParam.EnumParam(CaptureRequest.CONTROL_AWB_MODE, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, "White balance", awbLabels),
    CameraParam.BoolParam(CaptureRequest.CONTROL_AWB_LOCK, "AWB lock"),
    CameraParam.SpecialParam(CaptureRequest.CONTROL_AWB_REGIONS, "AWB metering region", CameraParam.Special.AWB_REGION),

    CameraParam.EnumParam(CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, "Focus mode", afLabels),
    CameraParam.FloatUpperParam(CaptureRequest.LENS_FOCUS_DISTANCE, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, "Focus distance", "D"),
    CameraParam.SpecialParam(CaptureRequest.CONTROL_AF_REGIONS, "AF metering region", CameraParam.Special.AF_REGION),

    CameraParam.EnumParam(CaptureRequest.LENS_APERTURE, CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES, "Aperture"),
    CameraParam.EnumParam(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, "Distortion correction", qualityLabels),

    CameraParam.SpecialParam(CaptureRequest.COLOR_CORRECTION_GAINS, "Manual color gains", CameraParam.Special.COLOR_GAINS),
    CameraParam.SpecialParam(CaptureRequest.COLOR_CORRECTION_TRANSFORM, "Color transform", CameraParam.Special.COLOR_MATRIX),
    CameraParam.EnumParam(CaptureRequest.TONEMAP_MODE, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, "Tone-map mode",
        mapOf(0 to "Contrast curve", 1 to "Fast", 2 to "High quality", 3 to "Gamma", 4 to "Preset curve")),
    CameraParam.FixedFloatParam(CaptureRequest.TONEMAP_GAMMA, "Tone-map gamma", 1f, 5f),
    CameraParam.SpecialParam(CaptureRequest.TONEMAP_CURVE, "Tone curve", CameraParam.Special.TONEMAP_CURVE),
    CameraParam.TorchParam(),
))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(zoomRatioParam())
}

@RequiresApi(Build.VERSION_CODES.R)
private fun zoomRatioParam() = CameraParam.RangeParam(
    CaptureRequest.CONTROL_ZOOM_RATIO,
    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE,
    "Zoom ratio",
    unit = "×",
)
