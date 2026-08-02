package com.example.mobstr

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/** Select the logical rear camera that exposes the widest field of view. */
fun preferredBackCameraId(manager: CameraManager): String {
    val rearIds = manager.cameraIdList.filter { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }
    return rearIds.maxByOrNull { id ->
        val characteristics = manager.getCameraCharacteristics(id)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val logicalBonus = if (capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            )) 1_000_000.0 else 0.0
        val zoomScore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                (1.0 / it.lower.toDouble()) * 1_000.0 + it.upper.toDouble()
            } ?: 0.0
        } else 0.0
        logicalBonus + zoomScore
    } ?: manager.cameraIdList.first()
}
