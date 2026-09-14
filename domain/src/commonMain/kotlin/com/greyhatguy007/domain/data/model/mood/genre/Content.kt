package com.greyhatguy007.domain.data.model.mood.genre

import com.greyhatguy007.domain.data.model.searchResult.songs.Thumbnail
import com.greyhatguy007.domain.data.type.HomeContentType

data class Content(
    val playlistBrowseId: String,
    val thumbnail: List<Thumbnail>?,
    val title: Title,
) : HomeContentType