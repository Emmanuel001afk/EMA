package com.ema.assistant

import android.content.Context

/** App-private atomic persistence for [EmaWorkspace]. Nothing leaves the phone. */
object EmaPersonalWorkspaceStore {
    private const val PREFS = "ema_workspace_store"
    private const val KEY = "workspace_v1"

    fun load(context: Context): EmaWorkspace =
        EmaWorkspace.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, workspace: EmaWorkspace): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, EmaWorkspace.encode(workspace))
            .commit()

    fun clear(context: Context): Boolean = save(context, EmaWorkspace())
}
