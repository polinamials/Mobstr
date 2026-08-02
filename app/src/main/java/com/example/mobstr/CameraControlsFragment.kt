package com.example.mobstr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Bundle
import android.util.Range
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class CameraControlsFragment : Fragment() {
    private lateinit var controlsContainer: GridLayout
    private lateinit var characteristics: CameraCharacteristics
    private lateinit var store: CameraControlStore
    private val controls = mutableMapOf<CaptureRequest.Key<*>, MutableList<View>>()
    private val labels = mutableMapOf<CaptureRequest.Key<*>, MutableList<TextView>>()
    private lateinit var availableKeys: Set<CaptureRequest.Key<*>>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_camera_controls, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        controlsContainer = view.findViewById(R.id.controlsContainer)
        store = CameraControlStore(requireActivity().getSharedPreferences("MobstrPrefs", 0))
        val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = preferredBackCameraId(manager)
        characteristics = manager.getCameraCharacteristics(cameraId)
        availableKeys = characteristics.availableCaptureRequestKeys.toSet()
        generateUi()
    }

    override fun onResume() {
        super.onResume()
        if (::controlsContainer.isInitialized && ::characteristics.isInitialized) generateUi()
    }

    fun refreshFromCamera() {
        if (isAdded && view != null && ::controlsContainer.isInitialized) generateUi()
    }

    private fun currentValue(key: CaptureRequest.Key<*>): Any? =
        (activity as? MainActivity)?.currentCameraValue(key)

    @Suppress("UNCHECKED_CAST")
    private fun generateUi() {
        controlsContainer.removeAllViews()
        controls.clear()
        labels.clear()
        var row = 0
        controlsContainer.addView(TextView(requireContext()).apply { text = "Camera controls" }, gridParams(row, 0, false))
        controlsContainer.addView(Button(requireContext()).apply {
            text = "Reset all"
            setOnClickListener {
                (activity as? MainActivity)?.resetCameraParameters()
                generateUi()
            }
        }, gridParams(row++, 1, true))
        cameraSettingsRegistry.forEach { param ->
            if (param.key !in availableKeys) return@forEach
            val label = TextView(requireContext()).apply { textSize = 14f }
            val control = when (param) {
                is CameraParam.RangeParam<*> -> makeRange(param, label)
                is CameraParam.EnumParam<*, *> -> makeEnum(param, label)
                is CameraParam.BoolParam -> makeBool(param, label)
                is CameraParam.FloatUpperParam -> makeFloatUpper(param, label)
                is CameraParam.FixedFloatParam -> makeFixedFloat(param, label)
                is CameraParam.SpecialParam<*> -> makeSpecial(param, label)
                is CameraParam.TorchParam -> makeTorch(param, label)
            } ?: return@forEach
            controlsContainer.addView(label, gridParams(row, 0, false))
            controlsContainer.addView(control, gridParams(row, 1, true))
            controls.getOrPut(param.key) { mutableListOf() }.add(control)
            labels.getOrPut(param.key) { mutableListOf() }.add(label)
            row++
        }
        updateDependencies()
    }

    @Suppress("UNCHECKED_CAST")
    private fun makeRange(param: CameraParam.RangeParam<*>, label: TextView): View? {
        val range = characteristics.get(param.characteristicKey as CameraCharacteristics.Key<android.util.Range<Comparable<Any>>>) ?: return null
        val lowerRaw = range.lower as Number
        val upperRaw = range.upper as Number
        val scale = param.unitScale.toDouble()
        val lower = lowerRaw.toDouble() / scale
        val upper = upperRaw.toDouble() / scale
        val logarithmic = param.key == CaptureRequest.SENSOR_EXPOSURE_TIME && lower > 0.0
        val steps = if (logarithmic || lowerRaw is Float) 1000
            else ((upper - lower).coerceAtLeast(1.0)).coerceAtMost(10_000.0).roundToInt()
        val reported = currentValue(param.key) as? Number
        val hasConfiguredValue = reported != null || store.contains(param.key)
        val savedRaw = reported?.toDouble() ?: when {
            param.key == CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION -> store.getInt(param.key, 0).toDouble()
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && param.key == CaptureRequest.CONTROL_ZOOM_RATIO ->
                store.getFloat(param.key, 1f).toDouble()
            lowerRaw is Long -> store.getLong(param.key, lowerRaw.toLong()).toDouble()
            lowerRaw is Float -> store.getFloat(param.key, lowerRaw.toFloat()).toDouble()
            else -> store.getInt(param.key, lowerRaw.toInt()).toDouble()
        }
        val saved = (savedRaw / scale).coerceIn(lower, upper)
        val initial = if (logarithmic) {
            (((ln(saved) - ln(lower)) / (ln(upper) - ln(lower))) * steps).roundToInt().coerceIn(0, steps)
        } else {
            (((saved - lower) / (upper - lower).coerceAtLeast(1.0)) * steps).roundToInt().coerceIn(0, steps)
        }
        fun value(progress: Int): Double = if (logarithmic) {
            exp(ln(lower) + (ln(upper) - ln(lower)) * progress / steps)
        } else {
            lower + (upper - lower) * progress / steps
        }
        label.text = param.label
        val valueLabel = TextView(requireContext()).apply {
            textSize = 13f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        val compensationStep = if (param.key == CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) {
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
        } else 1f
        fun updateValue(progress: Int) {
            valueLabel.text = if (!hasConfiguredValue &&
                (param.key == CaptureRequest.SENSOR_EXPOSURE_TIME || param.key == CaptureRequest.SENSOR_SENSITIVITY)) {
                "Available when camera starts"
            } else if (param.key == CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) {
                "%+.2f EV".format(value(progress) * compensationStep)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                param.key == CaptureRequest.CONTROL_ZOOM_RATIO) {
                "%.2f×".format(value(progress))
            } else {
                "${value(progress).roundToInt()} ${param.unit}".trim()
            }
        }
        val seekBar = SeekBar(requireContext()).apply {
            max = steps
            progress = initial
            updateValue(initial)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateValue(progress)
                    if (!fromUser) return
                    val raw = value(progress) * scale
                    val applied: Any = when (lowerRaw) { is Long -> raw.toLong(); is Float -> raw.toFloat(); else -> raw.roundToInt() }
                    apply(param.key as CaptureRequest.Key<Any>, applied)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun makeEnum(param: CameraParam.EnumParam<*, *>, label: TextView): View? {
        val available = characteristics.get(param.characteristicKey) ?: return null
        val advertisedModes: List<Any> = when (available) {
            is IntArray -> available.toList()
            is FloatArray -> available.toList()
            is Array<*> -> available.filterNotNull()
            else -> return null
        }
        val modes = if (param.key == CaptureRequest.CONTROL_AE_MODE) {
            advertisedModes.filter { it == CaptureRequest.CONTROL_AE_MODE_OFF || it == CaptureRequest.CONTROL_AE_MODE_ON }
        } else if (param.key == CaptureRequest.CONTROL_AWB_MODE) {
            advertisedModes.filter { it == CaptureRequest.CONTROL_AWB_MODE_OFF || it == CaptureRequest.CONTROL_AWB_MODE_AUTO }
        } else advertisedModes
        if (modes.isEmpty()) return null
        label.text = param.label
        val defaultMode: Any = when (param.key) {
            CaptureRequest.CONTROL_AE_MODE -> CaptureRequest.CONTROL_AE_MODE_ON
            CaptureRequest.CONTROL_AWB_MODE -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            CaptureRequest.NOISE_REDUCTION_MODE -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> modes.first()
        }.let { if (modes.contains(it)) it else modes.first() }
        val saved = currentValue(param.key) ?: when (defaultMode) {
            is Float -> store.getFloat(param.key, defaultMode)
            else -> store.getInt(param.key, defaultMode as Int)
        }
        val selectedMode = saved.takeIf(modes::contains) ?: defaultMode.also { store.put(param.key, it) }
        return Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
                modes.map { param.labels[it] ?: if (it is Float && param.key == CaptureRequest.LENS_APERTURE) "f/$it" else it.toString() }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(modes.indexOf(selectedMode).coerceAtLeast(0), false)
            var initializing = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initializing) return
                    apply(param.key as CaptureRequest.Key<Any>, modes[position])
                    updateDependencies()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            post { initializing = false }
        }
    }

    private fun makeBool(param: CameraParam.BoolParam, label: TextView): View {
        label.text = param.label
        return CheckBox(requireContext()).apply {
            text = "Enabled"
            isChecked = (currentValue(param.key) as? Boolean) ?: store.getBoolean(param.key, false)
            setOnCheckedChangeListener { _, checked -> apply(param.key, checked) }
        }
    }

    private fun makeFloatUpper(param: CameraParam.FloatUpperParam, label: TextView): View? {
        val upper = characteristics.get(param.upperKey) ?: return null
        if (upper <= 0f) return null
        label.text = param.label
        val value = TextView(requireContext()).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        val bar = SeekBar(requireContext()).apply {
            max = 1000
            val current = (currentValue(param.key) as? Number)?.toFloat() ?: store.getFloat(param.key, 0f)
            progress = ((current / upper) * max).roundToInt().coerceIn(0, max)
            fun update(p: Int) { value.text = "%.3f %s".format(upper * p / max, param.unit) }
            update(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    update(progress); if (fromUser) apply(param.key, upper * progress / max)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; addView(value); addView(bar)
        }
    }

    private fun makeFixedFloat(param: CameraParam.FixedFloatParam, label: TextView): View {
        label.text = param.label
        val value = TextView(requireContext()).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        val bar = SeekBar(requireContext()).apply {
            max = 1000
            val span = param.maximum - param.minimum
            val current = (currentValue(param.key) as? Number)?.toFloat() ?: store.getFloat(param.key, param.minimum)
            progress = (((current - param.minimum) / span) * max)
                .roundToInt().coerceIn(0, max)
            fun update(p: Int) { value.text = "%.2f".format(param.minimum + span * p / max) }
            update(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    update(progress)
                    if (fromUser) apply(param.key, param.minimum + span * progress / max)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; addView(value); addView(bar)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun makeSpecial(param: CameraParam.SpecialParam<*>, label: TextView): View? {
        label.text = param.label
        return when (param.kind) {
            CameraParam.Special.FPS -> makeFps(param as CameraParam.SpecialParam<Range<Int>>)
            CameraParam.Special.FRAME_DURATION -> makeFrameDuration(param as CameraParam.SpecialParam<Long>)
            CameraParam.Special.AE_REGION, CameraParam.Special.AWB_REGION, CameraParam.Special.AF_REGION -> makeRegion(param)
            CameraParam.Special.COLOR_GAINS -> makeVectorEditor(param, 4, "1,1,1,1") { values ->
                RggbChannelVector(values[0], values[1], values[2], values[3])
            }
            CameraParam.Special.COLOR_MATRIX -> makeVectorEditor(param, 9, "1,0,0,0,1,0,0,0,1") { values ->
                ColorSpaceTransform(values.map { Rational((it * 10_000).roundToInt(), 10_000) }.toTypedArray())
            }
            CameraParam.Special.TONEMAP_CURVE -> makeToneCurve(param)
        }
    }

    private fun makeFps(param: CameraParam.SpecialParam<Range<Int>>): View? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: return null
        val actual = currentValue(param.key) as? Range<*>
        val saved = store.getText(param.key).split(',').mapNotNull(String::toIntOrNull)
        val savedTitle = actual?.let { "${it.lower}–${it.upper} fps" }
            ?: saved.takeIf { it.size == 2 }?.let { "${it[0]}–${it[1]} fps" }.orEmpty()
        return makeObjectSpinner(ranges, savedTitle, title = { "${it.lower}–${it.upper} fps" }, selected = { selected ->
            store.put(param.key, selected); (activity as? MainActivity)?.updateCameraParameter(param.key, selected)
        })
    }

    private fun makeFrameDuration(param: CameraParam.SpecialParam<Long>): View? {
        val maxDuration = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: return null
        val maxFps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 30
        val minDuration = 1_000_000_000L / maxFps
        val reported = (currentValue(param.key) as? Number)?.toLong()
        val hasConfiguredValue = reported != null || store.contains(param.key)
        val value = TextView(requireContext()).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(value)
            addView(SeekBar(requireContext()).apply {
                max = 1000
                val current = reported ?: store.getLong(param.key, minDuration)
                progress = (((current - minDuration).toDouble() / (maxDuration - minDuration).coerceAtLeast(1)) * max).roundToInt().coerceIn(0, max)
                fun duration(p: Int) = minDuration + ((maxDuration - minDuration) * p / max)
                fun update(p: Int) {
                    value.text = if (hasConfiguredValue) "%.2f ms".format(duration(p) / 1_000_000.0)
                    else "Available when camera starts"
                }
                update(progress)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { update(p); if (user) apply(param.key, duration(p)) }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = Unit
                })
            })
        }
    }

    private fun makeRegion(param: CameraParam.SpecialParam<*>): View? {
        val maxRegions = when (param.kind) {
            CameraParam.Special.AE_REGION -> characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
            CameraParam.Special.AWB_REGION -> characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB)
            else -> characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
        } ?: 0
        if (maxRegions == 0) return null
        val presets = listOf("Full frame", "Center 50%", "Center 25%")
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val actualRect = (currentValue(param.key) as? Array<*>)?.firstOrNull() as? MeteringRectangle
        val actualPreset = actualRect?.rect?.let { rect ->
            val fraction = rect.width().toFloat() / active.width()
            when {
                fraction < .38f -> "Center 25%"
                fraction < .75f -> "Center 50%"
                else -> "Full frame"
            }
        }
        return makeObjectSpinner(presets, actualPreset ?: store.getText(param.key), title = { it }, selected = { selected ->
            store.put(param.key, selected)
            (activity as? MainActivity)?.updateCameraParameter(
                param.key as CaptureRequest.Key<Array<MeteringRectangle>>,
                centeredRegion(selected),
            )
        })
    }

    private fun makeToneCurve(param: CameraParam.SpecialParam<*>): View {
        val presets = listOf("Linear", "High contrast", "Low contrast")
        val actual = currentValue(param.key) as? TonemapCurve
        val actualPreset = presets.firstOrNull { curvesEqual(actual, toneCurve(it)) }
        return makeObjectSpinner(presets, actualPreset ?: store.getText(param.key), title = { it }, selected = { selected ->
            store.put(param.key, selected)
            (activity as? MainActivity)?.updateCameraParameter(
                param.key as CaptureRequest.Key<TonemapCurve>,
                toneCurve(selected),
            )
        })
    }

    private fun toneCurve(preset: String): TonemapCurve {
        val points = when (preset) {
            "High contrast" -> floatArrayOf(0f, 0f, .25f, .12f, .5f, .5f, .75f, .88f, 1f, 1f)
            "Low contrast" -> floatArrayOf(0f, .1f, .25f, .3f, .5f, .5f, .75f, .7f, 1f, .9f)
            else -> floatArrayOf(0f, 0f, 1f, 1f)
        }
        return TonemapCurve(points, points, points)
    }

    private fun curvesEqual(first: TonemapCurve?, second: TonemapCurve): Boolean {
        if (first == null) return false
        val channel = TonemapCurve.CHANNEL_RED
        if (first.getPointCount(channel) != second.getPointCount(channel)) return false
        return (0 until first.getPointCount(channel)).all { index ->
            val a = first.getPoint(channel, index)
            val b = second.getPoint(channel, index)
            kotlin.math.abs(a.x - b.x) < .001f && kotlin.math.abs(a.y - b.y) < .001f
        }
    }

    private fun centeredRegion(preset: String): Array<MeteringRectangle> {
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val fraction = when (preset) { "Center 25%" -> .25f; "Center 50%" -> .5f; else -> 1f }
        val w = (active.width() * fraction).roundToInt(); val h = (active.height() * fraction).roundToInt()
        return arrayOf(MeteringRectangle(active.centerX() - w / 2, active.centerY() - h / 2, w, h, MeteringRectangle.METERING_WEIGHT_MAX))
    }

    private fun <T> makePreset(param: CameraParam.SpecialParam<*>, presets: List<String>, value: (String) -> T): View =
        makeObjectSpinner(presets, store.getText(param.key), title = { it }, selected = { selected ->
            store.put(param.key, selected)
            (activity as? MainActivity)?.updateCameraParameter(param.key as CaptureRequest.Key<T>, value(selected))
        })

    private fun <T> makeObjectSpinner(items: List<T>, saved: String, title: (T) -> String, selected: (T) -> Unit): Spinner =
        Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items.map(title)).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val index = items.indexOfFirst { title(it) == saved || it.toString() == saved }.coerceAtLeast(0)
            setSelection(index, false)
            var initializing = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (!initializing) selected(items[position]) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            post { initializing = false }
        }

    private fun <T> makeVectorEditor(param: CameraParam.SpecialParam<*>, count: Int, fallback: String, build: (List<Float>) -> T): View {
        val actualText = when (val actual = currentValue(param.key)) {
            is RggbChannelVector -> listOf(actual.red, actual.greenEven, actual.greenOdd, actual.blue).joinToString(",")
            is ColorSpaceTransform -> (0..2).flatMap { row -> (0..2).map { column -> actual.getElement(column, row).toFloat() } }.joinToString(",")
            else -> null
        }
        val input = EditText(requireContext()).apply { setText(actualText ?: store.getText(param.key, fallback)); isSingleLine = false }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; addView(input)
            addView(Button(requireContext()).apply {
                text = "Apply"
                setOnClickListener {
                    val values = input.text.toString().split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (values.size != count) { input.error = "Enter $count comma-separated values"; return@setOnClickListener }
                    store.put(param.key, input.text.toString())
                    (activity as? MainActivity)?.updateCameraParameter(param.key as CaptureRequest.Key<T>, build(values))
                }
            })
        }
    }

    private fun makeTorch(param: CameraParam.TorchParam, label: TextView): View? {
        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) return null
        label.text = param.label
        return CheckBox(requireContext()).apply {
            text = "Enabled"
            val current = (currentValue(param.key) as? Number)?.toInt() ?: store.getInt(param.key, CaptureRequest.FLASH_MODE_OFF)
            isChecked = current == CaptureRequest.FLASH_MODE_TORCH
            setOnCheckedChangeListener { _, checked ->
                apply(param.key, if (checked) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
        }
    }

    private fun <T> apply(key: CaptureRequest.Key<T>, value: T) {
        store.put(key, value as Any)
        (activity as? MainActivity)?.updateCameraParameter(key, value)
    }

    private fun updateDependencies() {
        val aeMode = (currentValue(CaptureRequest.CONTROL_AE_MODE) as? Number)?.toInt()
            ?: store.getInt(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        controls[CaptureRequest.CONTROL_AE_LOCK]?.filterIsInstance<CheckBox>()?.forEach {
            val saved = (currentValue(CaptureRequest.CONTROL_AE_LOCK) as? Boolean)
                ?: store.getBoolean(CaptureRequest.CONTROL_AE_LOCK, false)
            if (it.isChecked != saved) it.isChecked = saved
        }
        setControlEnabled(CaptureRequest.SENSOR_EXPOSURE_TIME, aeMode == CaptureRequest.CONTROL_AE_MODE_OFF)
        setControlEnabled(CaptureRequest.SENSOR_SENSITIVITY, aeMode == CaptureRequest.CONTROL_AE_MODE_OFF)
        setControlEnabled(CaptureRequest.SENSOR_FRAME_DURATION, aeMode == CaptureRequest.CONTROL_AE_MODE_OFF)
        setControlEnabled(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeMode != CaptureRequest.CONTROL_AE_MODE_OFF)
        setControlEnabled(CaptureRequest.CONTROL_AE_LOCK, aeMode != CaptureRequest.CONTROL_AE_MODE_OFF)

        val torchAllowed = aeMode == CaptureRequest.CONTROL_AE_MODE_OFF || aeMode == CaptureRequest.CONTROL_AE_MODE_ON
        val torch = controls[CaptureRequest.FLASH_MODE]?.firstOrNull()
        if (!torchAllowed && torch is CheckBox && torch.isChecked) torch.isChecked = false
        setControlEnabled(CaptureRequest.FLASH_MODE, torchAllowed)

        val awbMode = (currentValue(CaptureRequest.CONTROL_AWB_MODE) as? Number)?.toInt()
            ?: store.getInt(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        controls[CaptureRequest.CONTROL_AWB_LOCK]?.filterIsInstance<CheckBox>()?.forEach {
            val saved = (currentValue(CaptureRequest.CONTROL_AWB_LOCK) as? Boolean)
                ?: store.getBoolean(CaptureRequest.CONTROL_AWB_LOCK, false)
            if (it.isChecked != saved) it.isChecked = saved
        }
        setControlEnabled(CaptureRequest.COLOR_CORRECTION_GAINS, awbMode == CaptureRequest.CONTROL_AWB_MODE_OFF)
        setControlEnabled(CaptureRequest.COLOR_CORRECTION_TRANSFORM, awbMode == CaptureRequest.CONTROL_AWB_MODE_OFF)

        val afMode = (currentValue(CaptureRequest.CONTROL_AF_MODE) as? Number)?.toInt()
            ?: store.getInt(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        setControlEnabled(CaptureRequest.LENS_FOCUS_DISTANCE, afMode == CaptureRequest.CONTROL_AF_MODE_OFF)

        val toneMode = (currentValue(CaptureRequest.TONEMAP_MODE) as? Number)?.toInt()
            ?: store.getInt(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        setControlEnabled(CaptureRequest.TONEMAP_CURVE, toneMode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        setControlEnabled(CaptureRequest.TONEMAP_GAMMA, toneMode == CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
    }

    private fun setControlEnabled(key: CaptureRequest.Key<*>, enabled: Boolean) {
        controls[key]?.forEach { control ->
            control.isEnabled = enabled
            control.alpha = if (enabled) 1f else 0.38f
            if (control is ViewGroup) setChildrenEnabled(control, enabled)
        }
        labels[key]?.forEach { it.alpha = if (enabled) 1f else 0.38f }
    }

    private fun setChildrenEnabled(group: ViewGroup, enabled: Boolean) {
        repeat(group.childCount) { index ->
            val child = group.getChildAt(index)
            child.isEnabled = enabled
            if (child is ViewGroup) setChildrenEnabled(child, enabled)
        }
    }

    private fun gridParams(row: Int, column: Int, fill: Boolean) = GridLayout.LayoutParams().apply {
        rowSpec = GridLayout.spec(row)
        columnSpec = GridLayout.spec(column, if (fill) 1f else 0f)
        width = if (fill) 0 else GridLayout.LayoutParams.WRAP_CONTENT
        setGravity(if (fill) android.view.Gravity.FILL_HORIZONTAL else android.view.Gravity.CENTER_VERTICAL)
    }
}
