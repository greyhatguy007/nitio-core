package com.greyhatguy007.kotlinytmusicscraper.models.body

import com.greyhatguy007.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class GetTranscriptBody(
    val context: Context,
    val params: String,
)