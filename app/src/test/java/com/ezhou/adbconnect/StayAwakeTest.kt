package com.ezhou.adbconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class StayAwakeTest {
    private val deadline = 1_000L

    @Test
    fun beforeTheDeadlineTheWindowIsRearmed() {
        assertEquals(ExpiryAction.REARM, expiryAction(current = 15, written = 15, nowMs = 999, deadlineMs = deadline))
    }

    @Test
    fun atTheDeadlineAnUntouchedSettingIsCleared() {
        assertEquals(ExpiryAction.CLEAR_SETTING, expiryAction(current = 15, written = 15, nowMs = 1_000, deadlineMs = deadline))
    }

    @Test
    fun aSettingChangedMidWindowIsLeftAlone() {
        assertEquals(ExpiryAction.LEAVE_SETTING, expiryAction(current = 2, written = 15, nowMs = 2_000, deadlineMs = deadline))
    }

    @Test
    fun aSettingTurnedOffMidWindowIsLeftAlone() {
        assertEquals(ExpiryAction.LEAVE_SETTING, expiryAction(current = 0, written = 15, nowMs = 2_000, deadlineMs = deadline))
    }

    @Test
    fun disablingTheFeatureClearsRegardlessOfDeadline() {
        assertEquals(ExpiryAction.CLEAR_SETTING, expiryAction(current = 15, written = 15, nowMs = Long.MAX_VALUE, deadlineMs = deadline))
    }
}
