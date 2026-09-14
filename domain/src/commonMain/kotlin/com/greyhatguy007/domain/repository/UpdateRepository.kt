package com.greyhatguy007.domain.repository

import com.greyhatguy007.domain.data.model.update.UpdateData
import com.greyhatguy007.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface UpdateRepository {
    fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>>
    fun checkForFdroidUpdate(): Flow<Resource<UpdateData>>
}