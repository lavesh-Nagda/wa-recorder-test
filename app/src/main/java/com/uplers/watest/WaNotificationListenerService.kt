package com.uplers.watest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class WaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WaNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected — ready to detect WA calls")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val n = sbn.notification ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (n.category != Notification.CATEGORY_CALL) return
        if (!WaCallPrefs.isMonitoringEnabled(this)) return

        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown"
        val startedAtMs = System.currentTimeMillis()
        WaCallPrefs.markCallStarted(this, title, startedAtMs)
        Log.i(TAG, "WA call STARTED — caller=$title pkg=$pkg")
        WaCallRecorderFgService.startRecording(this, title, startedAtMs)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val n = sbn.notification ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (n.category != Notification.CATEGORY_CALL) return
        if (!WaCallPrefs.isMonitoringEnabled(this)) return

        val activeWa = WaCallPrefs.getActiveWaNumber(this) ?: return
        val stillActive = try {
            activeNotifications?.any { a ->
                (a.packageName == "com.whatsapp" || a.packageName == "com.whatsapp.w4b") &&
                    a.notification?.category == Notification.CATEGORY_CALL
            } ?: false
        } catch (_: Exception) { false }

        if (stillActive) return
        Log.i(TAG, "WA call ENDED — caller=$activeWa")
        WaCallPrefs.clearActiveCall(this)
        WaCallRecorderFgService.stopRecording(this)
    }
}
