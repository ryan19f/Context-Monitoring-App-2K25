package edu.asu.cse535.contextmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_records")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val heartRateBpm: Int,
    val respiratoryRateBpm: Int,
    val sFever: Int,
    val sCough: Int,
    val sSOB: Int,
    val sFatigue: Int,
    val sAches: Int,
    val sHeadache: Int,
    val sThroat: Int,
    val sNasal: Int,
    val sNausea: Int,
    val sDiarrhea: Int
)
