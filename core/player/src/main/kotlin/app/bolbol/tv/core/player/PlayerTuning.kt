package app.bolbol.tv.core.player

/**
 * Every playback number in one place. Change these only with a measurement on the TV
 * (docs/perf-budget.md) and note the reason in docs/decisions.md §Player.
 *
 * Context: progressive H.264/AAC MP4 over HTTP range requests from a CDN, played on a
 * 2 GB Mi TV P1 (MT9611). The 1080p files run at ~3-5 Mbit/s, 720p ~1.3 Mbit/s.
 */
object PlayerTuning {

    // ---- DefaultLoadControl -------------------------------------------------------------

    /**
     * Data needed before the *first* frame plays. Small = fast start (TTFF budget is 2 s at 720p).
     * 1.5 s of a 720p file is ~250 KB, one or two TCP round-trips on home Wi-Fi.
     */
    const val BUFFER_FOR_PLAYBACK_MS = 1_500

    /**
     * Data needed to resume after a rebuffer. Larger than the start value so a flaky link
     * doesn't stutter in and out of buffering every second.
     */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    /** Below this much buffered media the player always keeps loading. */
    const val MIN_BUFFER_MS = 15_000

    /**
     * The player stops loading once this much is buffered ahead. 40 s rides out most Wi-Fi
     * dips while keeping memory low (40 s of 1080p at ~4 Mbit/s is ~20 MB).
     */
    const val MAX_BUFFER_MS = 40_000

    /**
     * Hard cap on buffer memory (perf budget: buffer <= 60 MB, playback PSS <= 250 MB).
     * Only matters for unusually high-bitrate files; time thresholds normally stop first.
     */
    const val TARGET_BUFFER_BYTES = 60 * 1024 * 1024

    /**
     * false = the byte cap wins over [MIN_BUFFER_MS]: on a 2 GB TV memory pressure is worse
     * than a rare rebuffer on an extreme-bitrate file.
     */
    const val PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS = false

    /**
     * Media kept *behind* the playhead so the common "oops, back 10 s" press is served from
     * memory instead of a new range request. Counts towards [TARGET_BUFFER_BYTES].
     */
    const val BACK_BUFFER_MS = 10_000

    // ---- Seeking -------------------------------------------------------------------------

    /** One D-pad press (and ExoPlayer seekBack/seekForward increments). */
    const val SEEK_STEP_MS = 10_000L

    /** MEDIA_FAST_FORWARD / MEDIA_REWIND. */
    const val FAST_SEEK_STEP_MS = 30_000L

    /** Step while a D-pad arrow has been held for [SEEK_MEDIUM_AFTER_MS]. */
    const val SEEK_STEP_MEDIUM_MS = 30_000L

    /** Step while a D-pad arrow has been held for [SEEK_LARGE_AFTER_MS]. */
    const val SEEK_STEP_LARGE_MS = 60_000L

    const val SEEK_MEDIUM_AFTER_MS = 1_000L
    const val SEEK_LARGE_AFTER_MS = 3_000L

    /**
     * Key auto-repeat fires ~20x/s; applying a step on every repeat would fly past the target.
     * Repeats closer together than this are swallowed.
     */
    const val SEEK_REPEAT_MIN_INTERVAL_MS = 120L

    // ---- Progress & resume ---------------------------------------------------------------

    /** Periodic progress save while playing (plus forced saves on pause/stop/error/release). */
    const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

    /** Positions below this are never saved (a peek at the opening isn't "watching"). */
    const val MIN_SAVE_POSITION_MS = 5_000L

    /** Past this fraction of the duration an item counts as watched. */
    const val COMPLETED_FRACTION = 0.95

    /** Only offer "continue from …" when the saved position is at least this far in … */
    const val RESUME_MIN_POSITION_MS = 30_000L

    /** … and at least this much is left to watch. */
    const val RESUME_MIN_REMAINING_MS = 60_000L

    // ---- Media network (v1.1-2, perf/reports/v1.1-quality-switch.md) ---------------------

    /**
     * TCP connect timeout of the media client (the shared client uses 10 s). The CDNs are ~100 ms
     * away; 6 s still covers two SYN retransmits on bad Wi-Fi, but a dead route fails fast.
     */
    const val MEDIA_CONNECT_TIMEOUT_MS = 6_000L

    /**
     * Read timeout of the media client (the shared client uses 20 s). 10 s without a single byte
     * is a stalled connection, not a slow one: drop it and retry on a fresh one. Has no effect
     * while ExoPlayer has stopped loading (full buffer): no read is pending then.
     */
    const val MEDIA_READ_TIMEOUT_MS = 10_000L

    // ---- Quality/audio switch (v1.1-2) ----------------------------------------------------

    /**
     * No first frame this long after a switch, and no bytes arriving: re-prepare once on a fresh
     * connection, then show the error panel (which offers «کیفیت پایین‌تر»). See [StreamSwitchPolicy].
     */
    const val SWITCH_FIRST_FRAME_TIMEOUT_MS = 8_000L

    /** Bytes for the new stream arrived within this window = the switch is progressing, wait. */
    const val SWITCH_STALL_MS = 2_000L

    /** Give up (error panel) this long after the switch even if bytes trickle in. */
    const val SWITCH_HARD_LIMIT_MS = 20_000L

    /** Watchdog re-prepares per switch before giving up. */
    const val SWITCH_MAX_REPREPARES = 1

    /**
     * A switch starts at the keyframe before the old position (SeekParameters.PREVIOUS_SYNC for
     * that start only), so nothing has to be decoded and thrown away before the first frame. The
     * site's x264 files have a keyframe every 1-10 s (mean ~3 s); never jump back more than this.
     */
    const val SWITCH_MAX_SNAP_BACK_MS = 15_000L

    /**
     * Connection pre-warm (DNS + TCP + TLS) for the other qualities' hosts: at most one tiny
     * request per host per this interval. The CDNs' DNS TTL is 75-120 s and nginx drops idle
     * keep-alive connections after ~1 min, so a warm-up older than this is mostly gone anyway.
     */
    const val PREWARM_MIN_INTERVAL_MS = 60_000L

    // ---- Diagnostics ---------------------------------------------------------------------

    /** Position/buffer/diagnostics refresh while playing or buffering (1 Hz is plenty). */
    const val TICK_INTERVAL_MS = 1_000L
}
