package org.tgwsproxy.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * One screen: start/stop the proxy, and hand the connection details to
 * Telegram. Everything the user has to do afterwards happens in Telegram
 * itself, so the copy and open actions are the important part.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var detailsView: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        prefs = Prefs(this)

        statusView = findViewById(R.id.status)
        detailsView = findViewById(R.id.details)
        toggleButton = findViewById(R.id.toggle)

        toggleButton.setOnClickListener { toggle() }
        findViewById<Button>(R.id.copy).setOnClickListener { copySecret() }
        findViewById<Button>(R.id.open).setOnClickListener { openInTelegram() }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun isRunning(): Boolean = try {
        Python.getInstance().getModule("proxy_bridge").callAttr("is_running").toBoolean()
    } catch (e: Exception) {
        false
    }

    private fun toggle() {
        if (isRunning()) {
            ProxyService.stop(this)
        } else {
            ProxyService.start(this)
        }
        // The service starts asynchronously; give it a moment before reading back.
        toggleButton.postDelayed({ render() }, 700)
    }

    private fun render() {
        val running = isRunning()
        statusView.setText(if (running) R.string.status_running else R.string.status_stopped)
        toggleButton.setText(if (running) R.string.action_stop else R.string.action_start)
        detailsView.text = getString(
            R.string.details,
            prefs.port,
            prefs.secret,
        )
    }

    private fun copySecret() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("secret", prefs.secret))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * tg://proxy makes Telegram open its "connect to proxy" dialog prefilled,
     * which is far less error-prone than typing a 32-character secret.
     */
    private fun openInTelegram() {
        val link = Python.getInstance()
            .getModule("proxy_bridge")
            .callAttr("link", prefs.port, prefs.secret)
            .toString()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_telegram, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1,
            )
        }
    }
}
