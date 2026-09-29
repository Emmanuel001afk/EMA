package com.ema.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object EmaConversationStore {
    private const val PREFS = "ema_conversation"
    private const val KEY_TURNS = "turns"
    private const val MAX_TURNS = 20

    data class Turn(val role: String, val text: String)

    @Synchronized
    fun add(context: Context, role: String, text: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_TURNS, "[]")) }.getOrElse { JSONArray() }
        array.put(JSONObject().put("role", role).put("text", text.take(4000)))
        while (array.length() > MAX_TURNS) array.remove(0)
        prefs.edit().putString(KEY_TURNS, array.toString()).apply()
    }

    fun recent(context: Context, maxTurns: Int = 12): List<Turn> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_TURNS, "[]")) }.getOrElse { JSONArray() }
        val start = (array.length() - maxTurns).coerceAtLeast(0)
        return buildList {
            for (i in start until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(Turn(item.optString("role"), item.optString("text")))
            }
        }
    }

    fun contextText(context: Context, maxTurns: Int = 10): String =
        recent(context, maxTurns).joinToString("\n") { t -> t.role + ": " + t.text }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TURNS).apply()
    }
}
