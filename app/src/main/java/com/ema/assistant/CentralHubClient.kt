package com.ema.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class CentralHubClient(private val context: Context) {
    companion object {
        private const val APP_ID = "com.ema.assistant"
        private const val PREFS = "ema_tunnel"
        private const val TOKEN_KEY = "token_v2"
        private const val DEVICE_KEY = "device_id_v2"
        private const val HUB_URL_KEY = "hub_url"
        private val REGISTRATION_LOCK = Any()

        /**
         * Build-time default Central Hub endpoint.
         *
         * Overridable at build time with `-PemaHubUrl=https://...` and at runtime
         * from EMA's settings via [setHubUrl]. This used to be a hardcoded
         * constant, so a retired or mismatched Hub left the AI layer silently
         * dead while the UI kept offering it.
         */
        const val DEFAULT_HUB_URL: String = BuildConfig.EMA_HUB_URL

        /** The endpoint EMA will actually use, honouring any saved override. */
        fun hubUrl(context: Context): String {
            val saved = runCatching { prefs(context).getString(HUB_URL_KEY, null) }
                .getOrNull()?.trim().orEmpty()
            return saved.ifBlank { DEFAULT_HUB_URL.trim() }
        }

        /**
         * True only when there is an endpoint to reach. Callers use this to avoid
         * promising an AI answer they cannot deliver.
         */
        fun isConfigured(context: Context): Boolean = hubUrl(context).isNotBlank()

        /** Save a Hub endpoint; pass a blank value to fall back to the build default. */
        fun setHubUrl(context: Context, url: String) {
            val normalized = url.trim()
            val editor = prefs(context).edit()
            if (normalized.isEmpty()) editor.remove(HUB_URL_KEY) else editor.putString(HUB_URL_KEY, normalized)
            editor.apply()
        }

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val hubUrl: String = hubUrl(context)

    private data class HttpResult(val code: Int, val body: String)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun deliver(callback: (String) -> Unit, value: String) {
        mainHandler.post { runCatching { callback(value) } }
    }

    fun ask(message: String, callback: (String) -> Unit) {
        thread(name = "EMA-CentralHub-AI") {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val stableDeviceId = prefs.getString(DEVICE_KEY, null)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString(DEVICE_KEY, it).apply()
                    }

                var token = prefs.getString(TOKEN_KEY, null)
                if (token.isNullOrBlank()) {
                    token = synchronized(REGISTRATION_LOCK) {
                        // Re-read after acquiring the lock so concurrent assistant
                        // requests cannot register the same device twice.
                        prefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
                            ?: register(stableDeviceId, prefs)
                    }
                }
                if (token.isNullOrBlank()) {
                    deliver(callback, "I could not get an answer right now. Please try that again.")
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
                        deliver(callback, "I could not reconnect to the answer service. Please try that again.")
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
                    android.util.Log.w("EMA", "AI tunnel error: $detail")
                    deliver(callback, "I could not answer that right now. Please try again.")
                    return@thread
                }

                val answer = extractAnswer(json)
                deliver(callback, answer.ifBlank { "I did not receive an answer. Please try that again." })
            } catch (e: Exception) {
                android.util.Log.e("EMA", "AI tunnel request failed", e)
                deliver(callback, "I could not answer that right now. Your EMA device controls are still available.")
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
        val connection = URL(hubUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "EMA-Android/1.0")
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
