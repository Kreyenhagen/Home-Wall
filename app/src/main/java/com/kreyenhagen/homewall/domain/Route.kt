package com.kreyenhagen.homewall.domain

import kotlinx.serialization.Serializable

/** §6.3. Stored by name in the database, so don't rename entries. FOOT may be added later. */
@Serializable
enum class HoldRole { START, MIDDLE, FINISH }

@Serializable
data class RouteHold(val holdId: Int, val role: HoldRole)

data class Route(
    val id: Long,
    val name: String,
    /** 0..17 = V0..V17; null = Project (ungraded). */
    val grade: Int?,
    val holds: List<RouteHold>,
    /** Epoch ms. */
    val createdAt: Long,
    val updatedAt: Long,
)

object RouteNames {
    const val MAX_LENGTH = 40

    /** The name to save, or null if it's blank or too long (§8.3). */
    fun clean(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
}
