package com.greyhatguy007.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.greyhatguy007.domain.data.model.browse.album.Track

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey(autoGenerate = false)
    val queueId: Long = 0,
    val listTrack: List<Track>,
)