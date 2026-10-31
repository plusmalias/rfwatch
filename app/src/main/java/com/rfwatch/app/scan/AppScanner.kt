package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.rfwatch.core.cs.AppFacts
import java.security.MessageDigest

/** Gathers [AppFacts] for every installed app (needs QUERY_ALL_PACKAGES) for the stalkerware check. */
class AppScanner(private val context: Context) {

    @SuppressLint("QueryPermissionsNeeded")
    fun scan(): List<AppFacts> {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS.toLong() or PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        else @Suppress("DEPRECATION") pm.getInstalledPackages(flags.toInt())

        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val accessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val admins = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).activeAdmins.orEmpty()
            .map { it.packageName }.toSet()

        return packages.filter { it.packageName != context.packageName }.map { p ->
            val app = p.applicationInfo
            val granted = p.requestedPermissions.orEmpty().filterIndexed { i, _ ->
                (p.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }.toSet()
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p.packageName).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(p.packageName)
            }.getOrNull()
            AppFacts(
                packageName = p.packageName,
                label = app?.loadLabel(pm)?.toString() ?: p.packageName,
                certSha1 = signerSha1(p),
                apkSha256 = if (app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0) apkSha256(app.sourceDir) else null,
                isSystem = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installer = installer,
                hasLauncherIcon = p.packageName in launchable,
                accessibilityEnabled = accessibility.contains("${p.packageName}/"),
                deviceAdminActive = p.packageName in admins,
                notificationListenerEnabled = listeners.contains("${p.packageName}/"),
                grantedPermissions = granted,
                firstInstallMs = p.firstInstallTime,
                lastUpdateMs = p.lastUpdateTime,
            )
        }
    }

    /** Streams the installed base.apk through SHA-256 (APK files are world-readable). */
    private fun apkSha256(path: String?): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.File(path ?: return null).inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun signerSha1(p: PackageInfo): List<String> = runCatching {
        val signers = p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }.orEmpty()
        signers.map { sig -> MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) } }
    }.getOrDefault(emptyList())
}
