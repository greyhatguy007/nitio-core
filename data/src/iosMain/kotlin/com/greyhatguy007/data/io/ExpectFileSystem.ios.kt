package com.greyhatguy007.data.io

import com.greyhatguy007.data.db.documentDirectory
import okio.FileSystem

actual fun fileSystem(): FileSystem = FileSystem.SYSTEM
actual fun fileDir(): String = documentDirectory() + "/SimpMusic"