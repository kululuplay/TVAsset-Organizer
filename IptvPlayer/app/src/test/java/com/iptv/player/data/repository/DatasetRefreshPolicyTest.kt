package com.iptv.player.data.repository

import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetRefreshPolicyTest {
    @Test
    fun `empty response preserves a populated cache`() {
        val decision = DatasetRefreshPolicy.evaluate(
            CatalogDataset.VOD_CATEGORY,
            DatasetSnapshot(existingCount = 80, receivedCount = 0, acceptedCount = 0),
        )
        assertTrue(decision is DatasetRefreshDecision.PreserveCache)
    }

    @Test
    fun `catastrophic shrink preserves a populated cache`() {
        val decision = DatasetRefreshPolicy.evaluate(
            CatalogDataset.LIVE,
            DatasetSnapshot(existingCount = 100, receivedCount = 10, acceptedCount = 10),
        )
        assertTrue(decision is DatasetRefreshDecision.PreserveCache)
    }

    @Test
    fun `a catalog sharing no ids with the cache replaces it despite the shrink`() {
        // Panel migration: 150 old category ids cached, 28 new ones served.
        val migrated = DatasetSnapshot(existingCount = 150, receivedCount = 28, acceptedCount = 28, overlapCount = 0)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.VOD_CATEGORIES, migrated) is DatasetRefreshDecision.Apply)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.LIVE, migrated) is DatasetRefreshDecision.Apply)
        // A truncated reply of the SAME catalog still preserves the cache.
        val truncated = DatasetSnapshot(existingCount = 150, receivedCount = 28, acceptedCount = 28, overlapCount = 28)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.VOD_CATEGORIES, truncated) is DatasetRefreshDecision.PreserveCache)
        // Unknown overlap keeps the old behaviour; an empty reply is never a new catalog.
        val unknown = DatasetSnapshot(existingCount = 150, receivedCount = 28, acceptedCount = 28)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.VOD_CATEGORIES, unknown) is DatasetRefreshDecision.PreserveCache)
        val empty = DatasetSnapshot(existingCount = 150, receivedCount = 0, acceptedCount = 0, overlapCount = 0)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.VOD_CATEGORIES, empty) is DatasetRefreshDecision.PreserveCache)
    }

    @Test
    fun `forced refresh bypasses only the shrink guard`() {
        val shrink = DatasetSnapshot(existingCount = 100, receivedCount = 10, acceptedCount = 10)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.LIVE, shrink, force = true) is DatasetRefreshDecision.Apply)
        val empty = DatasetSnapshot(existingCount = 100, receivedCount = 0, acceptedCount = 0)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.LIVE, empty, force = true) is DatasetRefreshDecision.PreserveCache)
    }

    @Test
    fun `repeated or day old shrink rejections are eventually accepted`() {
        val shrink = DatasetSnapshot(existingCount = 100, receivedCount = 10, acceptedCount = 10)
        val now = 1_000_000L
        val twice = ShrinkRejection(count = 2, firstRejectedAt = now - 60_000L)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.LIVE, shrink, twice, now) is DatasetRefreshDecision.PreserveCache)
        val thrice = ShrinkRejection(count = 3, firstRejectedAt = now - 60_000L)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.LIVE, shrink, thrice, now) is DatasetRefreshDecision.Apply)
        val dayOld = ShrinkRejection(count = 1, firstRejectedAt = now - DatasetRefreshPolicy.SHRINK_OVERRIDE_AFTER_MS)
        assertTrue(DatasetRefreshPolicy.evaluate(CatalogDataset.LIVE, shrink, dayOld, now) is DatasetRefreshDecision.Apply)
    }

    @Test
    fun `shrink ledger persists consecutive rejections and clears on apply`() {
        val prefs = com.iptv.player.util.FakeSharedPreferences()
        val ledger = ShrinkGuardLedger(prefs)
        assertNull(ledger.get("live"))
        assertEquals(ShrinkRejection(1, 500L), ledger.recordRejection("live", 500L))
        assertEquals(ShrinkRejection(2, 500L), ledger.recordRejection("live", 900L))
        // A fresh ledger over the same preferences (process restart) still knows.
        assertEquals(ShrinkRejection(2, 500L), ShrinkGuardLedger(prefs).get("live"))
        assertNull(ShrinkGuardLedger(prefs).get("epg"))
        ledger.clear("live")
        assertNull(ShrinkGuardLedger(prefs).get("live"))
        // Without preferences the ledger still works within the process.
        val memory = ShrinkGuardLedger()
        memory.recordRejection("live", 1L)
        assertEquals(1, memory.get("live")?.count)
    }

    @Test
    fun `ordinary catalog change is applied`() {
        val decision = DatasetRefreshPolicy.evaluate(
            CatalogDataset.SERIES_CATEGORY,
            DatasetSnapshot(existingCount = 100, receivedCount = 82, acceptedCount = 80),
        )
        assertTrue(decision is DatasetRefreshDecision.Apply)
    }

    @Test
    fun `malformed row majority preserves cache`() {
        val decision = DatasetRefreshPolicy.evaluate(
            CatalogDataset.VOD_CATEGORIES,
            DatasetSnapshot(existingCount = 20, receivedCount = 20, acceptedCount = 4),
        )
        assertTrue(decision is DatasetRefreshDecision.PreserveCache)
    }

    @Test
    fun `new empty optional dataset is accepted without deletion`() {
        val decision = DatasetRefreshPolicy.evaluate(
            CatalogDataset.EPG,
            DatasetSnapshot(existingCount = 0, receivedCount = 0, acceptedCount = 0),
        )
        assertTrue(decision is DatasetRefreshDecision.Apply)
    }

    @Test
    fun `only latest overlapping generation may commit`() {
        val gate = DatasetGenerationGate()
        val old = gate.begin("vod:12")
        val latest = gate.begin("vod:12")
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(latest))
        assertTrue(gate.isCurrent(gate.begin("series:12")))
    }

    @Test
    fun `generation identifies an in-process source switch per dataset`() {
        val gate = DatasetGenerationGate()
        val first = SourceConfig(SourceType.XTREAM, "https://one.example", "user", "password")
        val second = first.copy(serverUrl = "https://two.example")
        assertFalse(gate.begin("live", first).sourceChanged)
        assertFalse(gate.begin("epg", second).sourceChanged)
        assertTrue(gate.begin("live", second).sourceChanged)
    }

    @Test
    fun `source identity keeps scheme host port path and credentials`() {
        val saved = SourceConfig(
            type = SourceType.XTREAM,
            serverUrl = "https://panel.example:8443/root/",
            username = "u",
            password = "p",
        )
        assertTrue(SourceIdentity.matches(saved, saved.copy(serverUrl = "https://panel.example:8443/root")))
        assertFalse(SourceIdentity.matches(saved, saved.copy(serverUrl = "https://panel.example/root")))
        assertFalse(SourceIdentity.matches(saved, saved.copy(password = "other")))
    }

    @Test
    fun `full sync is successful only when every dataset refreshed`() {
        val healthy = DatasetSyncResult("live", DatasetSyncStatus.UPDATED, itemCount = 10)
        val cached = DatasetSyncResult("epg", DatasetSyncStatus.PRESERVED_CACHE, itemCount = 20)

        assertTrue(SyncReport(listOf(healthy)).allRefreshSucceeded)
        assertFalse(SyncReport(listOf(healthy, cached)).allRefreshSucceeded)
    }
}
