package com.kreyenhagen.homewall.data.route

import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteHold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The single owner of routes (§4). */
class RouteRepository(
    private val dao: RouteDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Every route, unsorted. Sorting and filtering happen in [com.kreyenhagen.homewall.domain.RouteSorting]. */
    val routes: Flow<List<Route>> = dao.observeAll().map { list -> list.map { it.toRoute() } }

    suspend fun get(id: Long): Route? = dao.get(id)?.toRoute()

    /** Saves a new route (`createdAt = updatedAt = now`) and returns its id. */
    suspend fun create(name: String, grade: Int?, holds: List<RouteHold>): Long {
        val now = clock()
        return dao.insert(RouteEntity(name = name, grade = grade, holds = holds, createdAt = now, updatedAt = now))
    }

    /** Updates a route (`updatedAt = now`, `createdAt` unchanged). Returns false if it no longer exists. */
    suspend fun update(id: Long, name: String, grade: Int?, holds: List<RouteHold>): Boolean {
        val existing = dao.get(id) ?: return false
        dao.update(existing.copy(name = name, grade = grade, holds = holds, updatedAt = clock()))
        return true
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
