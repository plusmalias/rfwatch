package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerwareTest {

    private val now = 1_800_000_000_000L

    @Test fun indicatorDbLoads() {
        assertEquals("TheTruthSpy", StalkerwareDb.byPackage("com.thetruth")?.name)
        assertEquals("TheTruthSpy", StalkerwareDb.byCertSha1("31A6ECECD97CF39BC4126B8745CD94A7C30BF81C")?.name)
        assertTrue(StalkerwareDb.familyCount > 100)
    }

    @Test fun knownPackageIsCritical() {
        val a = StalkerwareScorer.assess(AppFacts("com.mspy.lite", "Update Service"), now)
        assertEquals(AppRisk.CRITICAL, a.risk)
        assertTrue(a.reasons.first().contains("mSpy"))
    }

    @Test fun knownCertificateUnderARenamedPackageIsCritical() {
        val a = StalkerwareScorer.assess(AppFacts("com.innocent.looking", "Calculator", certSha1 = listOf("31A6ECECD97CF39BC4126B8745CD94A7C30BF81C")), now)
        assertEquals(AppRisk.CRITICAL, a.risk)
    }

    @Test fun hiddenSideloadedAccessibilityAppIsHigh() {
        val a = StalkerwareScorer.assess(
            AppFacts(
                "com.example.sync", "System Sync Service", hasLauncherIcon = false, accessibilityEnabled = true,
                deviceAdminActive = true, grantedPermissions = setOf("android.permission.READ_SMS", "android.permission.ACCESS_BACKGROUND_LOCATION"),
            ),
            now,
        )
        assertEquals(AppRisk.HIGH, a.risk)
        assertTrue(a.reasons.any { it.contains("Accessibility") })
    }

    @Test fun ordinaryStoreAppIsLow() {
        val a = StalkerwareScorer.assess(AppFacts("com.spotify.music", "Spotify", installer = "com.android.vending", grantedPermissions = setOf("android.permission.RECORD_AUDIO")), now)
        assertEquals(AppRisk.LOW, a.risk)
    }

    @Test fun apkHashAndSpywareCampaignIndicators() {
        assertTrue(StalkerwareDb.apkHashCount > 3000)
        val h = "2f3bc9ebe8c249f0416f5b0367f34b4b8f6aaa7d351dc5b4912fe2558491b6b7"
        assertEquals("1TopSpy", StalkerwareDb.byApkSha256(h)?.name)
        val a = StalkerwareScorer.assess(AppFacts("com.renamed.app", "Notes", apkSha256 = h.uppercase()), now)
        assertEquals(AppRisk.CRITICAL, a.risk)
        assertTrue(a.reasons.first().contains("APK file SHA-256"))
    }

    @Test fun ctsShimSignedWithPublicTestKeyIsNotStalkerware() {
        // Seen on a ZTE P660: system CTS shims in /apex signed with the AOSP testkey, which the
        // indicator list also has for AllTracker.
        val a = StalkerwareScorer.assess(
            AppFacts("com.android.cts.ctsshim", "com.android.cts.ctsshim", certSha1 = listOf("61ED377E85D386A8DFEE6B864BD85B0BFAA5AF81"),
                isSystem = true, hasLauncherIcon = false), now,
        )
        assertEquals(AppRisk.LOW, a.risk)
        assertTrue(a.reasons.any { it.contains("public") })
    }

    @Test fun systemAppPermissionsAreNotCountedButUserGrantedPowersAre() {
        val shell = AppFacts("com.android.shell", "Shell", isSystem = true, hasLauncherIcon = false,
            grantedPermissions = setOf("android.permission.READ_SMS", "android.permission.READ_CALL_LOG", "android.permission.RECORD_AUDIO"))
        assertEquals(AppRisk.LOW, StalkerwareScorer.assess(shell, now).risk)
        val sysWithAccessibility = shell.copy(packageName = "com.vendor.sys", accessibilityEnabled = true, deviceAdminActive = true, notificationListenerEnabled = true)
        assertEquals(AppRisk.HIGH, StalkerwareScorer.assess(sysWithAccessibility, now).risk)
    }

    @Test fun userAppWithPublicKeyStillScoredOnBehaviour() {
        val a = StalkerwareScorer.assess(
            AppFacts("com.x.spy", "System Service", certSha1 = listOf("61ED377E85D386A8DFEE6B864BD85B0BFAA5AF81"),
                hasLauncherIcon = false, accessibilityEnabled = true, grantedPermissions = setOf("android.permission.READ_SMS")), now,
        )
        assertEquals(AppRisk.HIGH, a.risk)
    }
}
