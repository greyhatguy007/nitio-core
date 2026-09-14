package com.greyhatguy007.domain.data.model.browse.artist

import com.greyhatguy007.domain.data.model.searchResult.songs.Thumbnail
import com.greyhatguy007.domain.data.type.HomeContentType

data class ResultAlbum(
    val browseId: String,
    val isExplicit: Boolean,
    val thumbnails: List<Thumbnail>,
    val title: String,
    val year: String,
) : HomeContentType