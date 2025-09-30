package edu.asu.cse535.contextmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(record: SessionRecord): Long

    @Query("SELECT * FROM session_records ORDER BY timestamp DESC")
    suspend fun all(): List<SessionRecord>

    @Query("DELETE FROM session_records")
    suspend fun deleteAll()
}
