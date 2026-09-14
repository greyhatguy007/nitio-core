package com.greyhatguy007.domain.data.model.searchResult

import com.greyhatguy007.domain.data.type.SearchResultType

data class SearchSuggestions(
    val queries: List<String>,
    val recommendedItems: List<SearchResultType>,
)