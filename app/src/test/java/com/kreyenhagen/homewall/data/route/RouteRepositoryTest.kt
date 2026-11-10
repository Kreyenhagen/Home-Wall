package com.kreyenhagen.homewall.data.route

import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.RouteHold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory [RouteDao] that behaves like Room's for the calls the app makes. */
class FakeRouteDao : RouteDao {
    val rows = MutableStateFlow<List<RouteEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<RouteEntity>> = rows

    override suspend fun get(id: Long): RouteEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(route: RouteEntity): Long {
        val id = nextId++
        rows.update { it + route.copy(id = id) }
        return id
    }

    override suspend fun update(route: RouteEntity) = rows.update { list -> list.map { if (it.id == route.id) route else it } }

    override suspend fun delete(id: Long) = rows.update { list -> list.filterNot { it.id == id } }
}

class RouteRepositoryTest {

    private val dao = FakeRouteDao()
    private var now = 1_000L
    private val repo = RouteRepository(dao, clock = { now })
    private val holds = listOf(RouteHold(3, HoldRole.START), RouteHold(40, HoldRole.FINISH))

    @Test
    fun createStampsBothTimes() = runTest {
        val id = repo.create("Crimps", 4, holds)
        val route = repo.get(id) ?: error("missing")
        assertEquals("Crimps", route.name)
        assertEquals(4, route.grade)
        assertEquals(holds, route.holds)
        assertEquals(1_000L, route.createdAt)
        assertEquals(1_000L, route.updatedAt)
    }

    @Test
    fun updateKeepsCreatedAt() = runTest {
        val id = repo.create("Crimps", null, holds)
        now = 5_000L
        assertTrue(repo.update(id, "Crimps v2", 6, holds.take(1)))
        val route = repo.get(id) ?: error("missing")
        assertEquals("Crimps v2", route.name)
        assertEquals(6, route.grade)
        assertEquals(holds.take(1), route.holds)
        assertEquals(1_000L, route.createdAt)
        assertEquals(5_000L, route.updatedAt)
    }

    @Test
    fun updatingADeletedRouteReportsIt() = runTest {
        val id = repo.create("Gone", null, holds)
        repo.delete(id)
        assertFalse(repo.update(id, "Gone", null, holds))
        assertNull(repo.get(id))
        assertEquals(emptyList<Any>(), repo.routes.first())
    }
}

class RouteConvertersTest {
    private val converters = RouteConverters()

    @Test
    fun holdsAreStoredAsJsonWithRoleNames() {
        val holds = listOf(RouteHold(12, HoldRole.START), RouteHold(40, HoldRole.MIDDLE), RouteHold(415, HoldRole.FINISH))
        val text = converters.holdsToJson(holds)
        assertEquals("""[{"holdId":12,"role":"START"},{"holdId":40,"role":"MIDDLE"},{"holdId":415,"role":"FINISH"}]""", text)
        assertEquals(holds, converters.holdsFromJson(text))
        assertEquals(emptyList<RouteHold>(), converters.holdsFromJson(converters.holdsToJson(emptyList())))
    }
}
