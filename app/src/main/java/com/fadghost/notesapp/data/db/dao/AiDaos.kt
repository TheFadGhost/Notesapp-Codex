package com.fadghost.notesapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fadghost.notesapp.data.ai.cost.AiCallCost
import com.fadghost.notesapp.data.ai.model.CachedModel
import kotlinx.coroutines.flow.Flow

@Dao
interface AiCostDao {
    @Insert
    suspend fun insert(row: AiCallCost): Long

    @Query("SELECT * FROM AiCallCost ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AiCallCost>>

    @Query("SELECT * FROM AiCallCost ORDER BY createdAt DESC LIMIT 1")
    fun observeLast(): Flow<AiCallCost?>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM AiCallCost WHERE createdAt >= :monthStart")
    fun observeMonthTotal(monthStart: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM AiCallCost WHERE createdAt >= :monthStart")
    suspend fun monthTotal(monthStart: Long): Double
}

@Dao
interface CachedModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<CachedModel>)

    @Query("DELETE FROM CachedModel")
    suspend fun clear()

    @Query("SELECT * FROM CachedModel ORDER BY name")
    fun observeAll(): Flow<List<CachedModel>>

    @Query("SELECT * FROM CachedModel ORDER BY name")
    suspend fun all(): List<CachedModel>

    @Query("SELECT COUNT(*) FROM CachedModel")
    suspend fun count(): Int
}
