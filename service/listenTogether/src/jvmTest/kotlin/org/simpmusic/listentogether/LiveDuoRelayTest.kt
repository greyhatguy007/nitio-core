package org.simpmusic.listentogether

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Duo mode against a real server: a guest that cannot publish a `playback_action` at all asks for the
 * host role, gets it, and then drives the room with real authority.
 *
 * Separate from [LiveRoomSyncTest] deliberately. That one proves the ordinary host/guest flow; this
 * one proves the handover, and the pair of them are the two halves of "both listeners can control the
 * room". Both are `@Ignore`d — they create a room on someone else's server — so delete the annotation
 * to run either.
 *
 * Three things are being checked, and only the first is obvious:
 *
 * 1. the ask REACHES the host, which is the one thing a guest cannot do for itself;
 * 2. the host's `transfer_host` is accepted and both sides see the role move — that is the server's
 *    own rule, not ours, and if it refused, duo mode would have nowhere left to go;
 * 3. the device that asked can then publish, which is the point of the whole exercise.
 *
 * What is NOT checked here is the bridge — the echo suppression and the "publish my real state on
 * becoming host" step live in `ListenTogetherPlaybackBridge`, which needs a player and therefore a
 * device. This file is about the transport underneath it.
 */
class LiveDuoRelayTest {
    /** Generous: a real network hop, not a latency assertion. */
    private val timeoutMs = 25_000L

    private fun session(version: String) =
        ListenTogetherSession(
            ListenTogetherClient(
                clientVersion = version,
                serverUrl = { "wss://example.com/ws" },
                userAgent = "SimpMusic/test (org.com.greyhatguy007.nitio.test; JVM)",
            ),
        )

    private suspend fun waitFor(
        label: String,
        block: () -> Boolean,
    ): Boolean =
        (
            withTimeoutOrNull(timeoutMs) {
                while (!block()) delay(150)
                true
            } ?: false
        ).also { if (!it) println("  ✗ timed out waiting for $label") }

    @Ignore
    @Test
    fun aDuoGuestTakesTheRoomOverAndThenDrivesIt() =
        runBlocking {
            val host = session("com.greyhatguy007.nitio-host-test")
            val guest = session("com.greyhatguy007.nitio-guest-test")
            val ask = CompletableDeferred<RelayedControl>()
            var collector: Job? = null

            try {
                host.connect()
                guest.connect()
                assertTrue(waitFor("host connected") { host.state.value.isConnected }, "host never connected")
                assertTrue(waitFor("guest connected") { guest.state.value.isConnected }, "guest never connected")
                println("✓ both clients connected")

                host.createRoom("HostUser")
                assertTrue(waitFor("room_created") { host.state.value.roomCode != null }, "host never got a room")
                val code = host.state.value.roomCode!!
                println("✓ room $code created")

                guest.joinRoom(code, "GuestUser")
                assertTrue(
                    waitFor("join_request") { host.state.value.joinRequests.isNotEmpty() },
                    "host never saw the join request",
                )
                host.approveJoin(host.state.value.joinRequests.first().userId)
                assertTrue(waitFor("join_approved") { guest.state.value.roomCode == code }, "guest never got in")
                println("✓ guest is in the room")

                // The ask travels as a suggestion, and the server drops one without an id and a
                // title, so the room has to be on a track before the guest can ask for anything.
                val track = TrackInfo(id = "dQw4w9WgXcQ", title = "Test Track", artist = "Tester", duration = 180_000L)
                host.sendPlaybackAction(PlaybackActions.CHANGE_TRACK, track.id, 0L, track)
                assertTrue(
                    waitFor("track on guest") { guest.state.value.currentTrack?.id == track.id },
                    "guest never received the track",
                )

                // Started BEFORE the guest speaks: the flow carries no replay, so an ask that lands
                // before the collector is running is an ask nobody saw.
                collector = launch { host.relayedControls.collect { ask.complete(it) } }

                val guestId = guest.state.value.selfUserId
                assertTrue(guestId.isNotBlank(), "the guest never learned its own user id")
                guest.requestHost(track)

                val relayed = withTimeoutOrNull(timeoutMs) { ask.await() }
                assertNotNull(relayed, "the host never received the guest's ask")
                assertTrue(relayed.isTakeOver, "the ask arrived as a transport command: ${relayed.action}")
                assertEquals(guestId, relayed.fromUserId, "the ask named the wrong requester")
                println("✓ host received the take-over request from ${relayed.fromUserId}")

                // The guest publishes no `playback_action`, so nothing answers it `not_host` — and
                // nothing answers the ask either, since the session clears it rather than queuing it.
                assertNull(guest.state.value.error, "the guest was refused by the server")

                host.transferHost(relayed.fromUserId)
                assertTrue(
                    waitFor("guest is host") { guest.state.value.isHost },
                    "the handover never reached the guest",
                )
                assertTrue(
                    waitFor("host is not host") { !host.state.value.isHost },
                    "the host kept the role it just gave away",
                )
                println("✓ the role moved — the guest is the host now")

                // And it is real authority, not a label: this is exactly what the server refused the
                // guest before the handover, and what the entire arrangement exists to get.
                guest.sendPlaybackAction(PlaybackActions.SEEK, "", 45_000L, null)
                assertTrue(
                    waitFor("seek on the old host") { host.state.value.position >= 45_000L },
                    "the room never followed the new host (old host at ${host.state.value.position})",
                )
                val sought = host.state.value.position
                println("✓ the new host drove the room to $sought")

                // The regression, against the real server. A change_track for the song the room is
                // ALREADY on is what a handover used to send, and `ActionChangeTrack` overwrites
                // its position with 0 — whatever the sender wrote — before rebroadcasting it. So
                // this command really does zero the room, and the client has to refuse to believe
                // it: believing it is what restarted the music for both devices on a plain seek.
                guest.sendPlaybackAction(PlaybackActions.CHANGE_TRACK, track.id, 0L, track)
                delay(700)
                assertTrue(
                    host.state.value.position > 40_000L,
                    "a redundant change_track rewound the room to ${host.state.value.position}",
                )
                println("✓ a redundant change_track did not rewind the room (still ${host.state.value.position})")

                host.leaveRoom()
                guest.leaveRoom()
                delay(300)
            } finally {
                collector?.cancel()
                host.release()
                guest.release()
            }
        }
}
