package com.onegallery.sync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {

    @Test
    fun wifiWithInternetCountsEvenWhenAndroidDidNotValidateIt() {
        assertTrue(
            networkCapabilitiesAreOnline(
                hasInternet = true,
                validated = false,
                captivePortal = false,
                wifi = true,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        )
    }

    @Test
    fun mobileDataAndVpnCountWithoutTheGoogleProbe() {
        assertTrue(
            networkCapabilitiesAreOnline(
                hasInternet = true,
                validated = false,
                captivePortal = false,
                wifi = false,
                cellular = true,
                ethernet = false,
                vpn = false,
            ),
        )
        assertTrue(
            networkCapabilitiesAreOnline(
                hasInternet = true,
                validated = false,
                captivePortal = false,
                wifi = false,
                cellular = false,
                ethernet = false,
                vpn = true,
            ),
        )
    }

    @Test
    fun captivePortalAndNoLinkStayOffline() {
        assertFalse(
            networkCapabilitiesAreOnline(
                hasInternet = true,
                validated = true,
                captivePortal = true,
                wifi = true,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        )
        assertFalse(
            networkCapabilitiesAreOnline(
                hasInternet = false,
                validated = true,
                captivePortal = false,
                wifi = true,
                cellular = true,
                ethernet = true,
                vpn = true,
            ),
        )
        assertFalse(
            networkCapabilitiesAreOnline(
                hasInternet = true,
                validated = false,
                captivePortal = false,
                wifi = false,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        )
    }
}
