package com.ezhou.adbconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkGateTest {
    @Test
    fun leavingWifiDisablesWhenDebuggingIsOn() {
        assertEquals(
            GateAction.DISABLE,
            gateAction(onWifi = false, ssid = "Home", allowed = setOf("Home"), debuggingOn = true),
        )
    }

    @Test
    fun leavingWifiIsANoOpWhenDebuggingIsOff() {
        assertEquals(
            GateAction.NONE,
            gateAction(onWifi = false, ssid = "Home", allowed = setOf("Home"), debuggingOn = false),
        )
    }

    @Test
    fun leavingWifiDisablesEvenWhenTheSsidIsUnreadable() {
        assertEquals(
            GateAction.DISABLE,
            gateAction(onWifi = false, ssid = null, allowed = emptySet(), debuggingOn = true),
        )
    }

    @Test
    fun anUnreadableSsidIsLeftAlone() {
        assertEquals(
            GateAction.NONE,
            gateAction(onWifi = true, ssid = null, allowed = setOf("Home"), debuggingOn = true),
        )
        assertEquals(
            GateAction.NONE,
            gateAction(onWifi = true, ssid = null, allowed = emptySet(), debuggingOn = false),
        )
    }

    @Test
    fun anAllowedNetworkEnablesWhenDebuggingIsOff() {
        assertEquals(
            GateAction.ENABLE,
            gateAction(onWifi = true, ssid = "Home", allowed = setOf("Home", "Office"), debuggingOn = false),
        )
    }

    @Test
    fun anAllowedNetworkIsANoOpWhenDebuggingIsAlreadyOn() {
        assertEquals(
            GateAction.NONE,
            gateAction(onWifi = true, ssid = "Home", allowed = setOf("Home"), debuggingOn = true),
        )
    }

    @Test
    fun aNetworkOffTheListDisablesWhenDebuggingIsOn() {
        assertEquals(
            GateAction.DISABLE,
            gateAction(onWifi = true, ssid = "Cafe", allowed = setOf("Home"), debuggingOn = true),
        )
    }

    @Test
    fun aNetworkOffTheListIsANoOpWhenDebuggingIsAlreadyOff() {
        assertEquals(
            GateAction.NONE,
            gateAction(onWifi = true, ssid = "Cafe", allowed = setOf("Home"), debuggingOn = false),
        )
    }

    @Test
    fun anEmptyAllowListDisablesAReadableNetwork() {
        assertEquals(
            GateAction.DISABLE,
            gateAction(onWifi = true, ssid = "Home", allowed = emptySet(), debuggingOn = true),
        )
    }

    @Test
    fun quotedSsidIsUnquoted() {
        assertEquals("Home", normalizeSsid("\"Home\""))
    }

    @Test
    fun unquotedSsidIsKept() {
        assertEquals("Home", normalizeSsid("Home"))
    }

    @Test
    fun unknownSsidIsNull() {
        assertNull(normalizeSsid("<unknown ssid>"))
        assertNull(normalizeSsid("\"<unknown ssid>\""))
    }

    @Test
    fun nullAndBlankAreNull() {
        assertNull(normalizeSsid(null))
        assertNull(normalizeSsid(""))
        assertNull(normalizeSsid("   "))
        assertNull(normalizeSsid("\"\""))
    }
}
