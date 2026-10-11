package app.bolbol.tv.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    private class FakeStore(val map: MutableMap<String, Boolean> = mutableMapOf()) : KeyValueStore {
        var writes = 0
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            writes++
            map[key] = value
        }
    }

    @Test
    fun performanceOverlayIsOffByDefault() {
        assertFalse(AppSettings(FakeStore()).showPerformance.value)
    }

    @Test
    fun settingUpdatesTheFlowAndTheStore() {
        val store = FakeStore()
        val settings = AppSettings(store)
        settings.setShowPerformance(true)
        assertTrue(settings.showPerformance.value)
        assertEquals(true, store.map[AppSettings.KEY_SHOW_PERFORMANCE])
        settings.setShowPerformance(false)
        assertFalse(settings.showPerformance.value)
        assertEquals(false, store.map[AppSettings.KEY_SHOW_PERFORMANCE])
    }

    @Test
    fun valueSurvivesANewInstanceOverTheSameStore() {
        val store = FakeStore()
        AppSettings(store).setShowPerformance(true)
        assertTrue(AppSettings(store).showPerformance.value)
    }

    @Test
    fun unchangedValueIsNotWritten() {
        val store = FakeStore()
        val settings = AppSettings(store)
        settings.setShowPerformance(false)
        assertEquals(0, store.writes)
        settings.setShowPerformance(true)
        settings.setShowPerformance(true)
        assertEquals(1, store.writes)
    }
}
