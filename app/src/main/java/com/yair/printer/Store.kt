package com.yair.printer

import android.content.Context

/**
 * Remembers the address the printer was last reachable at, so the app opens
 * ready instead of discovering from scratch every time. Discovery still runs
 * when this address stops answering.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifEmpty { DEFAULT_HOST }
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    companion object {
        /** Where the printer sat when the app was written; only a starting guess. */
        const val DEFAULT_HOST = "10.0.0.10"
        private const val KEY_HOST = "host"
    }
}
