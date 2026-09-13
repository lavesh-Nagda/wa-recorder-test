package com.uplers.watest

import android.content.Context

object WaCallPrefs {
    private const val PREFS = "wa_test_call"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    private const val KEY_ACTIVE_WA_NUMBER = "active_wa_number"
    private const val KEY_ACTIVE_STARTED_AT_MS = "active_started_at_ms"
    private const val KEY_LAST_RECORDING_INFO = "last_recording_info"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMonitoringEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_MONITORING_ENABLED, true)

    fun markCallStarted(ctx: Context, waNumber: String, startedAtMs: Long) {
        prefs(ctx).edit()
            .putString(KEY_ACTIVE_WA_NUMBER, waNumber)
            .putLong(KEY_ACTIVE_STARTED_AT_MS, startedAtMs)
            .apply()
    }

    fun getActiveWaNumber(ctx: Context): String? = prefs(ctx).getString(KEY_ACTIVE_WA_NUMBER, null)

    fun clearActiveCall(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ACTIVE_WA_NUMBER)
            .remove(KEY_ACTIVE_STARTED_AT_MS)
            .apply()
    }

    fun setLastRecordingInfo(ctx: Context, info: String) {
        prefs(ctx).edit().putString(KEY_LAST_RECORDING_INFO, info).apply()
    }

    fun getLastRecordingInfo(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_RECORDING_INFO, "None yet") ?: "None yet"
}
