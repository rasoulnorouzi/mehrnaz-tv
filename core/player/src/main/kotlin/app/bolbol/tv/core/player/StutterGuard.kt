package app.bolbol.tv.core.player

/**
 * Self-healing for choppy video (v1.5.1, user report 2026-10-29): after the TV wakes from standby, or when a film is
 * resumed, the MT9611 decoder sometimes stays "behind" and keeps dropping frames, so the film plays like a slideshow.
 * Switching to another quality and back fixed it (a fresh source from a keyframe), but films with one quality had no
 * way out. This pure rule (unit-tested) watches the dropped-frame counter once a second while playing and asks the
 * controller to reload the same stream at the same spot when playback is clearly stuttering.
 *
 * Stuttering = more than [dropRatio] of the expected frames dropped in each of [windowsNeeded] consecutive windows of
 * [windowMs] of real playback. Reloads are at least [cooldownMs] apart and at most [maxReloads] per stream, so a file
 * that is simply too heavy for the TV never loops.
 */
class StutterGuard(
    private val windowMs: Long = 4_000,
    private val dropRatio: Float = 0.3f,
    private val windowsNeeded: Int = 2,
    private val cooldownMs: Long = 60_000,
    private val maxReloads: Int = 3,
) {
    private var windowStartMs = -1L
    private var windowStartDropped = 0L
    private var badWindows = 0
    private var lastReloadMs = Long.MIN_VALUE / 2
    private var reloads = 0

    /** A new stream (or episode) started: forget everything. */
    fun reset() {
        windowStartMs = -1L
        badWindows = 0
        reloads = 0
        lastReloadMs = Long.MIN_VALUE / 2
    }

    /** Playback paused, buffering, seeking or reloaded: start a fresh window (keeps the reload budget). */
    fun restartWindow() {
        windowStartMs = -1L
        badWindows = 0
    }

    /**
     * Called about once a second. [droppedTotal] is the running dropped-frame count, [frameRate] the video's frame rate
     * (24-30 for these files; ≤ 0 when unknown). Returns true when the stream should be reloaded now.
     */
    fun onTick(nowMs: Long, isPlaying: Boolean, droppedTotal: Long, frameRate: Float): Boolean {
        if (!isPlaying) {
            restartWindow()
            return false
        }
        if (windowStartMs < 0) {
            windowStartMs = nowMs
            windowStartDropped = droppedTotal
            return false
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return false
        val fps = if (frameRate > 0f) frameRate else DEFAULT_FPS
        val expected = fps * elapsed / 1000f
        val dropped = (droppedTotal - windowStartDropped).coerceAtLeast(0)
        badWindows = if (dropped > expected * dropRatio) badWindows + 1 else 0
        windowStartMs = nowMs
        windowStartDropped = droppedTotal
        if (badWindows < windowsNeeded) return false
        badWindows = 0
        if (reloads >= maxReloads || nowMs - lastReloadMs < cooldownMs) return false
        reloads++
        lastReloadMs = nowMs
        windowStartMs = -1L
        return true
    }

    private companion object {
        const val DEFAULT_FPS = 25f
    }
}
