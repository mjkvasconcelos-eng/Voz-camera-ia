package com.vozcam.ia

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

private val Gold = Color(0xFFD6A84F)
private val Dark = Color(0xFF090909)

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
    var mode by remember { mutableStateOf("IA AUTOMÁTICA") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Pronto para fotografar") }
    var backCamera by remember { mutableStateOf(true) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().background(Dark), contentAlignment = Alignment.Center) {
            Text("Permissão de câmera necessária.", color = Color.White)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Dark)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("VOZ CÂMERA", color = Color.White, fontSize = 22.sp)
                Text("INTELIGÊNCIA FOTOGRÁFICA", color = Gold, fontSize = 10.sp)
            }
            Text("IA", color = Gold, fontSize = 20.sp)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()
                        val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA
                            else CameraSelector.DEFAULT_FRONT_CAMERA
                        try {
                            provider.unbindAll()
                            val bound = provider.bindToLifecycle(
                                lifecycleOwner, selector, preview, capture
                            )
                            imageCapture = capture
                            camera = bound
                        } catch (e: Exception) {
                            status = "Não foi possível iniciar a câmera"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                update = { }
            )
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color(0xAA111111),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(mode, color = Gold, fontSize = 11.sp)
                    Text(
                        if (mode == "IA AUTOMÁTICA") "Ajuste de exposição assistido"
                        else "Controle de exposição",
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "IA AUTOMÁTICA",
                    onClick = { mode = "IA AUTOMÁTICA" },
                    label = { Text("IA automática") }
                )
                FilterChip(
                    selected = mode == "PROFISSIONAL",
                    onClick = { mode = "PROFISSIONAL" },
                    label = { Text("Profissional") }
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Exposição  ${"%.1f".format(exposure)} EV", color = Color.White)
            Slider(
                value = exposure,
                onValueChange = {
                    exposure = it
                    camera?.cameraControl?.setExposureCompensationIndex(
                        it.toInt().coerceIn(
                            camera?.cameraInfo?.exposureState?.exposureCompensationRange?.lower ?: -3,
                            camera?.cameraInfo?.exposureState?.exposureCompensationRange?.upper ?: 3
                        )
                    )
                },
                valueRange = -3f..3f,
                colors = SliderDefaults.colors(
                    thumbColor = Gold,
                    activeTrackColor = Gold
                )
            )
            Text(status, color = Color.LightGray, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    backCamera = !backCamera
                    status = "Para trocar a câmera, reinicie a visualização nesta versão inicial."
                }) { Text("Virar câmera", color = Color.White) }

                Button(
                    onClick = {
                        val capture = imageCapture
                        if (capture == null) {
                            status = "Câmera ainda inicializando"
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
                            capture.takePicture(
                                output,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                        status = "Foto salva em Pictures/VozCameraIA"
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        status = "Erro ao salvar: ${exception.message}"
                                    }
                                }
                            )
                            status = "Capturando fotografia..."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(76.dp)
                ) {
                    Text("FOTO", color = Color.Black, fontSize = 12.sp)
                }
                TextButton(onClick = { exposure = 0f; camera?.cameraControl?.setExposureCompensationIndex(0) }) {
                    Text("Reset EV", color = Color.White)
                }
            }
        }
    }
}
