package com.vozcam.ia

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String?
)

object AppUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/mjkvasconcelos-eng/Voz-camera-ia/releases/latest"

    suspend fun checkLatest(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Voz-Camera-IA-Android")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@withContext null
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installedCode = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            if (code <= installedCode) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) &&
                    url.startsWith("https://github.com/")) {
                    val digest = asset.optString("digest").takeIf {
                        it.startsWith("sha256:")
                    }?.removePrefix("sha256:")
                    return@withContext AppUpdate(
                        versionCode = code,
                        versionName = tag.removePrefix("v"),
                        downloadUrl = url,
                        sha256 = digest
                    )
                }
            }
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, update: AppUpdate): File = withContext(Dispatchers.IO) {
        val directory = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Não foi possível criar a pasta de atualização.")
        }
        val apk = File(directory, "voz-camera-ia-update.apk")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", "Voz-Camera-IA-Android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Falha ao baixar o APK (HTTP ${connection.responseCode}).")
            }
            connection.inputStream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
            if (!apk.exists() || apk.length() < 1_000_000L) {
                apk.delete()
                throw IllegalStateException("O arquivo baixado está incompleto.")
            }
            update.sha256?.let { expected ->
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(apk.readBytes())
                    .joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    apk.delete()
                    throw SecurityException("A verificação de integridade do APK falhou.")
                }
            }
            apk
        } finally {
            connection.disconnect()
        }
    }

    fun installOrRequestPermission(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val installer = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installer)
        return true
    }
}
