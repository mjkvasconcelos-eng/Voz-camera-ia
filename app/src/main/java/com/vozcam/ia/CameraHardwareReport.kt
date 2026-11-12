package com.vozcam.ia

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val ReportGold = Color(0xFFD6A84F)
private val ReportMuted = Color(0xFF9A9DA3)

data class CameraLensInfo(
    val id: String,
    val facing: String,
    val hardwareLevel: String,
    val focalLengths: String,
    val sensorSize: String,
    val maxZoom: String,
    val isoRange: String,
    val exposureRange: String,
    val minFocus: String,
    val flash: String,
    val opticalStabilization: String,
    val physicalIds: String
)

fun readCameraHardware(context: Context): List<CameraLensInfo> {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return manager.cameraIdList.mapNotNull { id ->
        try {
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "TRASEIRA"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONTAL"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNA"
                else -> "NÃO IDENTIFICADA"
            }
            val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL 3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "DESCONHECIDO"
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString(", ") { String.format(Locale.US, "%.1f mm", it) } ?: "não informado"
            val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val sensor = if (pixels != null) {
                String.format(Locale.US, "%.1f MP (%d × %d)", pixels.width * pixels.height / 1_000_000.0, pixels.width, pixels.height)
            } else "não informado"
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.let { "${it.lower}–${it.upper}" } ?: "não informado"
            val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.let { String.format(Locale.US, "%.3f–%.1f ms", it.lower / 1_000_000.0, it.upper / 1_000_000.0) }
                ?: "não informado"
            val focus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.let { if (it > 0f) String.format(Locale.US, "%.2f dioptrias", it) else "somente automático" }
                ?: "não informado"
            val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val ois = if (oisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) "disponível" else "não declarado"
            val physical = if (Build.VERSION.SDK_INT >= 28) {
                c.physicalCameraIds.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "nenhuma exposta"
            } else "API Android abaixo de 28"
            CameraLensInfo(
                id = id,
                facing = facing,
                hardwareLevel = level,
                focalLengths = focal,
                sensorSize = sensor,
                maxZoom = String.format(Locale.US, "%.1f×", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f),
                isoRange = iso,
                exposureRange = exposure,
                minFocus = focus,
                flash = if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "sim" else "não",
                opticalStabilization = ois,
                physicalIds = physical
            )
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun CameraHardwareReportDialog(
    cameras: List<CameraLensInfo>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17191D),
        title = {
            Column {
                Text("HARDWARE DE CÂMERA", color = Color.White, fontWeight = FontWeight.Bold)
                Text("INVENTÁRIO REPORTADO PELO ANDROID", color = ReportGold, fontSize = 9.sp)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (cameras.isEmpty()) {
                    Text("O Android não retornou câmeras disponíveis.", color = Color.White)
                }
                cameras.forEachIndexed { index, lens ->
                    if (index > 0) HorizontalDivider(color = ReportMuted.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 10.dp))
                    Text("CÂMERA ${lens.id} • ${lens.facing}", color = ReportGold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    ReportLine("Nível Camera2", lens.hardwareLevel)
                    ReportLine("Distância focal", lens.focalLengths)
                    ReportLine("Sensor", lens.sensorSize)
                    ReportLine("Zoom digital máximo", lens.maxZoom)
                    ReportLine("Faixa ISO", lens.isoRange)
                    ReportLine("Exposição", lens.exposureRange)
                    ReportLine("Foco manual", lens.minFocus)
                    ReportLine("Flash", lens.flash)
                    ReportLine("Estabilização óptica", lens.opticalStabilization)
                    ReportLine("Câmeras físicas vinculadas", lens.physicalIds)
                }
                Text(
                    "IMPORTANTE: este relatório mostra o que o Android expõe. Ele não confirma que cada lente possa ser selecionada diretamente pelo CameraX nem substitui o teste no aparelho.",
                    color = ReportMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("FECHAR", color = ReportGold)
            }
        }
    )
}

@Composable
private fun ReportLine(label: String, value: String) {
    Text("$label: $value", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}
