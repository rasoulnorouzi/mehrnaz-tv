package app.bolbol.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun sixChoicesSlowestFirstIncludingNormal() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), PlaybackSpeeds.CHOICES)
        assertTrue(PlaybackSpeeds.CHOICES.contains(PlaybackSpeeds.NORMAL))
    }

    @Test fun normalizeKeepsChoicesAndSnapsOthers() {
        for (c in PlaybackSpeeds.CHOICES) assertEquals(c, PlaybackSpeeds.normalize(c), 0f)
        assertEquals(1.5f, PlaybackSpeeds.normalize(1.4f), 0f)
        assertEquals(2f, PlaybackSpeeds.normalize(5f), 0f)
        assertEquals(0.5f, PlaybackSpeeds.normalize(0.1f), 0f)
    }

    @Test fun normalizeFallsBackToNormalForGarbage() {
        assertEquals(1f, PlaybackSpeeds.normalize(Float.NaN), 0f)
        assertEquals(1f, PlaybackSpeeds.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, PlaybackSpeeds.normalize(0f), 0f)
        assertEquals(1f, PlaybackSpeeds.normalize(-2f), 0f)
    }

    @Test fun isNormalOnlyForOne() {
        assertTrue(PlaybackSpeeds.isNormal(1f))
        assertFalse(PlaybackSpeeds.isNormal(1.25f))
        assertFalse(PlaybackSpeeds.isNormal(0.75f))
    }

    @Test fun memoryStartsNormalAndKeepsTheChoice() {
        val memory = PlaybackSpeedMemory()
        assertEquals(1f, memory.speed.value, 0f)
        assertEquals(1.5f, memory.set(1.5f), 0f)
        // "next episode": the next controller reads the same holder and gets the same speed
        assertEquals(1.5f, memory.speed.value, 0f)
        assertEquals(1f, memory.set(1f), 0f)
    }

    @Test fun memorySnapsInvalidValues() {
        val memory = PlaybackSpeedMemory()
        assertEquals(2f, memory.set(3f), 0f)
        assertEquals(1f, memory.set(Float.NaN), 0f)
        assertEquals(1f, PlaybackSpeedMemory(initial = -1f).speed.value, 0f)
    }

    @Test fun aNewHolderIsNormalLikeAProcessRestart() {
        PlaybackSpeedMemory().set(2f)
        assertEquals(1f, PlaybackSpeedMemory().speed.value, 0f)
    }

    @Test fun diagnosticsShowSpeedOnlyWhenNotNormal() {
        assertTrue(PlaybackDiagnostics().lines().none { it.startsWith("speed") })
        assertTrue(PlaybackDiagnostics(speed = 1.5f).lines().contains("speed 1.5x"))
    }
}
