package com.greyhatguy007.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.greyhatguy007.common.DB_NAME
import com.greyhatguy007.data.io.getHomeFolderPath
import java.io.File

actual fun getDatabaseBuilder(
    converters: Converters
): RoomDatabase.Builder<MusicDatabase> {
    return Room.databaseBuilder<MusicDatabase>(
        name = getDatabasePath()
    ).addTypeConverter(converters)
}

actual fun getDatabasePath(): String {
    val dbFile = File(getHomeFolderPath(listOf(".com.greyhatguy007.nitio", "db")), DB_NAME)
    return dbFile.absolutePath
}