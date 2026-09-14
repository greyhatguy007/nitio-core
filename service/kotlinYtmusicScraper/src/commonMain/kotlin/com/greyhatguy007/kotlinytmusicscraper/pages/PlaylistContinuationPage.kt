package com.greyhatguy007.kotlinytmusicscraper.pages

import com.greyhatguy007.kotlinytmusicscraper.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)