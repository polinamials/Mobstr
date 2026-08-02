package com.example.mobstr

import android.content.SharedPreferences
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.util.Range
import android.util.Rational

class CameraControlStore(private val preferences: SharedPreferences) {
    private fun name(key: CaptureRequest.Key<*>) = "camera.${key.name}"
    fun contains(key: CaptureRequest.Key<*>) = preferences.contains(name(key))
    fun getInt(key: CaptureRequest.Key<*>, fallback: Int) = preferences.getInt(name(key), fallback)
    fun getLong(key: CaptureRequest.Key<*>, fallback: Long) = preferences.getLong(name(key), fallback)
    fun getFloat(key: CaptureRequest.Key<*>, fallback: Float) = preferences.getFloat(name(key), fallback)
    fun getBoolean(key: CaptureRequest.Key<*>, fallback: Boolean) = preferences.getBoolean(name(key), fallback)
    fun getText(key: CaptureRequest.Key<*>, fallback: String = "") = preferences.getString(name(key), fallback) ?: fallback
    fun clear() {
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith("camera.") }.forEach(::remove)
        }.apply()
    }

    fun resetToDefaults(characteristics: CameraCharacteristics) {
        clear()
        put(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        put(CaptureRequest.CONTROL_AE_LOCK, false)
        put(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        put(CaptureRequest.CONTROL_AWB_LOCK, false)
        put(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { modes ->
            val default = when {
                modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> modes.first()
            }
            put(CaptureRequest.CONTROL_AF_MODE, default)
        }
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { ranges ->
            ranges.minWithOrNull(compareBy<Range<Int>>(
                { kotlin.math.abs(it.upper - 30) },
                { it.upper - it.lower },
            ))?.let { put(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        put(CaptureRequest.CONTROL_AE_REGIONS, "Full frame")
        put(CaptureRequest.CONTROL_AWB_REGIONS, "Full frame")
        put(CaptureRequest.CONTROL_AF_REGIONS, "Full frame")

        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let {
            put(CaptureRequest.LENS_APERTURE, it)
        }
        characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.let { modes ->
            val default = if (modes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST))
                CaptureRequest.DISTORTION_CORRECTION_MODE_FAST else modes.first()
            put(CaptureRequest.DISTORTION_CORRECTION_MODE, default)
        }
        characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.let { modes ->
            val default = if (modes.contains(CaptureRequest.TONEMAP_MODE_FAST))
                CaptureRequest.TONEMAP_MODE_FAST else modes.first()
            put(CaptureRequest.TONEMAP_MODE, default)
        }
        put(CaptureRequest.TONEMAP_CURVE, "Linear")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1f) == true) {
            put(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        }
    }

    fun put(key: CaptureRequest.Key<*>, value: Any) {
        preferences.edit().apply {
            when (value) {
                is Int -> putInt(name(key), value)
                is Long -> putLong(name(key), value)
                is Float -> putFloat(name(key), value)
                is Boolean -> putBoolean(name(key), value)
                is String -> putString(name(key), value)
                is Range<*> -> putString(name(key), "${value.lower},${value.upper}")
                is IntArray -> putString(name(key), value.joinToString(","))
                is RggbChannelVector -> putString(name(key), listOf(value.red, value.greenEven, value.greenOdd, value.blue).joinToString(","))
                is ColorSpaceTransform -> putString(name(key), (0..2).flatMap { row ->
                    (0..2).map { column -> value.getElement(column, row).toFloat() }
                }.joinToString(","))
            }
        }.apply()
    }

    @Suppress("UNCHECKED_CAST")
    fun applyTo(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        cameraSettingsRegistry.distinctBy { it.key }.forEach { param ->
            if (!contains(param.key)) return@forEach
            runCatching {
                when (param) {
                    is CameraParam.RangeParam<*> -> {
                        if (param.key == CaptureRequest.SENSOR_EXPOSURE_TIME) {
                            builder.set(param.key as CaptureRequest.Key<Long>, getLong(param.key, 0L))
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && param.key == CaptureRequest.CONTROL_ZOOM_RATIO) {
                            builder.set(param.key as CaptureRequest.Key<Float>, getFloat(param.key, 1f))
                        } else {
                            builder.set(param.key as CaptureRequest.Key<Int>, getInt(param.key, 0))
                        }
                    }
                    is CameraParam.EnumParam<*, *> -> when (param.key) {
                        CaptureRequest.LENS_APERTURE, CaptureRequest.LENS_FILTER_DENSITY ->
                            builder.set(param.key as CaptureRequest.Key<Float>, getFloat(param.key, 0f))
                        CaptureRequest.CONTROL_AE_MODE -> builder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            getInt(param.key, CaptureRequest.CONTROL_AE_MODE_ON).takeIf {
                                it == CaptureRequest.CONTROL_AE_MODE_OFF || it == CaptureRequest.CONTROL_AE_MODE_ON
                            } ?: CaptureRequest.CONTROL_AE_MODE_ON,
                        )
                        CaptureRequest.CONTROL_AWB_MODE -> builder.set(
                            CaptureRequest.CONTROL_AWB_MODE,
                            getInt(param.key, CaptureRequest.CONTROL_AWB_MODE_AUTO).takeIf {
                                it == CaptureRequest.CONTROL_AWB_MODE_OFF || it == CaptureRequest.CONTROL_AWB_MODE_AUTO
                            } ?: CaptureRequest.CONTROL_AWB_MODE_AUTO,
                        )
                        else -> builder.set(param.key as CaptureRequest.Key<Int>, getInt(param.key, 0))
                    }
                    is CameraParam.BoolParam -> builder.set(param.key, getBoolean(param.key, false))
                    is CameraParam.FloatUpperParam -> builder.set(param.key, getFloat(param.key, 0f))
                    is CameraParam.FixedFloatParam -> {
                        if (param.key != CaptureRequest.TONEMAP_GAMMA ||
                            getInt(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST) == CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) {
                            builder.set(param.key, getFloat(param.key, param.minimum))
                        }
                    }
                    is CameraParam.TorchParam -> builder.set(param.key, getInt(param.key, CaptureRequest.FLASH_MODE_OFF))
                    is CameraParam.SpecialParam<*> -> applySpecial(builder, characteristics, param)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySpecial(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics, param: CameraParam.SpecialParam<*>) {
        when (param.kind) {
            CameraParam.Special.FPS -> getText(param.key).split(',').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 }
                ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it[0], it[1])) }
            CameraParam.Special.FRAME_DURATION -> builder.set(CaptureRequest.SENSOR_FRAME_DURATION, getLong(param.key, 33_333_333L))
            CameraParam.Special.AE_REGION, CameraParam.Special.AWB_REGION, CameraParam.Special.AF_REGION -> {
                val region = region(getText(param.key, "Full frame"), characteristics) ?: return
                builder.set(param.key as CaptureRequest.Key<Array<MeteringRectangle>>, arrayOf(region))
            }
            CameraParam.Special.COLOR_GAINS -> floats(getText(param.key), 4)?.let {
                if (getInt(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) != CaptureRequest.CONTROL_AWB_MODE_OFF) return
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(it[0], it[1], it[2], it[3]))
            }
            CameraParam.Special.COLOR_MATRIX -> floats(getText(param.key), 9)?.let { values ->
                if (getInt(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) != CaptureRequest.CONTROL_AWB_MODE_OFF) return
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    ColorSpaceTransform(values.map { Rational((it * 10_000).toInt(), 10_000) }.toTypedArray()))
            }
            CameraParam.Special.TONEMAP_CURVE -> toneCurve(getText(param.key))?.let {
                if (getInt(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST) != CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) return
                builder.set(CaptureRequest.TONEMAP_CURVE, it)
            }
        }
    }

    private fun floats(text: String, count: Int) = text.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == count }

    private fun region(preset: String, characteristics: CameraCharacteristics): MeteringRectangle? {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val fraction = when (preset) { "Center 25%" -> .25f; "Center 50%" -> .5f; else -> 1f }
        val width = (active.width() * fraction).toInt()
        val height = (active.height() * fraction).toInt()
        return MeteringRectangle(Rect(active.centerX() - width / 2, active.centerY() - height / 2,
            active.centerX() + width / 2, active.centerY() + height / 2), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun toneCurve(preset: String): TonemapCurve? {
        val points = when (preset) {
            "High contrast" -> floatArrayOf(0f, 0f, .25f, .12f, .5f, .5f, .75f, .88f, 1f, 1f)
            "Low contrast" -> floatArrayOf(0f, .1f, .25f, .3f, .5f, .5f, .75f, .7f, 1f, .9f)
            "Linear" -> floatArrayOf(0f, 0f, 1f, 1f)
            else -> return null
        }
        return TonemapCurve(points, points, points)
    }
}
