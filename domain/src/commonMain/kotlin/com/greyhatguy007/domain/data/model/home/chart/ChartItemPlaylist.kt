package com.greyhatguy007.domain.data.model.home.chart

import com.greyhatguy007.domain.data.model.browse.artist.ResultPlaylist

data class ChartItemPlaylist(
    val title: String,
    val playlists: List<ResultPlaylist>,
)