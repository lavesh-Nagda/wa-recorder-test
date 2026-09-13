package com.uplers.watest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvListenerStatus: TextView
    private lateinit var tvLastRecording: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var btnBatteryOpt: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createNotificationChannel()

        tvListenerStatus = findViewById(R.id.tvListenerStatus)
        tvLastRecording  = findViewById(R.id.tvLastRecording)
        btnNotifAccess   = findViewById(R.id.btnNotifAccess)
        btnBatteryOpt    = findViewById(R.id.btnBatteryOpt)

        btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnBatteryOpt.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.postDelayed(refreshRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refreshStatus() {
        val enabled = isNotificationListenerEnabled()
        tvListenerStatus.text = if (enabled) "Notification Listener: ACTIVE ✅" else "Notification Listener: NOT GRANTED ❌"
        tvListenerStatus.setTextColor(if (enabled) 0xFF1B8A2E.toInt() else 0xFFCC0000.toInt())
        tvLastRecording.text = "Last recording:\n${WaCallPrefs.getLastRecordingInfo(this)}"
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                RecordingUploadNotificationHelper.CHANNEL_ID,
                "WA Call Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Used during WA call recording and upload" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
