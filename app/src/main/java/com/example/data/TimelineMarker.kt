package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_markers",
    indices = [Index(value = ["mediaId"])]
)
data class TimelineMarker(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val mediaId: Int,
    val timeMs: Long,
    val name: String? = null,
    val description: String? = null,
    val type: String = "STANDARD", // STANDARD, START_RANGE, END_RANGE, SAFE_* or SAFE_SUGGESTION
    val createdAt: Long = System.currentTimeMillis(),
    val endTimeMs: Long? = null,
    val category: String? = null,
    val action: String? = null,
    val profileId: String? = null,
    val confidence: Float? = null,
    val source: String? = null,
    val reviewStatus: String? = null
)
