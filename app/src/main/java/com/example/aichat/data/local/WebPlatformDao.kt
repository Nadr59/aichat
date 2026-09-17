package com.example.aichat.data.local

import androidx.room.*
import com.example.aichat.data.model.WebPlatform
import kotlinx.coroutines.flow.Flow

@Dao
interface WebPlatformDao {

    @Query("SELECT * FROM web_platforms ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<WebPlatform>>

    @Query("SELECT * FROM web_platforms WHERE isEnabled = 1 ORDER BY sortOrder, name")
    fun observeEnabled(): Flow<List<WebPlatform>>

    @Query("SELECT * FROM web_platforms WHERE id = :id")
    suspend fun getById(id: String): WebPlatform?

    @Query("SELECT COUNT(*) FROM web_platforms")
    suspend fun count(): Int

    /** IGNORE → لا يُحدِّث الموجود، مثالي لـ seed */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WebPlatform>)

    /** لإضافة مخصص أو تحديث موجود */
    @Upsert
    suspend fun upsert(item: WebPlatform)

    /** يشترط isBuiltIn = 0 حمايةً للافتراضية */
    @Query("DELETE FROM web_platforms WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: String): Int
}
