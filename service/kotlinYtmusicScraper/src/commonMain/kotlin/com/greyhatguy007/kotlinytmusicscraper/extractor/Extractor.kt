package com.greyhatguy007.kotlinytmusicscraper.extractor

import com.greyhatguy007.kotlinytmusicscraper.models.SongItem
import com.greyhatguy007.kotlinytmusicscraper.models.response.DownloadProgress

expect class Extractor() {
    fun init()

    fun logIn(cookie: String?)

    fun mergeAudioVideoDownload(filePath: String): DownloadProgress

    fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress

    fun newPipePlayer(videoId: String): List<Pair<Int, String>>
}