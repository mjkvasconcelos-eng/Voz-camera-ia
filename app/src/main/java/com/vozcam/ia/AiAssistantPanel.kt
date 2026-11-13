package com.vozcam.ia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

private const val DEFAULT_VOZ_API = "https://voz-camera-ia-api.mjk-vasconcelos.workers.dev"

@Composable
fun AiAssistantPanel(
    context: Context,
    imageCapture: ImageCapture?,
    cameraContext: String,
    onClose: () -> Unit
) {
    var endpoint by remember { mutableStateOf(DEFAULT_VOZ_API) }
    var token by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("gemini") }
    var message by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("Olá! Sou seu assistente fotográfico. Configure o token de acesso para começar.") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp),
        color = Color(0xF0181A1F),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 18.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD6A84F))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFD6A84F), shape = RoundedCornerShape(10.dp)) {
                    Text("GPT", color = Color.Black, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Text("ASSISTENTE FOTOGRÁFICO", color = Color.White, fontSize = 11.sp)
                    Text("CONECTADO AO SERVIDOR VOZ CÂMERA IA", color = Color(0xFFD6A84F), fontSize = 8.sp)
                }
                TextButton(onClick = onClose) { Text("✕", color = Color.White) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = provider == "gemini", onClick = { provider = "gemini" }, label = { Text("GEMINI") })
                FilterChip(selected = provider == "openai", onClick = { provider = "openai" }, label = { Text("GPT") })
                FilterChip(selected = provider == "auto", onClick = { provider = "auto" }, label = { Text("AUTO") })
            }
            if (showSettings) {
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it.trim() },
                    label = { Text("Endereço do servidor") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token de acesso (APP_ACCESS_TOKEN)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("O token fica somente na memória enquanto o aplicativo estiver aberto. Não o compartilhe.", color = Color.LightGray, fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (token.isBlank()) answer = "Digite o token de acesso cadastrado como segredo no Cloudflare."
                        else {
                            busy = true
                            scope.launch {
                                answer = try { callVozApi(endpoint, token, provider, "Responda apenas em português do Brasil: confirme que a conexão do Voz Câmera IA está funcionando.", cameraContext, null) }
                                    catch (e: Exception) { "Falha na conexão: ${e.message ?: "verifique a internet, o endereço e o token"}" }
                                busy = false
                                if (!answer.startsWith("Falha")) showSettings = false
                            }
                        }
                    }, enabled = !busy && token.isNotBlank()) { Text("TESTAR E CONECTAR") }
                    TextButton(onClick = { showSettings = false }) { Text("FECHAR AJUSTES", color = Color(0xFFD6A84F)) }
                }
            } else {
                TextButton(onClick = { showSettings = true }) { Text("CONFIGURAÇÃO DA CONEXÃO", color = Color(0xFFD6A84F)) }
            }

            Surface(color = Color(0xFF242830), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(answer, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(11.dp))
            }
            OutlinedTextField(
                value = message, onValueChange = { message = it },
                label = { Text("Pergunte sobre a fotografia...") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val prompt = message.trim()
                        if (prompt.isNotEmpty() && token.isNotBlank()) {
                            busy = true
                            message = ""
                            scope.launch {
                                answer = try { callVozApi(endpoint, token, provider, prompt, cameraContext, null) }
                                    catch (e: Exception) { "Falha na comunicação: ${e.message ?: "tente novamente"}" }
                                busy = false
                            }
                        } else if (token.isBlank()) answer = "Abra Configuração da conexão e informe seu token."
                    },
                    enabled = !busy && message.isNotBlank() && token.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6A84F))
                ) { Text(if (busy) "AGUARDE..." else "ENVIAR", color = Color.Black) }

                OutlinedButton(
                    onClick = {
                        if (token.isBlank()) { answer = "Configure primeiro o token de acesso."; return@OutlinedButton }
                        val capture = imageCapture
                        if (capture == null || busy) { answer = "A câmera ainda não está pronta."; return@OutlinedButton }
                        busy = true
                        val photo = File(context.cacheDir, "voz_camera_analise.jpg")
                        val output = ImageCapture.OutputFileOptions.Builder(photo).build()
                        capture.takePicture(output, ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        answer = try {
                                            val dataUrl = withContext(Dispatchers.IO) { imageFileToDataUrl(photo) }
                                            callVozApi(endpoint, token, provider, "Analise esta fotografia e recomende configurações adequadas de ISO, velocidade do obturador, foco, exposição e lente. Explique brevemente o motivo.", cameraContext, dataUrl)
                                        } catch (e: Exception) { "Não foi possível analisar a foto: ${e.message ?: "erro desconhecido"}" }
                                        busy = false
                                    }
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    answer = "Erro ao capturar a foto: ${exception.message ?: "falha na câmera"}"
                                    busy = false
                                }
                            })
                    },
                    enabled = !busy && token.isNotBlank()
                ) { Text("ANALISAR FOTO") }
            }
        }
    }
}

private suspend fun callVozApi(
    endpoint: String,
    token: String,
    provider: String,
    message: String,
    cameraContext: String,
    imageDataUrl: String?
): String = withContext(Dispatchers.IO) {
    val base = endpoint.trim().trimEnd('/')
    require(base.startsWith("https://")) { "O endereço precisa começar com https://" }
    val connection = (URL("$base/v1/${if (imageDataUrl == null) "chat" else "analyze"}").openConnection() as HttpURLConnection)
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Authorization", "Bearer $token")
        val body = JSONObject().put("message", message).put("context", cameraContext).put("provider", provider)
        if (imageDataUrl != null) body.put("imageDataUrl", imageDataUrl)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val json = try { JSONObject(response) } catch (_: Exception) { JSONObject() }
        if (code !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "servidor retornou HTTP $code" })
        json.optString("answer").ifBlank { "O servidor respondeu, mas não enviou uma resposta legível." }
    } finally { connection.disconnect() }
}

private fun imageFileToDataUrl(file: File): String {
    val original = BitmapFactory.decodeFile(file.absolutePath) ?: error("Não foi possível ler a imagem.")
    val maxSide = 1600
    val scale = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
    val bytes = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 78, bytes)
    if (resized !== original) resized.recycle()
    original.recycle()
    val encoded = Base64.getEncoder().encodeToString(bytes.toByteArray())
    return "data:image/jpeg;base64,$encoded"
}
