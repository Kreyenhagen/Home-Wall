package com.kreyenhagen.homewall.data.route

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes")
    fun observeAll(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun get(id: Long): RouteEntity?

    @Insert
    suspend fun insert(route: RouteEntity): Long

    @Update
    suspend fun update(route: RouteEntity)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [RouteEntity::class], version = 1, exportSchema = true)
@TypeConverters(RouteConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao

    companion object {
        /**
         * Real migrations for every schema change (§6.3). There's deliberately no
         * destructive-migration fallback: it would wipe the routes.
         */
        private val MIGRATIONS = arrayOf<Migration>()

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "homewall.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
