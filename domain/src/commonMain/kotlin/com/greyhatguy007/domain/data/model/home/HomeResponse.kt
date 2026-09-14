package com.greyhatguy007.domain.data.model.home

import com.greyhatguy007.domain.data.model.home.chart.Chart
import com.greyhatguy007.domain.data.model.mood.Mood
import com.greyhatguy007.domain.utils.Resource

data class HomeResponse(
    val homeItem: Resource<ArrayList<HomeItem>>,
    val exploreMood: Resource<Mood>,
    val exploreChart: Resource<Chart>,
)