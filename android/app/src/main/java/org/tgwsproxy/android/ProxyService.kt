package org.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Runs the proxy for as long as the notification is up.
 *
 * It has to be a foreground service: Telegram keeps a long-lived connection to
 * 127.0.0.1, and a background process would be frozen or killed within minutes,
 * silently breaking Telegram with no hint as to why.
 */
class ProxyService : Service() {

    private lateinit var bridge: PyObject
    private val handler = Handler(Looper.getMainLooper())
    private var statusTicker: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        bridge = Python.getInstance().getModule("proxy_bridge")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                return START_NOT_STICKY
            }
        }

        val prefs = Prefs(this)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_starting)))

        try {
            bridge.callAttr("start", prefs.port, prefs.secret)
            Log.i(TAG, "proxy started on port ${prefs.port}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start proxy", e)
            updateNotification(getString(R.string.status_error, e.message ?: "?"))
            return START_NOT_STICKY
        }

        startStatusTicker()
        // START_STICKY: if the system reclaims us under memory pressure, come
        // back up rather than leaving Telegram pointed at a dead port.
        return START_STICKY
    }

    private fun startStatusTicker() {
        stopStatusTicker()
        val ticker = object : Runnable {
            override fun run() {
                val status = try {
                    bridge.callAttr("status").toString()
                } catch (e: Exception) {
                    e.message ?: "?"
                }
                updateNotification(status)
                handler.postDelayed(this, STATUS_INTERVAL_MS)
            }
        }
        statusTicker = ticker
        handler.postDelayed(ticker, STATUS_INTERVAL_MS)
    }

    private fun stopStatusTicker() {
        statusTicker?.let { handler.removeCallbacks(it) }
        statusTicker = null
    }

    private fun stopProxy() {
        stopStatusTicker()
        try {
            bridge.callAttr("stop")
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopStatusTicker()
        try {
            bridge.callAttr("stop")
        } catch (e: Exception) {
            Log.w(TAG, "stop on destroy failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            // Low: this notification is a requirement, not news.
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.action_stop), stop,
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_INTERVAL_MS = 5_000L
        const val ACTION_STOP = "org.tgwsproxy.android.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProxyService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
