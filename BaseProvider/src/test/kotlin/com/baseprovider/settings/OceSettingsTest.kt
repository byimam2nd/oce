package com.baseprovider.settings

import com.baseprovider.config.ProviderConfig
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OceSettingsTest {

    private val base = ProviderConfig(
        id = "test",
        mainUrl = "https://test.com",
        supportedTypes = setOf(TvType.Movie)
    )

    @Test
    fun `filterMainPageLists returns all when enabled is null`() {
        val lists = listOf("/a" to "A", "/b" to "B")
        assertEquals(lists, OceSettings.filterMainPageLists(lists, null))
    }

    @Test
    fun `filterMainPageLists filters by category name`() {
        val lists = listOf("/a" to "A", "/b" to "B", "/c" to "C")
        val filtered = OceSettings.filterMainPageLists(lists, setOf("A", "C"))
        assertEquals(listOf("/a" to "A", "/c" to "C"), filtered)
    }

    @Test
    fun `filterMainPageLists returns empty when enabled set is empty`() {
        val lists = listOf("/a" to "A", "/b" to "B")
        assertTrue(OceSettings.filterMainPageLists(lists, emptySet()).isEmpty())
    }

    @Test
    fun `enabledCategories is null without attached context`() {
        assertNull(OceSettings.enabledCategories("test"))
    }

    @Test
    fun `accessors fall back to defaults without context`() {
        assertEquals(30L, OceSettings.cacheTtlMinutes("test", 30L))
    }

    @Test
    fun `customCategories is empty without context`() {
        assertTrue(OceSettings.customCategories("test").isEmpty())
    }

    @Test
    fun `applyOverrides returns base unchanged without context`() {
        assertEquals(base, OceSettings.applyOverrides("test", base))
    }
}