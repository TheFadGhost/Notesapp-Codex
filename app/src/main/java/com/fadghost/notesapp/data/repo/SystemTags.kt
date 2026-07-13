package com.fadghost.notesapp.data.repo

/**
 * Tags created by product flows. They are ordinary user-editable tags: a user can remove one
 * from any note or delete it entirely, and the next matching flow will recreate it when needed.
 */
object SystemTags {
    const val RAMBLER = "Rambler"
    const val RAMBLER_COLOR: Int = 0xFFB96043.toInt()
}
