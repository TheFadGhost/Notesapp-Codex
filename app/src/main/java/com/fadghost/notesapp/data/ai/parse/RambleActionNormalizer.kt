package com.fadghost.notesapp.data.ai.parse

/**
 * Deterministic backstop for the model prompt. The app has no Todo table: a task carrying a
 * resolved date must therefore be a reminder, while a truly dateless Todo remains in the
 * organised note/checklist and should not masquerade as an insertable calendar card.
 */
object RambleActionNormalizer {
    fun normalize(action: ProposedAction): ProposedAction =
        if (action.type == ActionType.TODO && action.datetimeMillis != null) {
            action.copy(type = ActionType.REMINDER)
        } else action

    fun normalize(actions: List<ProposedAction>): List<ProposedAction> = actions.map(::normalize)

    data class Actionable(
        val items: List<ProposedAction>,
        val datelessTodoCount: Int
    )

    /** Items that can actually be accepted into the current Event/Reminder schema. */
    fun actionable(actions: List<ProposedAction>): Actionable {
        val normalized = normalize(actions)
        return Actionable(
            items = normalized.filter { it.type != ActionType.TODO },
            datelessTodoCount = normalized.count { it.type == ActionType.TODO }
        )
    }
}
