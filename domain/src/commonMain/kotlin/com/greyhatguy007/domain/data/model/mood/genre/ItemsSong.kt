package com.greyhatguy007.domain.data.model.mood.genre

import com.greyhatguy007.domain.data.model.searchResult.songs.Artist

data class ItemsSong(
    val title: String,
    val artist: List<Artist>?,
    val videoId: String,
)