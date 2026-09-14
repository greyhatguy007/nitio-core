package com.greyhatguy007.kotlinytmusicscraper.pages

import com.greyhatguy007.kotlinytmusicscraper.models.AlbumItem
import com.greyhatguy007.kotlinytmusicscraper.models.VideoItem

data class ExplorePage(
    val released: List<AlbumItem>,
    val musicVideo: List<VideoItem>,
)