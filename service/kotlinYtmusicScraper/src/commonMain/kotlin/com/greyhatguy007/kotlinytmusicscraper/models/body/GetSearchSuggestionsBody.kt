package com.greyhatguy007.kotlinytmusicscraper.models.body

import com.greyhatguy007.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class GetSearchSuggestionsBody(
    val context: Context,
    val input: String,
)