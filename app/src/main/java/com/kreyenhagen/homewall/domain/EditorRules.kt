package com.kreyenhagen.homewall.domain

/** Route Editor tap rules (§8.3). Holds are a map of holdId → role. */
object EditorRules {
    const val MAX_START = 2
    const val MAX_FINISH = 2

    sealed interface TapResult {
        data class Changed(val holds: Map<Int, HoldRole>) : TapResult

        /** The tap would go over the limit for [role]; nothing changes. */
        data class LimitReached(val role: HoldRole) : TapResult
    }

    /** Max holds of [role] in a route, or null for unlimited. */
    fun limitFor(role: HoldRole): Int? = when (role) {
        HoldRole.START -> MAX_START
        HoldRole.FINISH -> MAX_FINISH
        HoldRole.MIDDLE -> null
    }

    /**
     * Tapping an active hold always removes it, whatever role is selected. Tapping an inactive hold
     * adds it with [selected], unless that would exceed the role's limit.
     */
    fun tap(holds: Map<Int, HoldRole>, holdId: Int, selected: HoldRole): TapResult {
        if (holdId in holds) return TapResult.Changed(holds - holdId)
        val limit = limitFor(selected)
        if (limit != null && holds.values.count { it == selected } >= limit) return TapResult.LimitReached(selected)
        return TapResult.Changed(holds + (holdId to selected))
    }

    fun toRouteHolds(holds: Map<Int, HoldRole>): List<RouteHold> =
        holds.entries.sortedBy { it.key }.map { RouteHold(it.key, it.value) }
}

/**
 * Undo/redo for the editor (§8.3): every change is one step, a new change clears redo, and at
 * most [capacity] steps are kept. Immutable, so it lives in ViewModel state.
 */
data class EditHistory<T>(
    val present: T,
    val past: List<T> = emptyList(),
    val future: List<T> = emptyList(),
    val capacity: Int = DEFAULT_CAPACITY,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(next: T): EditHistory<T> =
        if (next == present) this else copy(present = next, past = (past + present).takeLast(capacity), future = emptyList())

    fun undo(): EditHistory<T> =
        if (!canUndo) this else copy(present = past.last(), past = past.dropLast(1), future = listOf(present) + future)

    fun redo(): EditHistory<T> =
        if (!canRedo) this else copy(present = future.first(), past = past + present, future = future.drop(1))

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
