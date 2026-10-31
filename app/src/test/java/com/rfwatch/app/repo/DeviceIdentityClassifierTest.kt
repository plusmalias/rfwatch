package com.rfwatch.app.repo

import com.rfwatch.core.cs.BatteryHint
import com.rfwatch.core.cs.TrackerMatch
import com.rfwatch.core.cs.TrackerState
import com.rfwatch.core.cs.TrackerType
import com.rfwatch.core.model.Band
import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleCategory
import com.rfwatch.core.rules.RuleHit
import com.rfwatch.core.rules.Threat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdentityClassifierTest {
    @Test fun trackerSignatureProvidesBrandAndSpecificCategory() {
        val match = TrackerMatch(TrackerType.APPLE_FIND_MY, TrackerState.NEARBY, BatteryHint.UNKNOWN, null, .9, "fixture")
        val identity = DeviceIdentityClassifier.classify(Band.BLE, DeviceFacts(null, null, null), match, emptyList())
        assertEquals("Apple", identity.brand)
        assertEquals("Bluetooth item tracker", identity.category)
    }

    @Test fun matchingRuleMakesTheCategoryExplicitlyTentative() {
        val hit = RuleHit("cameras_iot", RuleCategory.CAMERA_IOT, "cam", "Camera", Threat.NOTABLE, "name")
        val identity = DeviceIdentityClassifier.classify(Band.WIFI, DeviceFacts(null, null, "camera"), null, listOf(hit))
        assertEquals("Camera / IoT candidate", identity.category)
    }

    @Test fun unknownWifiVendorRemainsUnknownRatherThanGuessing() {
        val identity = DeviceIdentityClassifier.classify(Band.WIFI, DeviceFacts("02:11:22:33:44:55", "Guest", null), null, emptyList())
        assertNull(identity.brand)
        assertEquals("Wi-Fi access point / device", identity.category)
    }
}
