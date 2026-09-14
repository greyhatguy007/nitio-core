package com.greyhatguy007.domain.data.model.network

import com.greyhatguy007.domain.manager.DataStoreManager

data class ProxyConfiguration(
    val host: String,
    val port: Int,
    val type: DataStoreManager.ProxyType,
    val username: String? = null,
    val password: String? = null,
)