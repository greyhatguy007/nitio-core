package com.greyhatguy007.domain.data.model.browse.artist

import com.greyhatguy007.domain.data.model.searchResult.songs.Thumbnail
import com.greyhatguy007.domain.data.type.HomeContentType

data class ResultSingle(
    val browseId: String,
    val thumbnails: List<Thumbnail>,
    val title: String,
    val year: String,
) : HomeContentType