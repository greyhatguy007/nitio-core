package com.greyhatguy007.data.mediaservice

actual fun createMediaServiceHandler(
    dataStoreManager: com.greyhatguy007.domain.manager.DataStoreManager,
    songRepository: com.greyhatguy007.domain.repository.SongRepository,
    streamRepository: com.greyhatguy007.domain.repository.StreamRepository,
    localPlaylistRepository: com.greyhatguy007.domain.repository.LocalPlaylistRepository,
    analyticsRepository: com.greyhatguy007.domain.repository.AnalyticsRepository,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
): com.greyhatguy007.domain.mediaservice.handler.MediaPlayerHandler {
    TODO("Not yet implemented")
}