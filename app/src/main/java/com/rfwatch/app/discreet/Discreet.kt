package com.rfwatch.app.discreet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Discreet mode for people whose phone may be checked by the person they're worried about:
 * a disguised launcher entry ("Calculator"), a PIN entered through a working calculator, neutral
 * notification text, no spoken alerts, screenshots / Recents previews blocked, shake-to-hide.
 */
object Discreet {
    private const val PREFS = "rfwatch_settings"
    const val PREF_DISGUISE = "discreet_disguise"
    const val PREF_NEUTRAL_NOTIFICATIONS = "discreet_notifications"
    const val PREF_SHAKE_HIDE = "discreet_shake_hide"
    private const val PREF_PIN_HASH = "discreet_pin_hash"
    private const val PREF_PIN_SALT = "discreet_pin_salt"
    const val PREF_APP_LOCK = "app_lock"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDisguised(c: Context) = prefs(c).getBoolean(PREF_DISGUISE, false)
    fun neutralNotifications(c: Context) = prefs(c).getBoolean(PREF_NEUTRAL_NOTIFICATIONS, false) || isDisguised(c)
    fun shakeToHide(c: Context) = prefs(c).getBoolean(PREF_SHAKE_HIDE, false)
    fun hasPin(c: Context) = prefs(c).contains(PREF_PIN_HASH)
    fun biometricLock(c: Context) = prefs(c).getBoolean(PREF_APP_LOCK, false)
    /** Any *usable* lock configured → the app starts locked and relocks when backgrounded. */
    fun lockEnabled(c: Context) = hasPin(c) || (biometricLock(c) && canUseDeviceCredential(c))

    /** True when the phone has a screen lock (PIN / pattern / password) or biometric we can prompt for. */
    fun canUseDeviceCredential(c: Context): Boolean = androidx.biometric.BiometricManager.from(c).canAuthenticate(
        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

    /** Swap the launcher alias. The launcher may take a few seconds to refresh its icon. */
    fun setDisguised(c: Context, on: Boolean) {
        val pm = c.packageManager
        fun set(alias: String, enabled: Boolean) = pm.setComponentEnabledSetting(
            ComponentName(c.packageName, "com.rfwatch.app.$alias"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        set("LauncherCalculator", on)
        set("LauncherDefault", !on)
        prefs(c).edit().putBoolean(PREF_DISGUISE, on).apply()
    }

    fun setPin(c: Context, pin: String?) {
        val e = prefs(c).edit()
        if (pin.isNullOrBlank()) e.remove(PREF_PIN_HASH).remove(PREF_PIN_SALT)
        else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            e.putString(PREF_PIN_SALT, salt).putString(PREF_PIN_HASH, hash(salt, pin))
        }
        e.apply()
    }

    fun verifyPin(c: Context, pin: String): Boolean {
        val p = prefs(c)
        val salt = p.getString(PREF_PIN_SALT, null) ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), (p.getString(PREF_PIN_HASH, "") ?: "").toByteArray())
    }

    private fun hash(salt: String, pin: String): String {
        var d = (salt + pin).toByteArray()
        repeat(10_000) { d = MessageDigest.getInstance("SHA-256").digest(d) }
        return d.joinToString("") { "%02x".format(it) }
    }
}
