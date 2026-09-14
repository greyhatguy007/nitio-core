package com.greyhatguy007.data.mediaservice

import com.greyhatguy007.domain.repository.AnalyticsRepository

actual fun createMediaServiceHandler(
    dataStoreManager: com.greyhatguy007.domain.manager.DataStoreManager,
    songRepository: com.greyhatguy007.domain.repository.SongRepository,
    streamRepository: com.greyhatguy007.domain.repository.StreamRepository,
    localPlaylistRepository: com.greyhatguy007.domain.repository.LocalPlaylistRepository,
    analyticsRepository: AnalyticsRepository,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
): com.greyhatguy007.domain.mediaservice.handler.MediaPlayerHandler =
    MediaServiceHandlerImpl(
        dataStoreManager,
        songRepository,
        streamRepository,
        localPlaylistRepository,
        analyticsRepository,
        coroutineScope,
    )