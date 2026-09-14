package org.simpmusic.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relay is the only way a duo-mode guest can drive a room (see [ControlRelay]), and it travels
 * through a field the server truncates and the host decodes by hand. Both halves of that are pinned
 * here rather than trusted: a payload that fails to decode is a request that silently never happens,
 * with nothing on screen to say so.
 *
 * The set also covers take_host, which is not a transport command at all — it asks for the room — so
 * the truncation and length invariants have to hold for it too. A cut one must decode to null rather
 * than to some other action, or a guest asking for control would silently pause the song instead.
 */
class ControlRelayTest {
    private val ALL_PLAYBACK_ACTIONS =
        listOf(
            PlaybackActions.PLAY,
            PlaybackActions.PAUSE,
            PlaybackActions.SEEK,
            PlaybackActions.SKIP_NEXT,
            PlaybackActions.SKIP_PREV,
            PlaybackActions.CHANGE_TRACK,
            PlaybackActions.QUEUE_ADD,
            PlaybackActions.QUEUE_REMOVE,
            PlaybackActions.QUEUE_CLEAR,
            PlaybackActions.SYNC_QUEUE,
            PlaybackActions.SET_VOLUME,
        )

    private fun suggestion(suggestedBy: String) =
        TrackInfo(id = "dQw4w9WgXcQ", title = "Test Track", artist = "Tester", suggestedBy = suggestedBy)

    @Test
    fun everyRelayableActionSurvivesARoundTrip() {
        ControlRelay.RELAYABLE_ACTIONS.forEach { action ->
            val relay = ControlRelay.decode(suggestion(ControlRelay.encode(action, 12_345L)))
            assertNotNull(relay, "$action did not decode")
            assertEquals(action, relay.action)
            assertEquals(12_345L, relay.positionMs)
            // Taken from the carrier rather than the payload — the command targets the track the
            // suggestion is about, so encoding it again would only lengthen the carrier.
            assertEquals("dQw4w9WgXcQ", relay.trackId)
        }
    }

    @Test
    fun theDecodedTrackCarriesNoMarker() {
        // The host publishes this track onward, so the marker must not ride along with it: it is not
        // part of any song.
        val relay = ControlRelay.decode(suggestion(ControlRelay.encode(PlaybackActions.CHANGE_TRACK, 0L)))
        assertNotNull(relay)
        assertEquals("", relay.track.suggestedBy)
        assertEquals("dQw4w9WgXcQ", relay.track.id)
        assertEquals("Test Track", relay.track.title)
        assertEquals(PlaybackActions.CHANGE_TRACK, relay.action)
    }

    @Test
    fun anOrdinarySuggestionIsNotARelay() {
        // What a guest without duo mode sends, and what a Metrolist client sends — both must land on
        // the suggestion path, not on the command path.
        assertNull(ControlRelay.decode(suggestion("")))
        assertNull(ControlRelay.decode(suggestion("Some Artist")))
        assertNull(ControlRelay.decode(null))
        // A marker with nothing behind it is not a command either.
        assertNull(ControlRelay.decode(suggestion("SMPCTL1")))
        assertNull(ControlRelay.decode(suggestion("SMPCTL1|")))
        assertNull(ControlRelay.decode(suggestion("OtherCtrl|1000|seek")))
    }

    @Test
    fun aCommandTheGuestMayNotRelayIsRefused() {
        // The queue is the host's to order, so a queued command must not be readable as one at all —
        // and neither may an action nobody has heard of, whatever it might mean to a future server.
        assertNull(ControlRelay.decode(suggestion(ControlRelay.encode(PlaybackActions.QUEUE_CLEAR, 0L))))
        assertNull(ControlRelay.decode(suggestion(ControlRelay.encode(PlaybackActions.SYNC_QUEUE, 0L))))
        assertNull(ControlRelay.decode(suggestion(ControlRelay.encode(PlaybackActions.SET_VOLUME, 0L))))
        assertNull(ControlRelay.decode(suggestion("SMPCTL1|0|do_something_else")))
    }

