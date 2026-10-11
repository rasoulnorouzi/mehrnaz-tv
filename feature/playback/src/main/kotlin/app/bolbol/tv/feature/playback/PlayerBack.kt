package app.bolbol.tv.feature.playback

/** What one BACK press does in the player. */
internal enum class BackAction {
    /**
     * A side panel («تنظیمات پخش» or «قسمت‌ها») is open: close it; the controls come back with focus on the
     * button that opened it ([PlayerPanel.opener]).
     */
    ClosePanel,

    /** The controls are showing over the video: hide them. */
    HideOverlay,

    /** The «قسمت بعد» card is counting down: dismiss it. */
    DismissNextCard,

    /** Leave the player (progress is saved by the ViewModel). */
    Exit,
}

/**
 * The BACK order (docs/design-system.md §Player): **close the panel → hide the controls → (dismiss the
 * next-episode card) → exit**, one level per press. The resume dialog and the error panel are full-screen
 * stops, so BACK leaves straight away from them.
 *
 * Pass visibility as the screen actually draws it: [panelOpen], [overlayVisible] and [nextCardVisible] are
 * only true while the video is playing without an error. Pure, so the order is unit-tested.
 */
internal fun backAction(
    panelOpen: Boolean,
    overlayVisible: Boolean,
    resumeDialog: Boolean,
    errorShown: Boolean,
    nextCardVisible: Boolean = false,
): BackAction = when {
    resumeDialog || errorShown -> BackAction.Exit
    panelOpen -> BackAction.ClosePanel
    overlayVisible -> BackAction.HideOverlay
    nextCardVisible -> BackAction.DismissNextCard
    else -> BackAction.Exit
}

/**
 * Turns the remote's BACK key-down / key-up pair into exactly one action, on the **key-up** (the same
 * moment Android's own back handling fires, and what ContinueMenu does). Key-downs, including the
 * auto-repeats of a held key, are only swallowed. A key-up whose key-down this tracker never saw is not
 * ours: it belongs to a press that went through the system path, so the caller lets it through.
 */
internal class BackPressTracker {
    private var armed = false

    fun onKeyDown() {
        armed = true
    }

    /** True when this release completes a press whose key-down was seen: perform the action now. */
    fun onKeyUp(): Boolean {
        val completes = armed
        armed = false
        return completes
    }
}
