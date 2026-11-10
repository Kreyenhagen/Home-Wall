package com.kreyenhagen.homewall.data.route

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteHold
import kotlinx.serialization.json.Json

/** §6.3 */
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 0..17 = V0..V17, null = Project (ungraded). */
    val grade: Int?,
    val holds: List<RouteHold>,
    val createdAt: Long,
    val updatedAt: Long,
)

fun RouteEntity.toRoute() = Route(id, name, grade, holds, createdAt, updatedAt)

/** Stores a route's holds as a JSON string. */
class RouteConverters {
    @TypeConverter
    fun holdsToJson(holds: List<RouteHold>): String = json.encodeToString(holds)

    @TypeConverter
    fun holdsFromJson(text: String): List<RouteHold> = json.decodeFromString(text)

    private companion object {
        val json = Json
    }
}
