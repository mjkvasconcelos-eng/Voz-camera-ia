package com.vozcam.ia

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val Gold = Color(0xFFD6A84F)
private val Dark = Color(0xFF08090B)
private val Panel = Color(0xFF17191D)
private val Muted = Color(0xFF9A9DA3)

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            setContent { CameraScreen(hasPermission = granted) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) setContent { CameraScreen(hasPermission = true) }
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }
}

@Composable
fun CameraScreen(hasPermission: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mode by remember { mutableStateOf("AUTO") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("CÂMERA PRONTA") }
    var backCamera by remember { mutableStateOf(true) }
    var torchEnabled by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var evMin by remember { mutableIntStateOf(-3) }
    var evMax by remember { mutableIntStateOf(3) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var gridEnabled by remember { mutableStateOf(false) }
    var camera2Summary by remember { mutableStateOf("ANALISANDO CAMERA2…") }
    var isoRange by remember { mutableStateOf<IntRange?>(null) }
    var shutterRangeNs by remember { mutableStateOf<LongRange?>(null) }
    var minimumFocusDistance by remember { mutableFloatStateOf(0f) }
    var isoValue by remember { mutableFloatStateOf(100f) }
    var shutterMs by remember { mutableFloatStateOf(10f) }
    var manualFocusDistance by remember { mutableFloatStateOf(0f) }
    var manualExposureEnabled by remember { mutableStateOf(false) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().background(Dark), contentAlignment = Alignment.Center) {
            Text("Permissão de câmera necessária.", color = Color.White)
        }
        return
    }

    val view = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(view, backCamera) {
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                provider = future.get()
                val activeProvider = provider ?: return@addListener
                val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                activeProvider.unbindAll()
                val bound = activeProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, capture
                )
                imageCapture = capture
                camera = bound
                try {
                    val info = Camera2CameraInfo.from(bound.cameraInfo)
                    val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val exposure = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val focus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    isoRange = iso?.let { it.lower..it.upper }
                    shutterRangeNs = exposure?.let { it.lower..it.upper }
                    minimumFocusDistance = focus
                    isoValue = (iso?.lower?.coerceAtLeast(100) ?: 100).toFloat()
                    shutterMs = ((exposure?.lower ?: 10_000_000L) / 1_000_000f).coerceAtLeast(0.01f)
                    manualFocusDistance = 0f
                    manualExposureEnabled = false
                    camera2Summary = "ISO " + (if (iso != null) iso.lower.toString() + "–" + iso.upper else "indisponível") +
                        " • obturador " + (if (exposure != null) "disponível" else "indisponível") +
                        " • foco manual " + (if (focus > 0f) "disponível" else "indisponível")
                } catch (_: Exception) {
                    isoRange = null
                    shutterRangeNs = null
                    minimumFocusDistance = 0f
                    camera2Summary = "RECURSOS CAMERA2 NÃO EXPOSTOS PELO SISTEMA"
                }
                val state = bound.cameraInfo.zoomState.value
                if (state != null) {
                    minZoom = state.minZoomRatio
                    maxZoom = state.maxZoomRatio
                    zoom = 1f.coerceIn(minZoom, maxZoom)
                }
                val range = bound.cameraInfo.exposureState.exposureCompensationRange
                evMin = range.lower
                evMax = range.upper
                exposureIndex = 0.coerceIn(evMin, evMax)
                status = if (backCamera) "CÂMERA TRASEIRA ATIVA" else "CÂMERA FRONTAL ATIVA"
                var scalingGesture = false
                val scaleDetector = ScaleGestureDetector(context,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                            scalingGesture = true
                            return true
                        }
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val current = bound.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            val next = (current * detector.scaleFactor)
                                .coerceIn(minZoom, maxZoom)
                            bound.cameraControl.setZoomRatio(next)
                            zoom = next
                            return true
                        }
                    })
                view.setOnTouchListener { _, event ->
                    scaleDetector.onTouchEvent(event)
                    if (event.action == MotionEvent.ACTION_UP) {
                        if (!scalingGesture) {
                            val point: MeteringPoint = view.meteringPointFactory
                                .createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                            bound.cameraControl.startFocusAndMetering(action)
                            status = "FOCO AJUSTADO"
                        }
                        scalingGesture = false
                    }
                    true
                }
            } catch (e: Exception) {
                status = "FALHA AO INICIAR: ${e.message ?: "câmera indisponível"}"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            view.setOnTouchListener(null)
            try { provider?.unbindAll() } catch (_: Exception) { }
            camera = null
            imageCapture = null
        }
    }

    val activeCamera = camera
    val exposureStep = activeCamera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0.0f
    val exposureEv = exposureIndex * exposureStep

    Column(Modifier.fillMaxSize().background(Dark)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("VOZ CÂMERA IA", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("GALAXY S24 ULTRA • PRO CAMERA", color = Gold, fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
            Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
                Text(if (mode == "AUTO") "● AUTO IA" else "● PRO", color = Gold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 10.sp)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxSize()
            )
            if (gridEnabled) {
                Canvas(Modifier.fillMaxSize()) {
                    val lineColor = Color.White.copy(alpha = 0.48f)
                    val stroke = 1.dp.toPx()
                    for (i in 1..2) {
                        val x = size.width * i / 3f
                        val y = size.height * i / 3f
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, 0f),
                            androidx.compose.ui.geometry.Offset(x, size.height), stroke)
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), stroke)
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallControl("⟲", "VIRAR") { backCamera = !backCamera; torchEnabled = false }
                SmallControl(if (gridEnabled) "▦" else "▤", "GRADE") {
                    gridEnabled = !gridEnabled
                }
                SmallControl(if (torchEnabled) "⚡" else "ϟ", if (torchEnabled) "FLASH ON" else "FLASH") {
                    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                    if (hasFlash) {
                        torchEnabled = !torchEnabled
                        camera?.cameraControl?.enableTorch(torchEnabled)
                    } else status = "FLASH NÃO DISPONÍVEL NESTA CÂMERA"
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                color = Color(0xCC090A0C), shape = RoundedCornerShape(8.dp)
            ) {
                Text("TOQUE NA IMAGEM PARA FOCAR", color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 9.sp)
            }
        }

        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                listOf("AUTO", "PRO").forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { mode = item },
                        label = { Text(if (item == "AUTO") "IA AUTOMÁTICA" else "PROFISSIONAL") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold,
                            selectedLabelColor = Color.Black,
                            labelColor = Color.White
                        ),
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                }
            }

            Text("ZOOM", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.6f, 1f, 3f, 5f).forEach { factor ->
                    val available = factor >= minZoom && factor <= maxZoom
                    val label = if (factor == 0.6f) "0.6×" else "${factor.toInt()}×"
                    TextButton(
                        enabled = available,
                        onClick = {
                            zoom = factor
                            camera?.cameraControl?.setZoomRatio(factor)
                        }
                    ) {
                        Text(label, color = if (kotlin.math.abs(zoom - factor) < 0.05f) Gold
                            else if (available) Color.White else Muted.copy(alpha = 0.35f),
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (maxZoom > minZoom) {
                Slider(
                    value = zoom.coerceIn(minZoom, maxZoom),
                    onValueChange = {
                        zoom = it
                        camera?.cameraControl?.setZoomRatio(it)
                    },
                    valueRange = minZoom..maxZoom,
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
                )
            }

            if (mode == "PRO") {
                Text(camera2Summary, color = Gold, fontSize = 9.sp)
                val active = camera
                val isoLimits = isoRange
                val shutterLimits = shutterRangeNs
                val camera2Control = active?.cameraControl?.let { Camera2CameraControl.from(it) }
                if (isoLimits != null && shutterLimits != null) {
                    Text("ISO: ${isoValue.toInt()}", color = Muted, fontSize = 10.sp)
                    Slider(
                        value = isoValue.coerceIn(isoLimits.first.toFloat(), isoLimits.last.toFloat()),
                        onValueChange = { isoValue = it },
                        valueRange = isoLimits.first.toFloat()..isoLimits.last.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
                    )
                    val minMs = (shutterLimits.first / 1_000_000f).coerceAtLeast(0.001f)
                    val maxMs = (shutterLimits.last / 1_000_000f).coerceAtLeast(minMs + 0.001f)
                    Text("OBTURADOR: ${String.format("%.2f", shutterMs)} ms", color = Muted, fontSize = 10.sp)
                    Slider(
                        value = shutterMs.coerceIn(minMs, maxMs),
                        onValueChange = { shutterMs = it },
                        valueRange = minMs..maxMs,
                        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    camera2Control?.setCaptureRequestOptions(
                                        CaptureRequestOptions.Builder()
                                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            .build()
                                    )
                                    manualExposureEnabled = false
                                    status = "EXPOSIÇÃO AUTOMÁTICA ATIVADA"
                                } catch (_: Exception) { status = "NÃO FOI POSSÍVEL ATIVAR AUTO" }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("AUTO", color = Color.White) }
                        Button(
                            onClick = {
                                try {
                                    val isoNow = isoValue.toInt().coerceIn(isoLimits.first, isoLimits.last)
                                    val timeNow = (shutterMs * 1_000_000f).toLong().coerceIn(shutterLimits.first, shutterLimits.last)
                                    camera2Control?.setCaptureRequestOptions(
                                        CaptureRequestOptions.Builder()
                                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoNow)
                                            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, timeNow)
                                            .build()
                                    )
                                    manualExposureEnabled = true
                                    status = "ISO E OBTURADOR APLICADOS"
                                } catch (_: Exception) { status = "CONTROLE MANUAL NÃO SUPORTADO NESTA LENTE" }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold)
                        ) { Text("APLICAR", color = Color.Black) }
                    }
                } else {
                    Text("ISO/OBTURADOR MANUAIS NÃO DISPONÍVEIS NESTA CÂMERA", color = Muted, fontSize = 9.sp)
                }
                if (minimumFocusDistance > 0f) {
                    Text(
                        if (manualFocusDistance <= 0f) "FOCO: AUTO" else "FOCO MANUAL: ${String.format("%.2f", manualFocusDistance)} D",
                        color = Muted, fontSize = 10.sp
                    )
                    Slider(
                        value = manualFocusDistance.coerceIn(0f, minimumFocusDistance),
                        onValueChange = { manualFocusDistance = it },
                        onValueChangeFinished = {
                            try {
                                val afMode = if (manualFocusDistance <= 0f)
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
                                camera2Control?.setCaptureRequestOptions(
                                    CaptureRequestOptions.Builder()
                                        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
                                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                                        .build()
                                )
                                status = if (manualFocusDistance <= 0f) "FOCO AUTOMÁTICO" else "FOCO MANUAL APLICADO"
                            } catch (_: Exception) { status = "FOCO MANUAL NÃO SUPORTADO NESTA LENTE" }
                        },
                        valueRange = 0f..minimumFocusDistance,
                        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
                    )
                } else {
                    Text("FOCO MANUAL NÃO DISPONÍVEL NESTA CÂMERA", color = Muted, fontSize = 9.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("EXPOSIÇÃO", color = Muted, fontSize = 10.sp)
                    Text("${if (exposureEv >= 0) "+" else ""}${String.format("%.1f", exposureEv)} EV",
                        color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = exposureIndex.toFloat().coerceIn(evMin.toFloat(), evMax.toFloat()),
                    onValueChange = {
                        exposureIndex = it.roundToInt()
                        camera?.cameraControl?.setExposureCompensationIndex(exposureIndex)
                    },
                    valueRange = evMin.toFloat()..evMax.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold)
                )
            } else {
                Text("AJUSTES AUTOMÁTICOS DA CÂMERA", color = Muted, fontSize = 10.sp)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    exposureIndex = 0
                    camera?.cameraControl?.setExposureCompensationIndex(0)
                    zoom = 1f.coerceIn(minZoom, maxZoom)
                    camera?.cameraControl?.setZoomRatio(zoom)
                    status = "CONFIGURAÇÕES REINICIADAS"
                }) { Text("REINICIAR", color = Muted, fontSize = 10.sp) }

                Button(
                    onClick = {
                        val capture = imageCapture
                        if (capture == null) {
                            status = "AGUARDE A CÂMERA INICIAR"
                        } else {
                            val name = "VozCamera_${System.currentTimeMillis()}.jpg"
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VozCameraIA")
                            }
                            val output = ImageCapture.OutputFileOptions.Builder(
                                context.contentResolver,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values
                            ).build()
                            status = "CAPTURANDO..."
                            capture.takePicture(
                                output,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                        status = "FOTO SALVA • GALERIA/VOZCAMERAIA"
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        status = "ERRO AO FOTOGRAFAR: ${exception.message}"
                                    }
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = CircleShape,
                    modifier = Modifier.size(68.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(Modifier.size(52.dp).background(Gold, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(43.dp).background(Color.White, CircleShape))
                    }
                }

                TextButton(onClick = {
                    backCamera = !backCamera
                    torchEnabled = false
                }) { Text("CÂMERA ${if (backCamera) "FRONTAL" else "TRASEIRA"}", color = Muted, fontSize = 10.sp) }
            }
            Text(status, color = Gold, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SmallControl(symbol: String, label: String, onClick: () -> Unit) {
    Surface(color = Color(0xCC090A0C), shape = RoundedCornerShape(10.dp)) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(symbol, color = Gold, fontSize = 18.sp)
                Text(label, color = Color.White, fontSize = 8.sp)
            }
        }
    }
}
