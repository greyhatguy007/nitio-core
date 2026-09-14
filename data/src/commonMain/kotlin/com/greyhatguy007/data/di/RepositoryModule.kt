package com.greyhatguy007.data.di

import com.greyhatguy007.common.Config.SERVICE_SCOPE
import com.greyhatguy007.data.io.fileDir
import com.greyhatguy007.data.repository.AccountRepositoryImpl
import com.greyhatguy007.data.repository.AlbumRepositoryImpl
import com.greyhatguy007.data.repository.AnalyticsRepositoryImpl
import com.greyhatguy007.data.repository.ArtistRepositoryImpl
import com.greyhatguy007.data.repository.AutoEqRepositoryImpl
import com.greyhatguy007.data.lyrics.LyricsRomanizerRepositoryImpl
import com.greyhatguy007.data.repository.CommonRepositoryImpl
import com.greyhatguy007.data.repository.HomeRepositoryImpl
import com.greyhatguy007.data.repository.ImportRepositoryImpl
import com.greyhatguy007.data.repository.LocalPlaylistRepositoryImpl
import com.greyhatguy007.data.repository.LyricsCanvasRepositoryImpl
import com.greyhatguy007.data.repository.PlaylistRepositoryImpl
import com.greyhatguy007.data.repository.PodcastRepositoryImpl
import com.greyhatguy007.data.repository.SearchRepositoryImpl
import com.greyhatguy007.data.repository.SongRepositoryImpl
import com.greyhatguy007.data.repository.StreamRepositoryImpl
import com.greyhatguy007.data.repository.UpdateRepositoryImpl
import com.greyhatguy007.domain.repository.AccountRepository
import com.greyhatguy007.domain.repository.AlbumRepository
import com.greyhatguy007.domain.repository.AnalyticsRepository
import com.greyhatguy007.domain.repository.ArtistRepository
import com.greyhatguy007.domain.repository.AutoEqRepository
import com.greyhatguy007.domain.repository.LyricsRomanizerRepository
import com.greyhatguy007.domain.repository.CommonRepository
import com.greyhatguy007.domain.repository.HomeRepository
import com.greyhatguy007.domain.repository.ImportRepository
import com.greyhatguy007.domain.repository.LocalPlaylistRepository
import com.greyhatguy007.domain.repository.LyricsCanvasRepository
import com.greyhatguy007.domain.repository.PlaylistRepository
import com.greyhatguy007.domain.repository.PodcastRepository
import com.greyhatguy007.domain.repository.SearchRepository
import com.greyhatguy007.domain.repository.SongRepository
import com.greyhatguy007.domain.repository.StreamRepository
import com.greyhatguy007.domain.repository.UpdateRepository
import org.koin.core.qualifier.named
import org.koin.dsl.module

val repositoryModule =
    module {
        single<AccountRepository>(createdAtStart = true) {
            AccountRepositoryImpl(get(), get())
        }

        single<AlbumRepository>(createdAtStart = true) {
            AlbumRepositoryImpl(get(), get())
        }

        single<ArtistRepository>(createdAtStart = true) {
            ArtistRepositoryImpl(get(), get(), get())
        }

        single<CommonRepository>(createdAtStart = true) {
            CommonRepositoryImpl(get(named(SERVICE_SCOPE)), get(), get(), get(), get(), get()).apply {
                this.init("${fileDir()}/ytdlp-cookie.txt", get())
            }
        }

        // Lazy for the same reason its client is: the picker is the only thing that wants it.
        single<AutoEqRepository> {
            AutoEqRepositoryImpl(get(), get())
        }

        // Lazy: constructing it costs a few File.length() calls, but the kuromoji dictionary
        // behind it is loaded on first Japanese line and never before — so this must NOT be
        // createdAtStart, or every launch pays for a feature most listeners leave off. The path
        // is where Android keeps the downloaded ipadic pack (the APK no longer bundles it);
        // Desktop and iOS ignore it.
        single<LyricsRomanizerRepository> {
            LyricsRomanizerRepositoryImpl("${fileDir()}/kuromoji-ipadic")
        }

        single<HomeRepository>(createdAtStart = true) {
            HomeRepositoryImpl(get(), get())
        }

        single<ImportRepository>(createdAtStart = true) {
            ImportRepositoryImpl(get())
        }

        single<LocalPlaylistRepository>(createdAtStart = true) {
            LocalPlaylistRepositoryImpl(get(), get())
        }

        single<LyricsCanvasRepository>(createdAtStart = true) {
            LyricsCanvasRepositoryImpl(get(), get(), get(), get(), get())
        }

        single<PlaylistRepository>(createdAtStart = true) {
            PlaylistRepositoryImpl(get(), get(), get())
        }

        single<PodcastRepository>(createdAtStart = true) {
            PodcastRepositoryImpl(get(), get())
        }

        single<SearchRepository>(createdAtStart = true) {
            SearchRepositoryImpl(get(), get())
        }

        single<SongRepository>(createdAtStart = true) {
            SongRepositoryImpl(get(), get(), get())
        }

        single<StreamRepository>(createdAtStart = true) {
            StreamRepositoryImpl(get(), get())
        }

        single<UpdateRepository>(createdAtStart = true) {
            UpdateRepositoryImpl(get())
        }

        single<AnalyticsRepository>(createdAtStart = true) {
            AnalyticsRepositoryImpl(get())
        }
    }