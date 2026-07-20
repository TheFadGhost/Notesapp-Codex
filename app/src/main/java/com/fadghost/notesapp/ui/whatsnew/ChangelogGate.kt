package com.fadghost.notesapp.ui.whatsnew

/**
 * Version-gate for the post-update "What's new" sheet (PLAN.md §13). Pure logic so it
 * is unit-testable: show the sheet exactly once per versionName change.
 */
object ChangelogGate {
    /**
     * Show when the current versionName is non-blank and differs from the last version
     * the sheet was shown for. A blank [lastSeen] means fresh install: a newcomer must
     * NOT be greeted by a changelog for features they've never seen (council finding) —
     * the Welcome sheet owns first run; the changelog starts with the first update.
     */
    fun shouldShow(lastSeen: String, current: String): Boolean =
        current.isNotBlank() && lastSeen.isNotBlank() && lastSeen != current
}
