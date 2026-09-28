package com.ema.assistant

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class CentralHubClient(private val context: Context) {
    companion object {
        // EMA uses the existing shared Central Hub AI tunnel. This is not a new
        // EMA-specific tunnel; appId identifies this installation to the shared service.
        private const val HUB_URL = "https://mayobuild-studio.lovable.app/api/ai-tunnel"
        private const val APP_ID = "ema"
        private const val PREFS = "ema_tunnel"
        private const val TOKEN_KEY = "token"
        private const val DEVICE_KEY = "device_id"
    }

    private data class HttpResult(val code: Int, val body: String)

    fun ask(message: String, callback: (String) -> Unit) {
        thread(name = "EMA-CentralHub-AI") {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val deviceId = prefs.getString(TOKEN_KEY, null)
                    ?.takeIf { false } // keep token and device namespaces separate
                val stableDeviceId = prefs.getString(DEVICE_KEY, null)
                    ?: (Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ANDROID_ID
                    ) ?: UUID.randomUUID().toString()).also {
                        prefs.edit().putString(DEVICE_KEY, it).apply()
                    }

                var token = prefs.getString(TOKEN_KEY, null)

                if (token.isNullOrBlank()) {
                    token = register(stableDeviceId, prefs)
                }

                if (token.isNullOrBlank()) {
                    callback("Central Hub AI registration failed.")
                    return@thread
                }

                var result = post(token, JSONObject()
                    .put("action", "ask")
                    .put("appId", APP_ID)
                    .put("deviceId", stableDeviceId)
                    .put("message", message)
                    .toString())

                // A stale/revoked installation token is repaired automatically.
                if (result.code == 401) {
                    token = register(stableDeviceId, prefs)
                    if (token.isNullOrBlank()) {
                        callback("Central Hub AI session expired and could not be renewed.")
                        return@thread
                    }
                    result = post(token, JSONObject()
                        .put("action", "ask")
                        .put("appId", APP_ID)
                        .put("deviceId", stableDeviceId)
                        .put("message", message)
                        .toString())
                }

                val json = runCatching { JSONObject(result.body) }.getOrNull()
                if (result.code !in 200..299 || json?.optBoolean("ok", false) != true) {
                    val detail = json?.optString("error").orEmpty()
                        .ifBlank { "HTTP ${result.code}" }
                    callback("Central Hub AI error: $detail")
                    return@thread
                }

                val answer = json.optString("answer").trim()
                callback(if (answer.isBlank()) {
                    "Central Hub AI returned an empty answer."
                } else {
                    answer
                })
            } catch (e: Exception) {
                callback(
                    "I can still handle phone actions, but Central Hub AI is unavailable: " +
                        (e.message ?: "network error")
                )
            }
        }
    }

    private fun register(deviceId: String, prefs: android.content.SharedPreferences): String? {
        val result = post(null, JSONObject()
            .put("action", "register")
            .put("appId", APP_ID)
            .put("deviceId", deviceId)
            .put("deviceLabel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .toString())

        val json = runCatching { JSONObject(result.body) }.getOrNull()
        if (result.code !in 200..299 || json?.optBoolean("ok", false) != true) return null

        return json.optString("token").trim().takeIf { it.isNotBlank() }?.also {
            prefs.edit().putString(TOKEN_KEY, it).apply()
        }
    }

    private fun post(token: String?, body: String): HttpResult {
        val connection = (URL(HUB_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        return connection.use {
            it.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val stream = if (it.responseCode in 200..299) it.inputStream else it.errorStream
            val responseBody = stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
            HttpResult(it.responseCode, responseBody)
        }
    }
}