    @Test
    fun noTruncatedPayloadEverDecodes() {
        // The server trims `suggestedBy` to 50 characters, and a shortened payload must never be
        // obeyed: a position cut in half reads as 12ms where 1234567ms was meant, which is a
        // perfectly plausible command pointing somewhere else entirely. EVERY prefix is checked,
        // because the cut can land anywhere — the action last in the payload is what makes them all
        // fail, since no action name is a prefix of another.
        ControlRelay.RELAYABLE_ACTIONS.forEach { action ->
            val full = ControlRelay.encode(action, 1_234_567L)
            (1 until full.length).forEach { cut ->
                assertNull(
                    ControlRelay.decode(suggestion(full.take(cut))),
                    "$action cut to \"${full.take(cut)}\" decoded as a command",
                )
            }
        }
    }

    @Test
    fun aMangledPayloadIsRefusedRatherThanGuessedAt() {
        assertNull(ControlRelay.decode(suggestion("SMPCTL1|not-a-number|seek")))
        assertNull(ControlRelay.decode(suggestion("SMPCTL1||seek")))
        assertNull(ControlRelay.decode(suggestion("SMPCTL1|1000")))
        assertNull(ControlRelay.decode(suggestion("SMPCTL1|1000|")))
    }

    @Test
    fun aNegativePositionIsClampedRatherThanForwarded() {
        // The server answers a negative position with `invalid_position`, which would surface as a
        // failed command on the host instead of a harmless clamp here.
        val relay = ControlRelay.decode(suggestion("SMPCTL1|-500|seek"))
        assertNotNull(relay)
        assertEquals(0L, relay.positionMs)
    }

    @Test
    fun theRequesterTravelsWithTheAsk() {
        // `fromUserId` is the one thing the carrier has no room for and the one thing the host
        // cannot answer without: `transfer_host` takes a user id, and the payload cannot name it.
        val relay =
            ControlRelay.decode(
                suggestion(ControlRelay.encodeTakeHost()),
                fromUserId = "user_1789386031073473764_1986",
            )
        assertNotNull(relay)
        assertTrue(relay.isTakeOver)
        assertEquals("user_1789386031073473764_1986", relay.fromUserId)
        // And it is still a well-formed carrier for a track this client can name.
        assertEquals("dQw4w9WgXcQ", relay.trackId)
    }

    @Test
    fun onlyTheTakeOverIsNotATransportCommand() {
        // The distinction the host acts on. `take_host` must not be publishable as a
        // `playback_action` — the server would try to execute it and refuse — which is why it lives
        // on [ControlRelay] rather than on [PlaybackActions].
        assertTrue(ControlRelay.ACTION_TAKE_HOST !in ALL_PLAYBACK_ACTIONS)
        ControlRelay.RELAYABLE_ACTIONS.forEach { action ->
            val relay = ControlRelay.decode(suggestion(ControlRelay.encode(action, 0L)))
            assertNotNull(relay, "$action did not decode")
            assertEquals(action == ControlRelay.ACTION_TAKE_HOST, relay.isTakeOver)
        }
    }

    @Test
    fun everyCommandFitsInTheFieldTheServerAllows() {
        // `sanitizeTrackInfo` cuts `suggestedBy` to MaxUsernameLength (50), so the longest command a
        // guest can send has to fit inside that. This is what catches a future action name or
        // payload shape outgrowing the carrier — silently, otherwise, since a truncated payload
        // simply decodes to null and the guest's action looks like it was ignored.
        ControlRelay.RELAYABLE_ACTIONS.forEach { action ->
            val longest = ControlRelay.encode(action, 86_399_999L)
            assertTrue(
                longest.length <= ControlRelay.MAX_ENCODED_LENGTH,
                "$action encodes to ${longest.length} characters: $longest",
            )
        }
    }
}
