package com.greyhatguy007.domain.data.model.home

import com.greyhatguy007.domain.data.model.home.chart.Chart
import com.greyhatguy007.domain.data.model.mood.Mood
import com.greyhatguy007.domain.utils.Resource

data class HomeDataCombine(
    val home: Resource<Pair<String?, List<HomeItem>>>,
    val mood: Resource<Mood>,
    val chart: Resource<Chart>,
    val newRelease: Resource<List<HomeItem>>,
)