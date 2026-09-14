package org.simpmusic.listentogether

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rule that decides whether a playback command's position and play state can be believed.
 *
 * It is pinned here because getting it wrong is not a visible error anywhere: the server rebroadcasts
 * `change_track` with `Position = 0` and `IsPlaying = false` no matter what was sent, so a follower
 * that trusts those numbers simply starts the song over — for everyone, and with nothing in the logs
 * to say why. See [saysNothingAboutPositionOrPlayState].
 */
class PlaybackPositionTrustTest {
    private val track = TrackInfo(id = "dQw4w9WgXcQ", title = "Test Track")

    private fun payload(
        action: String,
        trackInfo: TrackInfo? = null,
    ) = PlaybackActionPayload(action = action, trackId = trackInfo?.id.orEmpty(), position = 0L, trackInfo = trackInfo)

    @Test
    fun aChangeTrackForTheSongWeAreOnSaysNothing() {
        // The case the whole rule exists for: the track has not changed, so the zero and the "paused"
        // that came with it are the server's own doing and mean nothing.
        assertTrue(payload(PlaybackActions.CHANGE_TRACK, track).saysNothingAboutPositionOrPlayState(track.id))
    }

    @Test
    fun aChangeTrackForAnotherSongIsHonoured() {
        // A new track genuinely starts at the top, so this zero is information, not an artefact.
        val other = TrackInfo(id = "abcdefghijk", title = "Another Track")
        assertFalse(payload(PlaybackActions.CHANGE_TRACK, other).saysNothingAboutPositionOrPlayState(track.id))
        // ...and so is one arriving before we know of any track at all — the first song in a room.
        assertFalse(payload(PlaybackActions.CHANGE_TRACK, track).saysNothingAboutPositionOrPlayState(null))
    }

    @Test
    fun aTracklessChangeTrackIsHonoured() {
        // The server refuses these, but a rule that only holds for well-formed input is not a rule:
        // with nothing to compare, the safe answer is to do what we are told.
        assertFalse(payload(PlaybackActions.CHANGE_TRACK, null).saysNothingAboutPositionOrPlayState(track.id))
    }

    @Test
    fun seekAndPlayAndPauseAreAlwaysBelieved() {
        // All three carry a position the server applies and re-sends, so a position of 0 from any of
        // them is a real request to go back to the start — including one that names the current
        // song, which is how a peer announces a seek.
        assertFalse(payload(PlaybackActions.SEEK, track).saysNothingAboutPositionOrPlayState(track.id))
        assertFalse(payload(PlaybackActions.SEEK).saysNothingAboutPositionOrPlayState(track.id))
        assertFalse(payload(PlaybackActions.PLAY, track).saysNothingAboutPositionOrPlayState(track.id))
        assertFalse(payload(PlaybackActions.PAUSE, track).saysNothingAboutPositionOrPlayState(track.id))
        assertFalse(payload(PlaybackActions.SYNC_QUEUE, track).saysNothingAboutPositionOrPlayState(track.id))
    }
}
