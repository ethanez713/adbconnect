package com.ezhou.adbconnect

import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetricsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun storeAt(file: File, today: LocalDate) = MetricsStore(file, clock = { today })

    @Test
    fun recordedEventCountsTowardTotalAndLast30() {
        val file = File(tempFolder.root, "metrics.json")
        val today = LocalDate.of(2026, 8, 21)
        val store = storeAt(file, today)

        store.record(MetricEvent.ENABLE_REMOTE)

        val counts = store.snapshot().counts.getValue(MetricEvent.ENABLE_REMOTE)
        assertEquals(1L, counts.total)
        assertEquals(1L, counts.last30)
    }

    @Test
    fun last30WindowIsExactlyTodayPlusPreceding29Days() {
        val file = File(tempFolder.root, "metrics.json")
        val today = LocalDate.of(2026, 8, 21)

        storeAt(file, today.minusDays(29)).record(MetricEvent.STATUS_REMOTE)
        storeAt(file, today.minusDays(30)).record(MetricEvent.STATUS_REMOTE)

        val counts = storeAt(file, today).snapshot().counts.getValue(MetricEvent.STATUS_REMOTE)
        assertEquals(2L, counts.total)
        assertEquals(1L, counts.last30)
    }

    @Test
    fun pruningDropsBucketsOlderThan35DaysButKeepsTotal() {
        val file = File(tempFolder.root, "metrics.json")
        val day0 = LocalDate.of(2026, 1, 1)
        storeAt(file, day0).record(MetricEvent.AUTH_REJECTED)

        val day40 = day0.plusDays(40)
        storeAt(file, day40).record(MetricEvent.AUTH_REJECTED)

        val persisted = JSONObject(file.readText())
        val days = persisted.getJSONObject("counts").getJSONObject(MetricEvent.AUTH_REJECTED.id).getJSONObject("days")
        assertFalse(days.has(day0.toEpochDay().toString()))
        assertTrue(days.has(day40.toEpochDay().toString()))

        val total = storeAt(file, day40).snapshot().counts.getValue(MetricEvent.AUTH_REJECTED).total
        assertEquals(2L, total)
    }

    @Test
    fun sinceSurvivesReloadAndIsNotOverwritten() {
        val file = File(tempFolder.root, "metrics.json")
        val firstDay = LocalDate.of(2026, 3, 1)
        storeAt(file, firstDay).record(MetricEvent.SERVICE_START)

        val laterDay = firstDay.plusDays(10)
        storeAt(file, laterDay).record(MetricEvent.SERVICE_START)

        assertEquals(firstDay, storeAt(file, laterDay).snapshot().since)
    }

    @Test
    fun corruptFileYieldsEmptyStoreInsteadOfThrowing() {
        val file = File(tempFolder.root, "metrics.json")
        file.writeText("{ not valid json at all")

        val snapshot = storeAt(file, LocalDate.of(2026, 8, 21)).snapshot()

        MetricEvent.entries.forEach {
            val counts = snapshot.counts.getValue(it)
            assertEquals(0L, counts.total)
            assertEquals(0L, counts.last30)
        }
    }

    @Test
    fun freshSnapshotIncludesEveryEventAtZero() {
        val file = File(tempFolder.root, "metrics.json")
        val snapshot = storeAt(file, LocalDate.of(2026, 8, 21)).snapshot()

        assertEquals(MetricEvent.entries.toSet(), snapshot.counts.keys)
        snapshot.counts.values.forEach {
            assertEquals(0L, it.total)
            assertEquals(0L, it.last30)
        }
    }
}
