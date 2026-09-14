package com.greyhatguy007.data.di.loader

import com.simpmusic.media_jvm.di.loadDesktopPlayerModule

actual fun loadMediaService() {
    loadDesktopPlayerModule()
}
