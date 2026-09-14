package com.greyhatguy007.domain.data.model.account

import com.greyhatguy007.domain.data.model.searchResult.songs.Thumbnail

data class AccountInfo(
    val name: String,
    val email: String,
    val pageId: String? = null,
    val thumbnails: List<Thumbnail>,
)