package com.greyhatguy007.data.di.loader

import com.greyhatguy007.common.AppIdentity
import com.greyhatguy007.data.di.databaseModule
import com.greyhatguy007.data.di.listenTogetherModule
import com.greyhatguy007.data.di.mediaHandlerModule
import com.greyhatguy007.data.di.repositoryModule
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

fun loadAllModules(appIdentity: AppIdentity) {
    loadKoinModules(
        listOf(
            module { single { appIdentity } },
            databaseModule,
            repositoryModule,
        ),
    )
    loadKoinModules(mediaHandlerModule)
    // NOTE: `createdAtStart` is NOT enough for a module loaded this way — nothing constructs it
    // unless something injects it, and the bridge exists purely to listen, so nobody would.
    // ListenTogetherViewModel injects and starts it; start() is idempotent.
    loadKoinModules(listenTogetherModule)
    loadMediaService()
}

expect fun loadMediaService()