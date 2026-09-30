package com.ema.assistant

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class CentralHubClient(private val context: Context) {
    companion object {
        private const val HUB_URL = "https://qnogfzwmdjggqtdrkzpv.supabase.co/functions/v1/ai-tunnel"
        private const val APP_ID = "com.ema.assistant"
        private const val PREFS = "ema_tunnel"
        private const val TOKEN_KEY = "token_v2"
        private const val DEVICE_KEY = "device_id_v2"
    }

    private data class HttpResult(val code: Int, val body: String)

    fun ask(message: String, callback: (String) -> Unit) {
        thread(name = "EMA-CentralHub-AI") {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val stableDeviceId = prefs.getString(DEVICE_KEY, null)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString(DEVICE_KEY, it).apply()
                    }

                var token = prefs.getString(TOKEN_KEY, null)
                if (token.isNullOrBlank()) token = register(stableDeviceId, prefs)
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
                    val detail = json?.optString("error").orEmpty().ifBlank { "HTTP ${result.code}" }
                    callback("Central Hub AI error: $detail")
                    return@thread
                }

                val answer = extractAnswer(json)
                callback(answer.ifBlank { "Central Hub AI returned an empty answer." })
            } catch (e: Exception) {
                callback(
                    "I can still handle phone actions, but Central Hub AI is unavailable: " +
                        (e.message ?: "network error")
                )
            }
        }
    }

    private fun extractAnswer(json: JSONObject): String {
        val direct = listOf("answer", "response", "message", "content")
            .asSequence()
            .map { json.optString(it).trim() }
            .firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return direct

        val result = json.optJSONObject("result")
        val nested = result?.let {
            listOf("answer", "response", "message", "content")
                .asSequence()
                .map { key -> it.optString(key).trim() }
                .firstOrNull { value -> value.isNotBlank() }
        }
        if (!nested.isNullOrBlank()) return nested

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            val content = message?.optString("content")?.trim().orEmpty()
            if (content.isNotBlank()) return content
        }

        return ""
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
        val connection = URL(HUB_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
            return HttpResult(responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
