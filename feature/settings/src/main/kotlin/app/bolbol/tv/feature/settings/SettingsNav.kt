package app.bolbol.tv.feature.settings

/** Sections of the settings list, in display order (first = top of the list). */
enum class SettingsSection { SUBTITLES, PLAYBACK, CONNECTION, UPDATE, DEVELOPER, ABOUT }

/** Persian label shown in the section list. */
internal val SettingsSection.label: String
    get() = when (this) {
        SettingsSection.SUBTITLES -> "زیرنویس"
        SettingsSection.PLAYBACK -> "پخش"
        SettingsSection.CONNECTION -> "اتصال"
        SettingsSection.UPDATE -> "به‌روزرسانی"
        SettingsSection.DEVELOPER -> "توسعه‌دهنده"
        SettingsSection.ABOUT -> "درباره"
    }

/** False for sections that only show text, so there is nothing to move focus to. */
internal val SettingsSection.hasControls: Boolean
    get() = this != SettingsSection.ABOUT

/** Where the D-pad focus currently is. */
internal enum class SettingsZone { LIST, CONTENT }

/**
 * Pure navigation state of the two-pane screen. The list selection follows focus (moving up/down in
 * the list switches the content at once); the end direction (D-pad left in RTL) enters the content;
 * Back from the content returns to the list, Back from the list is left to the host (leave the screen).
 * The Compose layer feeds focus changes in and performs the actual focus moves.
 */
internal data class SettingsNavState(
    val section: SettingsSection = SettingsSection.SUBTITLES,
    val zone: SettingsZone = SettingsZone.LIST,
) {
    /** A list item took focus: it becomes the selected section. */
    fun listFocused(section: SettingsSection): SettingsNavState = SettingsNavState(section, SettingsZone.LIST)

    /** Move into the content pane; stays in the list when the section has no controls. */
    fun enterContent(): SettingsNavState =
        if (section.hasControls) copy(zone = SettingsZone.CONTENT) else this

    /** Focus landed inside the content pane (also by geometric D-pad moves). */
    fun contentFocused(): SettingsNavState = enterContent()

    /** Focus left the content pane (to the list, or out of the screen). */
    fun contentBlurred(): SettingsNavState =
        if (zone == SettingsZone.CONTENT) copy(zone = SettingsZone.LIST) else this

    /** Back pressed. Returns the new state, or null when not handled here (the host leaves the screen). */
    fun back(): SettingsNavState? =
        if (zone == SettingsZone.CONTENT) copy(zone = SettingsZone.LIST) else null
}
