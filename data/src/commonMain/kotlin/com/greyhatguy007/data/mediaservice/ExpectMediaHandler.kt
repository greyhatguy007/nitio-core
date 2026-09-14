package com.greyhatguy007.data.mediaservice

import com.greyhatguy007.domain.manager.DataStoreManager
import com.greyhatguy007.domain.mediaservice.handler.MediaPlayerHandler
import com.greyhatguy007.domain.repository.AnalyticsRepository
import com.greyhatguy007.domain.repository.LocalPlaylistRepository
import com.greyhatguy007.domain.repository.SongRepository
import com.greyhatguy007.domain.repository.StreamRepository
import kotlinx.coroutines.CoroutineScope

expect fun createMediaServiceHandler(
    dataStoreManager: DataStoreManager,
    songRepository: SongRepository,
    streamRepository: StreamRepository,
    localPlaylistRepository: LocalPlaylistRepository,
    analyticsRepository: AnalyticsRepository,
    coroutineScope: CoroutineScope,
): MediaPlayerHandler