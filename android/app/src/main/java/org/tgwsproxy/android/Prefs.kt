package org.tgwsproxy.android

import android.content.Context
import com.chaquo.python.Python

/**
 * Port and secret, kept across restarts so the proxy entry already saved in
 * Telegram keeps working — a fresh secret every launch would mean re-adding it
 * by hand each time.
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("proxy", Context.MODE_PRIVATE)

    val port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    val secret: String
        get() {
            prefs.getString(KEY_SECRET, null)?.let { return it }
            val generated = Python.getInstance()
                .getModule("proxy_bridge")
                .callAttr("generate_secret")
                .toString()
            prefs.edit().putString(KEY_SECRET, generated).apply()
            return generated
        }

    fun setPort(value: Int) {
        prefs.edit().putInt(KEY_PORT, value).apply()
    }

    fun regenerateSecret() {
        prefs.edit().remove(KEY_SECRET).apply()
    }

    companion object {
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret"
        const val DEFAULT_PORT = 1443
    }
}
