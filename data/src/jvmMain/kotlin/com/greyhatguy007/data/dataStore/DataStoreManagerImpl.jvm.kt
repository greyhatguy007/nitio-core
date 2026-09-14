package com.greyhatguy007.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.greyhatguy007.common.SETTINGS_FILENAME
import com.greyhatguy007.data.io.getHomeFolderPath
import createDataStore
import java.io.File

actual fun createDataStoreInstance(): DataStore<Preferences> = createDataStore(
    producePath = {
        val file = File(getHomeFolderPath(listOf(".com.greyhatguy007.nitio")), "$SETTINGS_FILENAME.preferences_pb")
        file.absolutePath
    }
)