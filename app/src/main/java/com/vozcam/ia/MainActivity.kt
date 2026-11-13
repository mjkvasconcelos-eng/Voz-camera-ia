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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.Executors
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val barsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        barsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        barsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
    var dslrMode by remember { mutableStateOf("P") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var whiteBalanceMode by remember { mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO) }
    var availableWhiteBalanceModes by remember { mutableStateOf(listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)) }
    var availableAfModes by remember { mutableStateOf(listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) }
    var selectedAfMode by remember { mutableIntStateOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) }
    var status by remember { mutableStateOf("CÂMERA PRONTA") }
    var backCamera by remember { mutableStateOf(true) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
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
    var detectedScene by remember { mutableStateOf("ANALISANDO CENA…") }
    var averageLuminance by remember { mutableIntStateOf(0) }
    var luminanceHistogram by remember { mutableStateOf(List(32) { 0f }) }
    var focusPeakingEnabled by remember { mutableStateOf(false) }
    var focusPeakingPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var showHardwareReport by remember { mutableStateOf(false) }
    var hardwareReport by remember { mutableStateOf<List<CameraLensInfo>>(emptyList()) }
    var showLensPicker by remember { mutableStateOf(false) }
    var showLensOverlay by remember { mutableStateOf(false) }
    var showProSettings by remember { mutableStateOf(false) }
    var showAiAssistant by remember { mutableStateOf(false) }
    var focusExposureLocked by remember { mutableStateOf(false) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var photoTimerSeconds by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }
    val cameraScope = rememberCoroutineScope()
    var updateAvailable by remember { mutableStateOf<AppUpdate?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadedUpdate by remember { mutableStateOf<File?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("Uma nova versão está disponível.") }

    BackHandler(enabled = showProSettings) { showProSettings = false }

    LaunchedEffect(Unit) {
        updateBusy = true
        try {
            updateAvailable = AppUpdater.checkLatest(context)
            showUpdateDialog = updateAvailable != null
        } catch (_: Exception) {
            // A falha de rede não impede a abertura da câmera.
        } finally {
            updateBusy = false
        }
    }

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

    DisposableEffect(view, backCamera, selectedCameraId) {
        previewView = view
        focusExposureLocked = false
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analysisBusy = AtomicBoolean(false)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        future.addListener({
            try {
                provider = future.get()
                val activeProvider = provider ?: return@addListener
                val selector = selectedCameraId?.let { requestedId ->
                    CameraSelector.Builder().addCameraFilter { cameraInfos ->
                        cameraInfos.filter { info ->
                            try { Camera2CameraInfo.from(info).cameraId == requestedId }
                            catch (_: Exception) { false }
                        }
                    }.build()
                } ?: if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null || !analysisBusy.compareAndSet(false, true)) {
                        proxy.close()
                    } else {
                        val yPlane = proxy.planes.firstOrNull()
                        var measuredHistogram: List<Float>? = null
                        var measuredFocusPoints: List<Offset>? = null
                        val measuredLuminance = if (yPlane != null) {
                            val buffer = yPlane.buffer
                            val rowStride = yPlane.rowStride
                            val pixelStride = yPlane.pixelStride
                            val edgePoints = ArrayList<Offset>(400)
                            var total = 0L
                            var samples = 0
                            val bins = IntArray(32)
                            for (y in 0 until proxy.height step 8) {
                                for (x in 0 until proxy.width step 8) {
                                    val index = y * rowStride + x * pixelStride
                                    if (index < buffer.limit()) {
                                        val value = buffer.get(index).toInt() and 0xFF
                                        total += value
                                        bins[(value / 8).coerceIn(0, 31)]++
                                        samples++
                                    }
                                }
                            }
                            if (samples > 0) {
                                val peak = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
                                measuredHistogram = bins.map { it / peak }
                                for (y in 8 until proxy.height - 8 step 12) {
                                    for (x in 8 until proxy.width - 8 step 12) {
                                        val left = y * rowStride + (x - 4) * pixelStride
                                        val right = y * rowStride + (x + 4) * pixelStride
                                        val top = (y - 4) * rowStride + x * pixelStride
                                        val bottom = (y + 4) * rowStride + x * pixelStride
                                        if (bottom < buffer.limit() && right < buffer.limit()) {
                                            val gx = kotlin.math.abs((buffer.get(right).toInt() and 0xFF) - (buffer.get(left).toInt() and 0xFF))
                                            val gy = kotlin.math.abs((buffer.get(bottom).toInt() and 0xFF) - (buffer.get(top).toInt() and 0xFF))
                                            if (gx + gy > 72 && edgePoints.size < 500) {
                                                edgePoints.add(Offset(x.toFloat() / proxy.width, y.toFloat() / proxy.height))
                                            }
                                        }
                                    }
                                }
                                measuredFocusPoints = edgePoints
                                (total / samples).toInt()
                            } else null
                        } else null
                        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                        labeler.process(input)
                            .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { labels ->
                                val names = labels.sortedByDescending { it.confidence }
                                    .take(5).map { it.text.lowercase() }
                                detectedScene = classifyScene(names)
                                if (measuredLuminance != null) averageLuminance = measuredLuminance
                                measuredHistogram?.let { luminanceHistogram = it }
                                measuredFocusPoints?.let { focusPeakingPoints = it }
                            }
                            .addOnFailureListener(ContextCompat.getMainExecutor(context)) {
                                detectedScene = "IA INDISPONÍVEL"
                            }
                            .addOnCompleteListener {
                                analysisBusy.set(false)
                                proxy.close()
                            }
                    }
                }
                activeProvider.unbindAll()
                val bound = activeProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, capture, analysis
                )
                imageCapture = capture
                camera = bound
                try {
                    val info = Camera2CameraInfo.from(bound.cameraInfo)
                    val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val exposure = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val focus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    availableWhiteBalanceModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList()
                        ?: listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    availableAfModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList()
                        ?: listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    selectedAfMode = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in availableAfModes)
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else availableAfModes.firstOrNull()
                        ?: CaptureRequest.CONTROL_AF_MODE_OFF
                    whiteBalanceMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
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
                            showProSettings = false
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
            analysisExecutor.shutdown()
            labeler.close()
            camera = null
            imageCapture = null
        }
    }

    LaunchedEffect(imageCapture, flashMode) {
        imageCapture?.flashMode = flashMode
    }

    val activeCamera = camera
    if (showHardwareReport) {
        CameraHardwareReportDialog(cameras = hardwareReport, onDismiss = { showHardwareReport = false })
    }
    if (showLensPicker) {
        CameraLensSelectionDialog(
            cameras = hardwareReport.filter { it.facing == if (backCamera) "TRASEIRA" else "FRONTAL" },
            selectedId = selectedCameraId,
            onSelect = { id ->
                selectedCameraId = id
                showLensPicker = false
                status = "TROCANDO LENTE…"
            },
            onDismiss = { showLensPicker = false }
        )
    }
    val exposureStep = activeCamera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0.0f
    val exposureEv = exposureIndex * exposureStep

    LaunchedEffect(zoom, selectedCameraId, backCamera) {
        showLensOverlay = true
        delay(2800)
        showLensOverlay = false
    }

    if (showUpdateDialog && updateAvailable != null) {
        val release = updateAvailable!!
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("ATUALIZAÇÃO DISPONÍVEL") },
            text = {
                Column {
                    Text("Voz Câmera IA • versão ${release.versionName}")
                    Text(updateMessage, modifier = Modifier.padding(top = 8.dp))
                    Text("O Android pedirá sua autorização para instalar.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updateBusy,
                    onClick = {
                        cameraScope.launch {
                            updateBusy = true
                            try {
                                val apk = downloadedUpdate ?: AppUpdater.download(context, release)
                                downloadedUpdate = apk
                                val opened = AppUpdater.installOrRequestPermission(context, apk)
                                updateMessage = if (opened) "Instalador do Android aberto."
                                    else "Permita a instalação para Voz Câmera IA e volte aqui. Depois toque em INSTALAR APK."
                            } catch (error: Exception) {
                                updateMessage = error.message ?: "Não foi possível atualizar."
                            } finally {
                                updateBusy = false
                            }
                        }
                    }
                ) { Text(if (updateBusy) "AGUARDE..." else if (downloadedUpdate == null) "BAIXAR APK" else "INSTALAR APK") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("DEPOIS") }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050608))) {
        Box(
            Modifier.fillMaxSize()
        ) {
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxSize()
            )
            // Assistente fotográfico conectado ao Worker Voz Câmera IA.
            if (showAiAssistant) {
                AiAssistantPanel(
                    context = context,
                    imageCapture = imageCapture,
                    cameraContext = buildString {
                        append("Dispositivo: Samsung Galaxy S24 Ultra. ")
                        append("Modo de câmera: $mode; seletor: $dslrMode; lente/câmera selecionada: ${selectedCameraId ?: "automática"}; ")
                        append("câmera: ${if (backCamera) "traseira" else "frontal"}; zoom: ${String.format("%.2f", zoom)}x; ")
                        append("cena detectada: $detectedScene; luminosidade média: $averageLuminance; ")
                        append("faixa ISO disponível: ${isoRange ?: "não informada"}; ISO selecionado no controle: ${isoValue.toInt()}; ")
                        append("obturador selecionado: ${String.format("%.2f", shutterMs)} ms; ")
                        append("foco manual: ${String.format("%.2f", manualFocusDistance)}; exposição: ${String.format("%.1f", exposureEv)} EV; ")
                        append("recursos Camera2: $camera2Summary. ")
                        append("Os valores selecionados não necessariamente representam os valores efetivamente aplicados; recomende ajustes sem alterar a câmera automaticamente.")
                    },
                    onClose = { showAiAssistant = false }
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                onClick = { showAiAssistant = !showAiAssistant },
                color = if (showAiAssistant) Color(0xFF29231A) else Color(0xF0D6A84F),
                shape = CircleShape,
                shadowElevation = 14.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Gold)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("✦", color = if (showAiAssistant) Gold else Color.Black, fontSize = 18.sp)
                    Text("GPT", color = if (showAiAssistant) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
            if (focusPeakingEnabled && mode == "PRO") {
                Canvas(Modifier.fillMaxSize()) {
                    focusPeakingPoints.forEach { point ->
                        drawCircle(
                            color = Color(0xFF45FF78),
                            radius = 2.2.dp.toPx(),
                            center = Offset(point.x * size.width, point.y * size.height)
                        )
                    }
                }
            }
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
            if (showLensOverlay) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) {
                Surface(
                    color = Color(0xDD101216),
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 12.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(0.6f, 1f, 3f, 5f).forEach { factor ->
                            val available = factor >= minZoom && factor <= maxZoom
                            val selected = kotlin.math.abs(zoom - factor) < 0.05f
                            Surface(
                                onClick = {
                                    if (available) {
                                        zoom = factor
                                        camera?.cameraControl?.setZoomRatio(factor)
                                    }
                                },
                                enabled = available,
                                shape = CircleShape,
                                color = if (selected) Gold else Color.Transparent
                            ) {
                                Text(
                                    if (factor == 0.6f) "0.6×" else "${factor.toInt()}×",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    color = if (selected) Color.Black else if (available) Color.White else Muted.copy(alpha = 0.4f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallControl("⟲", "VIRAR") { backCamera = !backCamera; selectedCameraId = null }
                SmallControl(if (gridEnabled) "▦" else "▤", "GRADE") {
                    gridEnabled = !gridEnabled
                }
                SmallControl(
                    when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> "A⚡"
                        ImageCapture.FLASH_MODE_ON -> "⚡"
                        else -> "ϟ"
                    },
                    when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"
                        ImageCapture.FLASH_MODE_ON -> "FLASH ON"
                        else -> "FLASH OFF"
                    }
                ) {
                    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                    if (!hasFlash) {
                        status = "FLASH NÃO DISPONÍVEL NESTA CÂMERA"
                    } else {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                        status = when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTOMÁTICO ATIVADO"
                            ImageCapture.FLASH_MODE_ON -> "FLASH SEMPRE LIGADO PARA FOTOS"
                            else -> "FOTOS SEM FLASH"
                        }
                    }
                }
                SmallControl("◉", "LENTES") {
                    hardwareReport = try { readCameraHardware(context) } catch (_: Exception) { emptyList() }
                    showLensPicker = true
                }
                SmallControl("ⓘ", "INFO") {
                    hardwareReport = try { readCameraHardware(context) } catch (_: Exception) { emptyList() }
                    showHardwareReport = true
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp),
                color = Color(0xDD090A0C), shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "IA • $detectedScene  |  LUZ ${averageLuminance}/255 • " +
                        when {
                            averageLuminance < 65 -> "BAIXA"
                            averageLuminance > 190 -> "ALTA"
                            else -> "MÉDIA"
                        },
                    color = Gold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 9.sp
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 62.dp, end = 10.dp),
                color = Color(0xD9090A0C), shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.45f))
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text("HISTOGRAMA", color = Gold, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    Canvas(Modifier.width(112.dp).height(42.dp)) {
                        val barWidth = size.width / luminanceHistogram.size
                        luminanceHistogram.forEachIndexed { index, level ->
                            val barHeight = (level * size.height).coerceAtLeast(1.dp.toPx())
                            drawRect(
                                color = Gold.copy(alpha = 0.9f),
                                topLeft = androidx.compose.ui.geometry.Offset(index * barWidth, size.height - barHeight),
                                size = androidx.compose.ui.geometry.Size((barWidth - 1.dp.toPx()).coerceAtLeast(1f), barHeight)
                            )
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                color = Color(0xD9090A0C), shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    when {
                        averageLuminance < 55 -> "EXPOSIÇÃO: AUMENTAR LUZ"
                        averageLuminance < 85 -> "CENA ESCURA"
                        averageLuminance > 205 -> "ATENÇÃO: ALTAS LUZES"
                        averageLuminance > 175 -> "CENA CLARA"
                        else -> "EXPOSIÇÃO: EQUILIBRADA"
                    },
                    color = Gold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 8.sp
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                color = Color(0xCC090A0C), shape = RoundedCornerShape(8.dp)
            ) {
                Text("TOQUE NA IMAGEM PARA FOCAR", color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 9.sp)
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = Color(0xFF202329), shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = 0.45f))) {
                    Text("V", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("VOZ CÂMERA IA", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
                    Text("S24 ULTRA  •  PROFESSIONAL", color = Gold, fontSize = 8.sp, letterSpacing = 1.35.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !updateBusy,
                    onClick = {
                        cameraScope.launch {
                            updateBusy = true
                            try {
                                updateAvailable = AppUpdater.checkLatest(context)
                                if (updateAvailable != null) {
                                    downloadedUpdate = null
                                    updateMessage = "Uma nova versão está disponível."
                                    showUpdateDialog = true
                                } else {
                                    status = "VOZ CÂMERA IA JÁ ESTÁ ATUALIZADO"
                                }
                            } catch (_: Exception) {
                                status = "NÃO FOI POSSÍVEL VERIFICAR ATUALIZAÇÕES"
                            } finally {
                                updateBusy = false
                            }
                        }
                    }
                ) { Text(if (updateBusy) "VERIFICANDO" else "ATUALIZAR", color = Gold, fontSize = 9.sp) }
                Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
                    Text(if (mode == "AUTO") "● AUTO IA" else "● PRO", color = Gold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 10.sp)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .shadow(22.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xB8101216), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SELETOR DE MODOS • ESTILO DSLR", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf("P", "S", "A", "M").forEach { dial ->
                    FilterChip(
                        selected = dslrMode == dial,
                        onClick = {
                            dslrMode = dial
                            mode = if (dial == "P") "AUTO" else "PRO"
                            showProSettings = dial != "P"
                            status = when (dial) {
                                "P" -> "PROGRAMA: EXPOSIÇÃO AUTOMÁTICA"
                                "S" -> "PERFIL S: AJUSTE O OBTURADOR NO PAINEL PRO"
                                "A" -> "PERFIL A: ABERTURA FÍSICA FIXA NESTE SMARTPHONE"
                                else -> "MANUAL: ISO, OBTURADOR, FOCO E EXPOSIÇÃO"
                            }
                        },
                        label = { Text(dial, fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold,
                            selectedLabelColor = Color.Black,
                            labelColor = Color.White
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                listOf("AUTO", "PRO").forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = {
                            mode = item
                            showProSettings = item == "PRO"
                        },
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

            if (mode == "PRO" && showProSettings) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("AJUSTES PROFISSIONAIS", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showProSettings = false }) { Text("FECHAR  ✕", color = Gold, fontSize = 10.sp) }
                }
                Text(camera2Summary, color = Gold, fontSize = 9.sp)
                OutlinedButton(
                    onClick = {
                        val active = camera
                        if (focusExposureLocked) {
                            active?.cameraControl?.cancelFocusAndMetering()
                            focusExposureLocked = false
                            status = "TRAVA AE/AF DESATIVADA"
                        } else {
                            val viewForMetering = previewView
                            if (active == null || viewForMetering == null || viewForMetering.width <= 0 || viewForMetering.height <= 0) {
                                status = "AGUARDE A CÂMERA FICAR PRONTA"
                            } else {
                                try {
                                    val center = viewForMetering.meteringPointFactory.createPoint(
                                        viewForMetering.width / 2f,
                                        viewForMetering.height / 2f
                                    )
                                    val action = FocusMeteringAction.Builder(center)
                                        .disableAutoCancel()
                                        .build()
                                    val future = active.cameraControl.startFocusAndMetering(action)
                                    future.addListener({
                                        try {
                                            future.get()
                                            focusExposureLocked = true
                                            status = "TRAVA AE/AF ATIVADA"
                                        } catch (_: Exception) {
                                            status = "ESTA LENTE NÃO PERMITIU A TRAVA AE/AF"
                                        }
                                    }, ContextCompat.getMainExecutor(context))
                                } catch (_: Exception) {
                                    status = "NÃO FOI POSSÍVEL TRAVAR FOCO/EXPOSIÇÃO"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (focusExposureLocked) Gold else Color.White
                    )
                ) {
                    Text(if (focusExposureLocked) "🔒 AE/AF BLOQUEADOS • TOQUE PARA LIBERAR" else "🔓 TRAVAR FOCO + EXPOSIÇÃO (AE/AF)")
                }
                OutlinedButton(
                    onClick = {
                        focusPeakingEnabled = !focusPeakingEnabled
                        status = if (focusPeakingEnabled) "FOCUS PEAKING ATIVADO • CONTORNO VERDE" else "FOCUS PEAKING DESATIVADO"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (focusPeakingEnabled) Color(0xFF45FF78) else Color.White
                    )
                ) {
                    Text(if (focusPeakingEnabled) "FOCUS PEAKING • LIGADO" else "ATIVAR FOCUS PEAKING")
                }
                val active = camera
                val isoLimits = isoRange
                val shutterLimits = shutterRangeNs
                val camera2Control = active?.cameraControl?.let { Camera2CameraControl.from(it) }
                val whiteBalanceOptions = listOf(
                    CaptureRequest.CONTROL_AWB_MODE_AUTO to "AUTO",
                    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "DIA",
                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "NUBLADO",
                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "QUENTE",
                    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "FLUOR"
                ).filter { it.first in availableWhiteBalanceModes }
                Text("BALANÇO DE BRANCO", color = Muted, fontSize = 10.sp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    whiteBalanceOptions.forEach { (awbMode, label) ->
                        FilterChip(
                            selected = whiteBalanceMode == awbMode,
                            onClick = {
                                try {
                                    camera2Control?.setCaptureRequestOptions(
                                        CaptureRequestOptions.Builder()
                                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
                                            .build()
                                    )
                                    whiteBalanceMode = awbMode
                                    status = "BALANÇO DE BRANCO: $label"
                                } catch (_: Exception) {
                                    status = "BALANÇO DE BRANCO NÃO SUPORTADO"
                                }
                            },
                            label = { Text(label, fontSize = 9.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Gold,
                                selectedLabelColor = Color.Black,
                                labelColor = Color.White
                            )
                        )
                    }
                }
                if (whiteBalanceOptions.isEmpty()) {
                    Text("BALANÇO DE BRANCO MANUAL NÃO EXPOSTO NESTA CÂMERA", color = Muted, fontSize = 9.sp)
                }
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
                Text("MODO DE FOCO", color = Muted, fontSize = 10.sp)
                val afChoices = listOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO to "AF-S",
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE to "AF-C",
                    CaptureRequest.CONTROL_AF_MODE_OFF to "MF"
                ).filter { it.first in availableAfModes }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    afChoices.forEach { (afMode, label) ->
                        FilterChip(
                            selected = selectedAfMode == afMode,
                            onClick = {
                                try {
                                    camera2Control?.setCaptureRequestOptions(
                                        CaptureRequestOptions.Builder()
                                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
                                            .build()
                                    )
                                    selectedAfMode = afMode
                                    status = when (afMode) {
                                        CaptureRequest.CONTROL_AF_MODE_AUTO -> "FOCO AUTOMÁTICO SIMPLES (AF-S)"
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "FOCO CONTÍNUO (AF-C)"
                                        else -> "FOCO MANUAL (MF)"
                                    }
                                } catch (_: Exception) { status = "MODO DE FOCO NÃO SUPORTADO NESTA CÂMERA" }
                            },
                            label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Color.Black, labelColor = Color.White)
                        )
                    }
                }
                if (afChoices.isEmpty()) Text("MODOS DE FOCO NÃO EXPOSTOS PELO CAMERA2", color = Muted, fontSize = 9.sp)

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
            } else if (mode == "PRO") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = { showProSettings = true }) { Text("ABRIR AJUSTES PRO", color = Gold) }
                }
            } else {
                Text("AJUSTES AUTOMÁTICOS DA CÂMERA", color = Muted, fontSize = 10.sp)
            }

            Text("TEMPORIZADOR DA FOTO", color = Muted, fontSize = 9.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                listOf(0, 3, 5, 10).forEach { seconds ->
                    FilterChip(
                        selected = photoTimerSeconds == seconds,
                        onClick = { photoTimerSeconds = seconds },
                        label = { Text(if (seconds == 0) "DESLIGADO" else "${seconds}s") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold, selectedLabelColor = Color.Black, labelColor = Color.White
                        ),
                        modifier = Modifier.padding(horizontal = 3.dp)
                    )
                }
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
                        } else if (!isCountingDown) {
                            cameraScope.launch {
                                isCountingDown = true
                                try {
                                    if (photoTimerSeconds > 0) {
                                        status = "FOTO EM ${photoTimerSeconds}..."
                                        delay(photoTimerSeconds * 1000L)
                                    }
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
                                                isCountingDown = false
                                            }
                                            override fun onError(exception: ImageCaptureException) {
                                                status = "ERRO AO FOTOGRAFAR: ${exception.message}"
                                                isCountingDown = false
                                            }
                                        }
                                    )
                                } catch (_: Exception) {
                                    status = "NÃO FOI POSSÍVEL INICIAR O TEMPORIZADOR"
                                    isCountingDown = false
                                }
                            }
                        }
                    },
                    enabled = !isCountingDown,
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
                    selectedCameraId = null
                }) { Text("CÂMERA ${if (backCamera) "FRONTAL" else "TRASEIRA"}", color = Muted, fontSize = 10.sp) }
            }
            Text(status, color = Gold, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun classifyScene(labels: List<String>): String {
    val text = labels.joinToString(" ")
    return when {
        listOf("person", "human face", "face", "portrait").any { it in text } -> "RETRATO"
        listOf("night", "darkness", "nightclub", "moon").any { it in text } -> "BAIXA LUMINOSIDADE"
        listOf("food", "dish", "fruit", "cuisine").any { it in text } -> "COMIDA"
        listOf("animal", "dog", "cat", "bird", "mammal").any { it in text } -> "ANIMAL"
        listOf("flower", "plant", "tree", "vegetation").any { it in text } -> "NATUREZA"
        listOf("landscape", "mountain", "beach", "sky", "water").any { it in text } -> "PAISAGEM"
        listOf("document", "text", "paper").any { it in text } -> "DOCUMENTO/TEXTO"
        listOf("car", "vehicle", "motorcycle", "truck").any { it in text } -> "VEÍCULO"
        else -> labels.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "CENA NÃO IDENTIFICADA"
    }
}

@Composable
private fun SmallControl(symbol: String, label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xE6101216),
        shape = RoundedCornerShape(13.dp),
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.13f))
    ) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(symbol, color = Gold, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(label, color = Color.White.copy(alpha = 0.88f), fontSize = 7.sp, letterSpacing = 0.35.sp)
            }
        }
    }
}
