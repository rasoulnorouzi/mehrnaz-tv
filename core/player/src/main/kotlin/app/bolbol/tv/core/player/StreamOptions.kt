package app.bolbol.tv.core.player

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.StreamOption

// Pure helpers over a title's streams for the picker and the error fallback (unit-tested).

/** Audio types present, subtitled first (the site's own order). */
fun List<StreamOption>.audioTypes(): List<AudioType> =
    map { it.audio }.distinct().sortedBy { it.ordinal }

/** Qualities available for [audio], highest first. */
fun List<StreamOption>.qualitiesFor(audio: AudioType): List<Int> =
    filter { it.audio == audio }.map { it.quality }.distinct().sortedDescending()

/** Exact match, or null. */
fun List<StreamOption>.find(audio: AudioType, quality: Int): StreamOption? =
    firstOrNull { it.audio == audio && it.quality == quality }

/**
 * Stream for a new audio choice, keeping the current quality when possible, else the closest
 * lower one, else the lowest available for that audio.
 */
fun List<StreamOption>.forAudio(audio: AudioType, preferredQuality: Int): StreamOption? {
    val candidates = filter { it.audio == audio }
    return candidates.filter { it.quality <= preferredQuality }.maxByOrNull { it.quality }
        ?: candidates.minByOrNull { it.quality }
}

/**
 * Error fallback ("lower quality"): the next lower quality with the same audio, else the next lower quality in
 * any audio, else null (already the lowest).
 */
fun List<StreamOption>.nextLowerQuality(current: StreamOption): StreamOption? =
    filter { it.audio == current.audio && it.quality < current.quality }.maxByOrNull { it.quality }
        ?: filter { it.quality < current.quality }.maxByOrNull { it.quality }
