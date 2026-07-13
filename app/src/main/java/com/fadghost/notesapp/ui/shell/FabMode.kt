package com.fadghost.notesapp.ui.shell

/**
 * Contextual FAB behaviour per tab (V2-SPEC item 4, ux.md §2). The FAB's *action*
 * changes with the active tab; on Settings there is nothing to create so it hides.
 * Pure mapping so the shell wiring stays trivial and unit-testable.
 */
enum class FabMode {
    /** Notes: open the FAB-anchored capture panel (note / diary / voice / reminder). */
    CAPTURE_PANEL,

    /** Diary: jump to today's entry and raise the keyboard. */
    DIARY_TODAY,

    /** Calendar: start a new event on the selected day. */
    CALENDAR_NEW,

    /** Ask and Settings: no creation FAB. */
    HIDDEN;

    val visible: Boolean get() = this != HIDDEN
}

fun fabModeFor(tab: NavTab): FabMode = when (tab) {
    NavTab.NOTES -> FabMode.CAPTURE_PANEL
    NavTab.DIARY -> FabMode.DIARY_TODAY
    NavTab.CALENDAR -> FabMode.CALENDAR_NEW
    NavTab.ASK -> FabMode.HIDDEN
    NavTab.SETTINGS -> FabMode.HIDDEN
}
