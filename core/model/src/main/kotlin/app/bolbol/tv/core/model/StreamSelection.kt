package app.bolbol.tv.core.model

/**
 * Picks the stream to play: preferred audio first (falling back to whatever exists),
 * then the preferred quality or the closest lower one, else the lowest available.
 */
fun List<StreamOption>.pick(prefs: PlaybackPrefs?, defaultQuality: Int = 720): StreamOption? {
    if (isEmpty()) return null
    val audio = prefs?.audio
    val byAudio = if (audio != null && any { it.audio == audio }) filter { it.audio == audio } else this
    val target = prefs?.quality ?: defaultQuality
    return byAudio.filter { it.quality <= target }.maxByOrNull { it.quality }
        ?: byAudio.minByOrNull { it.quality }
}
