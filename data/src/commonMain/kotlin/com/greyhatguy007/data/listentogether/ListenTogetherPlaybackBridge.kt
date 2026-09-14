package com.greyhatguy007.data.listentogether

import com.greyhatguy007.common.MERGING_DATA_TYPE
import com.greyhatguy007.domain.data.model.browse.album.Track
import com.greyhatguy007.domain.data.model.listentogether.RoomTrack
import com.greyhatguy007.domain.data.player.GenericMediaItem
import com.greyhatguy007.domain.data.player.GenericMediaMetadata
import com.greyhatguy007.domain.mediaservice.handler.MediaPlayerHandler
import com.greyhatguy007.domain.mediaservice.handler.QueueData
import com.greyhatguy007.domain.mediaservice.handler.SimpleMediaState
import com.greyhatguy007.domain.repository.ListenTogetherRepository
import com.greyhatguy007.logger.Logger
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.listentogether.ListenTogetherSession
import org.simpmusic.listentogether.PlaybackActions
import org.simpmusic.listentogether.TrackInfo

private const val PLAY_SETTLE_TIMEOUT_MS = 2_000L

/** Poll step for the settle wait; playWhenReady has no flow to collect. */
private const val PLAY_SETTLE_POLL_MS = 50L
private const val TAG = "ListenTogetherBridge"

/** What the guest reacts to. A data class so `distinctUntilChanged` compares every field. */
private data class RoomSnapshot(
    val track: RoomTrack?,
    val isPlaying: Boolean,
    val position: Long,
    val queueIds: List<String>,
)

/**
 * Joins a Listen Together room to the local player.
 *
 * Lives in `commonMain` and talks only to [MediaPlayerHandler], which is the one interface both
 * platforms implement — Android's `MediaServiceHandlerImpl` and Desktop's
 * `JvmMediaPlayerHandlerImpl` share nothing else, so anything written against a concrete player
 * would have to be written twice and would drift.
 *
 * Direction of travel is decided entirely by who hosts:
 * - **Host** watches the local player and publishes what it does.
 * - **Guest** watches the room and applies what the host did. It publishes nothing itself, because
 *   the server refuses `playback_action` from anyone but the host.
 *
 * Duo mode (`repository.pairListeningMode`) makes the guest a driver rather than a listener, but not
 * by giving it a second way to publish: the server refuses `playback_action` from anyone but the
 * host, and it refuses `transfer_host` from anyone but the host too. So the guest asks for the ROLE
 * (see [ControlRelay]), the host hands it over, and whoever holds it drives natively — real payloads,
 * queue included. That is the whole of the baton, and its value is that **exactly one side of the
 * room is ever publishing**, decided by the server rather than by us. The setting has to be on at
 * BOTH ends: one side has to know to ask, the other has to agree to let go.
 *
 * The [applyingRemote] guard is what stops the two roles from feeding each other: applying a remote
 * pause makes the local player report "paused", which would otherwise be published straight back to
 * the server as a fresh command. It is not enough on its own — the player's flows routinely deliver
 * that observation after the guard has been cleared — so the track and seek paths also compare what
 * they are about to say against the ROOM's own view and stay silent when it agrees: an echoed
 * `change_track` would restart the song from the top for everyone, and an echoed seek would travel
 * back at the host. Those two compare values the SERVER owns (the current track id, the room's
 * position), which is what makes the comparison trustworthy.
 *
 * Play/pause has no such value to compare against — see [publishOrRelayPlayPause] — so it suppresses
 * an echo by remembering the intent it last applied. That matters most right after a handover: the
 * device that just gave the role away applies the new host's first command, and an unguarded echo
 * there would ask for the role straight back and silently undo the handover it just agreed to.
 */
class ListenTogetherPlaybackBridge(
    /**
     * Read through the repository (domain types), written through the session (protocol types).
     *
     * The split is deliberate: reacting to a room is app logic and belongs on our own model, while
     * publishing a command is protocol detail that must not leak upward — putting
     * `sendPlaybackAction` on the domain interface would drag Metrolist's schema into domain.
     */
    private val repository: ListenTogetherRepository,
    private val session: ListenTogetherSession,
    private val handler: MediaPlayerHandler,
    private val scope: CoroutineScope,
) {
    private var started = false
    private var applyingRemote = false
    private var lastPublishedTrackId: String? = null
    private var lastAppliedTrackId: String? = null
    private var lastAppliedQueueIds: List<String> = emptyList()

    /**
     * Whether the room was playing before the command currently being applied.
     *
     * Needed because change_track always says "not playing" — see [watchRoomForGuests].
     */
    private var lastRoomPlaying = false

    /**
     * The play/pause intent this device last took FROM the room, and when.
     *
     * A boolean cannot do this job, which is why [applyingRemote] is not enough on its own: it is
     * cleared the moment a remote command finishes being applied, while the player's flows report the
     * resulting change a moment later. Remembering WHICH intent was applied, and for how long it
     * stays attributable, is what lets an echo be told apart from the user pressing the same button —
     * see [publishOrRelayPlayPause].
     */
    private var lastRemotePlayingIntent: Boolean? = null
    private var lastRemoteApplyAtMs = 0L

    /** When this device last asked to hold the room's controls. See [requestBaton]. */
    private var batonRequestedAtMs = 0L

    /**
     * Where the command this device is currently applying asked it to be.
     *
     * Cleared as soon as the player arrives, which is the point: it makes the "this report is our
     * own settling, not the user" suppression last exactly as long as there is settling to suppress,
     * rather than for a fixed period after every remote command. See [publishOrRelaySeeks].
     */
    private var pendingRemotePosition: Long? = null

    /** Idempotent: callers cannot know whether something else already started it. */
    fun start() {
        if (started) return
        started = true
        Logger.i(TAG, "Playback bridge started")
        scope.launch { suppressCrossfadeWhileInRoom() }
        scope.launch { watchRoomForGuests() }
        scope.launch { resyncGuestOnResume() }
        scope.launch { applyRelayedControlsAsHost() }
        scope.launch { publishCurrentStateOnJoin() }
        scope.launch { publishStateWhenSomeoneArrives() }
        scope.launch { publishQueueAsHost() }
        scope.launch { publishOrRelayTrackChanges() }
        scope.launch { publishOrRelayPlayPause() }
        scope.launch { publishOrRelaySeeks() }
        scope.launch { answerBufferBarrier() }
    }

    /**
     * Crossfade overlaps two tracks for seconds, which drifts a room apart at every transition.
     *
     * The user's own setting is left alone — this is a separate override on the player, the same
     * shape as the sleep-timer fade, so a process death mid-room cannot lose their preference.
     */
    private suspend fun suppressCrossfadeWhileInRoom() {
        repository.room
            .map { it.inRoom }
            .distinctUntilChanged()
            .collect { inRoom ->
                withContext(Dispatchers.Main) { handler.player.crossfadeSuppressed = inRoom }
                if (!inRoom) {
                    lastRoomPlaying = false
                } else if (!repository.room.value.isHost) {
                    // Ask for the live position the moment we are in. The state pushed on join
                    // carries the position as of the host's LAST command, which can be minutes old
                    // — obeying it drops a joiner at the start of a song everyone else is halfway
                    // through. sync_state answers with where the room actually is.
                    repository.requestSync()
                }
                Logger.i(TAG, if (inRoom) "Crossfade suppressed for the room" else "Crossfade restored")
            }
    }

    /**
     * A guest may pause, and stays paused.
     *
     * An earlier version forced the guest straight back to the room's state, which made pause
     * impossible — press it and playback resumed instantly. The room is something you listen along
     * with, not something that holds your transport hostage: pausing is local and silent, and
     * pressing play again asks the server where the room actually is now, so resuming lands in the
     * right place instead of wherever this device stopped. This is what Metrolist's manager does
     * (`requestSync` — "call this when a guest presses play/pause").
     *
     * In duo mode this does not apply: the guest's transport takes the room over instead, so its
     * pause becomes the room's pause. Hence the early return below, which is the only thing that
     * keeps the two behaviours from fighting.
     */
    private suspend fun resyncGuestOnResume() {
        handler.controlState
            .map { it.isPlaying }
            .distinctUntilChanged()
            .collect { locallyPlaying ->
                val room = repository.room.value
                if (!room.inRoom || room.isHost || repository.pairListeningMode || applyingRemote) return@collect
                if (!locallyPlaying) {
                    Logger.i(TAG, "Guest paused locally — leaving the room running")
                    return@collect
                }
                Logger.i(TAG, "Guest resumed — asking the server where the room is")
                repository.requestSync()
            }
    }

    // ─────────────────────────── room state synchronisation ───────────────────────────

    private suspend fun watchRoomForGuests() {
        repository.room
            // The queue is part of the key: without it a room state that changed ONLY its queue
            // compares equal here and is dropped, so the guest never builds the host's queue at all.
            .map {
                RoomSnapshot(
                    track = it.currentTrack,
                    isPlaying = it.isPlaying,
                    position = it.position,
                    queueIds = it.queue.map { t -> t.id },
                    // Follow the room we are a GUEST in, and only that one. The old shape
                    // (`|| pairListeningMode`) also had the HOST apply the room it owns, which was
                    // right while commands were relayed one at a time — the host had to obey the
                    // broadcast it had just published on a guest's behalf. The baton leaves nothing
                    // to obey: whoever holds the role IS the authority, and applying our own
                    // broadcast back to ourselves is a second opinion nobody asked for.
                ) to (it.inRoom && !it.isHost)
            }
            .distinctUntilChanged()
            .collect { (snapshot, shouldFollow) ->
                if (!shouldFollow) return@collect
                val (track, isPlaying, position, queueIds) = snapshot
                Logger.i(TAG, "Room says: track=${track?.id} playing=$isPlaying pos=$position queue=${queueIds.size}")
                applyingRemote = true
                try {
                    // Rebuild when the track changes OR when the queue behind it does — the queue
                    // can legitimately arrive while the same track keeps playing.
                    val queueChanged = queueIds != lastAppliedQueueIds
                    val trackChanged =
                        track != null && track.id.isNotBlank() && (track.id != lastAppliedTrackId || queueChanged)
                    // The server forces IsPlaying=false on EVERY change_track — protocol default,
                    // not the host pausing. Obeying it pauses the track just loaded, which is why
                    // the guest sat silent on next, prev AND end-of-song alike. Carry the room's
                    // previous intent across the change; a real pause arrives as its own command,
                    // with the track unchanged, and is applied normally.
                    val playing = if (trackChanged && !isPlaying) lastRoomPlaying else isPlaying
                    lastRoomPlaying = playing
                    // Remember the intent we are about to impose, so its echo — which the player
                    // reports a moment later, long after [applyingRemote] has been cleared — can be
                    // told apart from the user pressing the same button. See
                    // [publishOrRelayPlayPause].
                    lastRemotePlayingIntent = playing
                    lastRemoteApplyAtMs = elapsedMs()
                    pendingRemotePosition = null
                    if (trackChanged && track != null) {
                        val sameTrack = track.id == lastAppliedTrackId
                        lastAppliedTrackId = track.id
                        lastAppliedQueueIds = queueIds
                        // Decided BEFORE loading, not corrected afterwards: loading with a hardcoded
                        // playWhenReady=true and letting applyTransport pause it is a race, and the
                        // guest wins it by starting to play in a room the host has paused.
                        // A NEW track still starts where the room is, not at zero: someone joining
                        // a room mid-song must land next to everyone else. Only the same track
                        // being rebuilt (the queue arrived late) keeps the local playhead.
                        val startAt =
                            when {
                                sameTrack -> handler.player.currentPosition
                                // change_track carries position 0, and 0 means "from the top" —
                                // running it through the clock correction turns it into however
                                // long ago the last command was, which can seek past the end.
                                position <= 0L -> 0L
                                else -> session.positionAt(position, playing)
                            }
                        playTrack(track, keepPosition = startAt, playWhenReady = playing)
                    }
                    applyTransport(playing, position)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to apply remote state: ${e.message}")
                } finally {
                    applyingRemote = false
                }
            }
    }

    private suspend fun applyTransport(
        isPlaying: Boolean,
        position: Long,
    ) = withContext(Dispatchers.Main) {
        // Correct the position for however long the command spent in flight; ServerClock falls back
        // to the raw value whenever it is not calibrated yet.
        val corrected = session.positionAt(position, isPlaying)
        // A small drift is normal and seeking on every tick would stutter; only a real gap is worth
        // a seek, which is also why the host publishes position with each command.
        if (abs(handler.player.currentPosition - corrected) > SEEK_TOLERANCE_MS) {
            // Where we are being sent, so the reports that still say where we WERE can be told apart
            // from the user dragging the scrubber. See [publishOrRelaySeeks].
            pendingRemotePosition = corrected
            handler.player.seekTo(corrected)
        }
        if (isPlaying && !handler.player.playWhenReady) {
            handler.player.play()
        } else if (!isPlaying && handler.player.playWhenReady) {
            handler.player.pause()
        }
    }

    /**
     * Loads the host's track locally.
     *
     * The room only carries a videoId, so the guest resolves its own metadata and stream — which is
     * exactly why two clients on different platforms, or a SimpMusic and a Metrolist client, can
     * share a room at all: both read the same catalogue rather than shipping audio to each other.
     */
    /**
     * Loads the host's track and queue.
     *
     * Built straight from the room's own [TrackInfo], the way Metrolist's manager does
     * (`TrackInfo.toMediaMetadata().toMediaItem()`) — deliberately NOT by resolving metadata from
     * the catalogue first. Two reasons, both learned the hard way:
     *
     * 1. `Track.toGenericMediaItem()` GUESSES song-vs-video from the artwork aspect ratio and
     *    treats the `maxresdefault.jpg` fallback as video. A guest resolving its own metadata lands
     *    on that branch almost every time, ends up on the merged audio+video path, and gets video
     *    with no sound where the host has plain audio.
     * 2. A network round trip per track can hang; `first { it.data != null }` on a flow that never
     *    carries data blocks the whole collector, and with it every later room command.
     *
     * The stream itself is still resolved locally by the player — the room only ever carries ids.
     */
    private suspend fun playTrack(
        info: RoomTrack,
        keepPosition: Long = 0L,
        playWhenReady: Boolean,
    ) {
        val roomQueue = repository.room.value.queue
        // Metrolist's canonicalPlaybackQueue: the current track leads, upcoming follows, no dupes.
        val ordered =
            (listOf(info) + roomQueue.filter { it.id != info.id })
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
        Logger.i(TAG, "Guest loading ${info.id} (${info.title}) + ${ordered.size - 1} upcoming")

        // Dispatchers.Main is mandatory, not tidiness: Media3 throws if the player is touched off
        // the main thread, and the bridge runs on the service scope (Default).
        withContext(Dispatchers.Main) {
            handler.clearMediaItems()
            handler.addMediaItem(ordered.first().toRoomMediaItem(), playWhenReady = playWhenReady)
            val rest = ordered.drop(1)
            if (rest.isNotEmpty()) handler.addMediaItemList(rest.map { it.toRoomMediaItem() })
            // Rebuilding restarts the track; if only the queue changed, put the playhead back.
            if (keepPosition > 0L) handler.player.seekTo(keepPosition)
        }
    }

    /**
     * A room track as a media item.
     *
     * `MERGING_DATA_TYPE.SONG` is set explicitly: inside a room every client must be on the same
     * rendition, and for listening together that rendition is audio. Leaving it to be inferred is
     * what produced video-with-no-sound on the guest.
     */
    private fun RoomTrack.toRoomMediaItem(): GenericMediaItem =
        GenericMediaItem(
            mediaId = id,
            uri = id,
            metadata =
                GenericMediaMetadata(
                    title = title,
                    artist = artist.ifBlank { null },
                    albumTitle = album.ifBlank { null },
                    artworkUri = thumbnail.ifBlank { null },
                    description = MERGING_DATA_TYPE.SONG,
                ),
            customCacheKey = id,
        )

    // ─────────────────────────── host: publish what we do ───────────────────────────

    /**
     * Publishes what is ALREADY playing the moment we become host.
     *
     * Everything else here reacts to a *change* — a track transition, a play/pause. Someone who was
     * already listening and then opens a room produces neither, so without this the room has no state
     * at all and every guest sits in silence waiting for a command that only arrives if the host
     * happens to touch the transport.
     *
     * It is also how a HANDOVER lands, which is why it is keyed on becoming host rather than on
     * joining one. A duo-mode guest does not send the command that took the room over — it asks for
     * the role, and this publish is what tells everyone what it did with it: its own real track,
     * position and queue, rather than a command reconstructed from a truncated carrier.
     */
    private suspend fun publishCurrentStateOnJoin() {
        repository.room
            .map { it.inRoom && it.isHost }
            .distinctUntilChanged()
            .collect { isHosting -> if (isHosting) publishSnapshot() }
    }

    /**
     * Re-publishes for a guest who arrives later.
     *
     * The server keeps the room's last known state, but only what the host has told it; a guest
     * approved before the host's first command would otherwise join an empty room.
     */
    private suspend fun publishStateWhenSomeoneArrives() {
        repository.room
            .map { it.members.size }
            .distinctUntilChanged()
            .collect { count ->
                val state = repository.room.value
                if (state.inRoom && state.isHost && count > 1) publishSnapshot()
            }
    }

    /**
     * Republishes the queue whenever the HOST's own queue changes.
     *
     * Deliberately host-only, even in duo mode where a guest mirrors everything else it does. A
     * whole queue cannot ride a relay — the carrier is a 50-character `suggestedBy`, see
     * [ControlRelay] — and a guest syncing its own order would overwrite the host's on every track,
     * because both ends rebuild the room's queue from what the server holds. A duo-mode guest still
     * chooses WHAT plays: that is a track change, and it is relayed. The order stays the host's.
     */
    private suspend fun publishQueueAsHost() {
        handler.queueData
            .map { it?.data?.listTracks?.map { t -> t.videoId }.orEmpty() }
            .distinctUntilChanged()
            .collect { ids ->
                val state = repository.room.value
                if (!state.inRoom || !state.isHost || applyingRemote || ids.isEmpty()) return@collect
                lastAppliedQueueIds = ids
                publishQueue()
            }
    }

    private fun publishQueue() {
        val data = handler.queueData.value?.data ?: return
        val tracks = data.listTracks.map { it.toTrackInfo() }
        if (tracks.isEmpty()) return
        session.sendQueue(tracks, data.playlistName.orEmpty())
        Logger.i(TAG, "Published queue of ${tracks.size} track(s)")
    }

    private fun publishSnapshot() {
        val item = handler.nowPlaying.value ?: return
        if (item.mediaId.isBlank()) return
        lastPublishedTrackId = item.mediaId
        lastAppliedTrackId = item.mediaId
        val data = handler.queueData.value?.data
        val queueIds = data?.listTracks.orEmpty().map { it.videoId }
        if (queueIds.isNotEmpty()) {
            lastAppliedQueueIds = queueIds
        }
        val position = handler.player.currentPosition
        // A change_track goes out ONLY when the room is on a different song.
        //
        // For one it already has, the command is not a harmless no-op — it is destructive. The
        // server forces `Position = 0` and `IsPlaying = false` on every change_track and
        // rebroadcasts exactly that, discarding the position the sender put in the payload (see
        // `ActionChangeTrack`, which ends `p.Position = 0`). So a redundant one rewinds every
        // listener to the top of the song and makes them rebuild the media item. This is the whole
        // of the "seeking restarts the music for both" bug: a handover's snapshot sent a
        // change_track, the other device obeyed the zero, and its own report of that rewind then
        // travelled back as a fresh seek.
        //
        // The position does not need it either. PLAY and PAUSE both carry one and the server
        // applies it (`room.State.Position = p.Position`), so a single one of those says everything
        // a same-track handover has to say — and unlike a change_track, it moves nobody's playhead
        // to zero on the way.
        if (repository.room.value.currentTrack?.id != item.mediaId) {
            session.sendPlaybackAction(
                action = PlaybackActions.CHANGE_TRACK,
                trackId = item.mediaId,
                // Zero because the server discards it, not because zero is what we want.
                position = 0L,
                trackInfo = item.toTrackInfo(),
                queue = data?.listTracks.orEmpty().map { it.toTrackInfo() },
                queueTitle = data?.playlistName.orEmpty(),
            )
            // Because that position IS discarded, a room that should pick up part-way through has
            // to say so separately. Only when there is a position worth carrying.
            if (position > SEEK_TOLERANCE_MS) {
                session.sendPlaybackAction(
                    action = PlaybackActions.SEEK,
                    trackId = "",
                    position = position,
                    trackInfo = null,
                )
            }
        }
        // Never optional: a change_track does not say whether the room is running — the server
        // explicitly sets IsPlaying=false on one — and on a same-track handover this is the command
        // that carries the real position.
        session.sendPlaybackAction(
            action = if (handler.player.playWhenReady) PlaybackActions.PLAY else PlaybackActions.PAUSE,
            trackId = "",
            position = position,
            trackInfo = null,
        )
        Logger.i(TAG, "Published current state to the room: ${item.mediaId} @ ${position}ms")
    }

    /** Announces whatever the local player moves on to, as a host or as a duo-mode guest. */
    private suspend fun publishOrRelayTrackChanges() {
        handler.nowPlaying
            .filterNotNull()
            .distinctUntilChanged { old, new -> old.mediaId == new.mediaId }
            .collect { item ->
                val state = repository.room.value
                val canAct = state.inRoom && (state.isHost || repository.pairListeningMode)
                if (!canAct || applyingRemote) return@collect
                if (item.mediaId == lastPublishedTrackId) return@collect
                // A track the ROOM is already on needs no announcement, and in duo mode that is the
                // common case rather than an edge one: the guest applies the host's command and its
                // own player reports the change a moment later. Relaying it back would tell the host
                // to change to the song it is already playing — and `change_track` carries position
                // 0, so the echo restarts it from the top for everyone.
                if (item.mediaId == state.currentTrack?.id) return@collect
                lastPublishedTrackId = item.mediaId
                lastAppliedTrackId = item.mediaId
                val data = handler.queueData.value?.data
                val ids = data?.listTracks.orEmpty().map { it.videoId }
                if (ids.isNotEmpty()) {
                    lastAppliedQueueIds = ids
                }
                Logger.i(TAG, "${if (state.isHost) "Host" else "Duo guest"} intends a track change: ${item.mediaId}")
                publishOrRelay(
                    action = PlaybackActions.CHANGE_TRACK,
                    trackId = item.mediaId,
                    position = 0L,
                    track = item.toTrackInfo(),
                    queue = data?.listTracks.orEmpty().map { it.toTrackInfo() },
                    queueTitle = data?.playlistName.orEmpty(),
                )
                // change_track alone leaves the room paused: the server sets IsPlaying=false on
                // every track change. The host's own controlState does NOT change when one playing
                // track follows another, so nothing else would ever send this and guests would load
                // each new track and sit there stopped.
                //
                // Whether the host is actually going to play this, decided by WAITING rather than
                // by sampling. Reading playWhenReady inline was wrong twice over: it is false while
                // a next-track buffers, and false again for a moment while the player is rebuilt
                // for a track the host picked from a list — so the PLAY that guests depend on was
                // dropped on exactly the transitions it exists for. A host who is genuinely paused
                // simply never satisfies this and the room stays paused.
                val started =
                    withTimeoutOrNull(PLAY_SETTLE_TIMEOUT_MS) {
                        // playWhenReady, not isPlaying: the intent flips the moment the load path
                        // commits, while audible playback waits for the stream URL to resolve —
                        // which can take longer than any reasonable timeout. Waiting for audio here
                        // is why picking a track still left guests paused. Polled, because
                        // playWhenReady is a plain property with no flow behind it.
                        while (!handler.player.playWhenReady && !handler.player.isPlaying) {
                            delay(PLAY_SETTLE_POLL_MS)
                        }
                    } != null
                if (started) {
                    // Naming the track we just moved to matters only to a relay: the carrier has to
                    // be a well-formed suggestion, and the song being played is the honest way to
                    // make it one. A host publishes no metadata on a PLAY.
                    publishOrRelay(
                        action = PlaybackActions.PLAY,
                        position = handler.player.currentPosition,
                        track = item.toTrackInfo(),
                    )
                }
            }
    }

    /** Announces play/pause, as a host or as a duo-mode guest. */
    private suspend fun publishOrRelayPlayPause() {
        handler.controlState
            .map { it.isPlaying }
            .distinctUntilChanged()
            .collect { isPlaying ->
                val state = repository.room.value
                val canAct = state.inRoom && (state.isHost || repository.pairListeningMode)
                if (!canAct || applyingRemote) return@collect
                // A host that merely buffers reports isPlaying=false, indistinguishable from a
                // user pause — and publishing it stops the WHOLE room on one device's hiccup.
                // playWhenReady carries the intent, so a dip where the two disagree is not news.
                val intent = handler.player.playWhenReady
                if (isPlaying != intent) return@collect
                // A PLAY for a room with no track is refused outright — the server answers
                // `no_track` ("Cannot play without a track") and the app has no way to act on it.
                // It is also never the useful command: the song that is starting reaches the room as
                // a change_track a moment later, and that is what carries the track with it. This is
                // reachable because controlState turns true as soon as a load begins, while
                // nowPlaying is still the old (or no) track.
                if (intent && state.currentTrack == null) {
                    Logger.i(TAG, "Not playing the room — it has no track yet; the track change will say so")
                    return@collect
                }
                // Deliberately NO "the room already agrees" test here, unlike the track and seek
                // paths. `isPlaying` is not a value the room owns: the server forces it FALSE on
                // every `change_track`, and each client carries the real intent privately (see
                // [watchRoomForGuests]). In the window after a track change it therefore reads
                // false while everyone is genuinely playing, and testing against it would silently
                // swallow a real pause — the exact class of bug this whole feature exists to fix.
                //
                // What IS compared is what this device last applied from the room. An echo of a remote
                // command must not be read as a fresh user intent, and that is not academic: the device
                // that has just handed the baton away applies the new host's first command, and
                // without this it would send that observation straight back and ask for the role
                // again — undoing the handover it had just agreed to, with nothing on screen to
                // explain it. Only the intent we applied is suppressed, and only briefly, so a user
                // pressing the opposite button inside the window is still heard.
                if (lastRemotePlayingIntent == intent && elapsedMs() - lastRemoteApplyAtMs < REMOTE_ECHO_WINDOW_MS) {
                    Logger.i(TAG, "Echo of the ${if (intent) "PLAY" else "PAUSE"} just applied — not acting on it")
                    return@collect
                }
                Logger.i(TAG, "${if (state.isHost) "Host" else "Duo guest"} intends ${if (intent) "PLAY" else "PAUSE"}")
                publishOrRelay(
                    action = if (intent) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                    // Deliberately EMPTY. The server rejects a play/pause whose trackId does not
                    // match the track it is holding ("stale_track") and drops it silently; sending
                    // nothing makes it fill in its own current track, which is always right.
                    trackId = "",
                    position = handler.player.currentPosition,
                )
            }
    }

    /**
     * Publishes a seek.
     *
     * Neither `nowPlaying` nor `controlState` changes when the host drags the scrubber, so without
     * this a seek is simply never sent and guests keep playing from wherever they were.
     *
     * Detected off `SimpleMediaState.Progress`, which both platforms already emit — Metrolist uses
     * Media3's `onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK)`, but that is an Android-only
     * API and Desktop runs mpv. A seek is a position that moved further than wall-clock time could
     * account for; ordinary playback advances roughly in step with it.
     *
     * Sent by a host, or relayed by a duo-mode guest — the decision is [publishOrRelay]'s, and the
     * echo guard below is what keeps the two way traffic from circling.
     */
    private suspend fun publishOrRelaySeeks() {
        var lastProgress = 0L
        var lastAt = 0L
        handler.simpleMediaState.collect { mediaState ->
            val progress = (mediaState as? SimpleMediaState.Progress)?.progress ?: return@collect
            val now = PROCESS_START.elapsedNow().inWholeMilliseconds
            val previous = lastProgress
            val previousAt = lastAt
            lastProgress = progress
            lastAt = now

            val state = repository.room.value
            val canAct = state.inRoom && (state.isHost || repository.pairListeningMode)
            if (!canAct || applyingRemote) return@collect
            // The player's own report of a command we just applied is not ours to announce. A seek
            // does not take effect instantly, so for a moment afterwards the player still reports
            // where it WAS, and a media rebuild reports zero outright — either of which looks
            // exactly like somebody dragging the scrubber. Announcing that is how a room gets
            // dragged BACKWARDS: the follower's stale position goes out as a fresh seek, and the
            // device that actually seeked is pulled to where this one used to be.
            //
            // The suppression ends the moment the player has actually arrived, so a drag that
            // follows a remote command is only ever lost while the command is still settling.
            if (elapsedMs() - lastRemoteApplyAtMs < REMOTE_SETTLE_WINDOW_MS) {
                val target = pendingRemotePosition
                if (target == null || abs(progress - target) > SEEK_DETECT_MS) return@collect
                pendingRemotePosition = null
            }
            if (previousAt == 0L) return@collect

            val elapsed = now - previousAt
            val expected = previous + if (handler.player.isPlaying) elapsed else 0L
            if (kotlin.math.abs(progress - expected) < SEEK_DETECT_MS) return@collect
            // A seek is only ours to announce while it takes us away from where the ROOM expects us
            // to be. Every seek we apply from the room lands exactly there, which is what stops a
            // duo-mode guest from bouncing the host's own seek back at it.
            if (kotlin.math.abs(progress - session.positionAt(state.position, state.isPlaying)) < SEEK_DETECT_MS) return@collect

            Logger.i(TAG, "${if (state.isHost) "Host" else "Duo guest"} intends a seek to $progress (expected ~$expected)")
            publishOrRelay(
                action = PlaybackActions.SEEK,
                trackId = "",
                position = progress,
            )
        }
    }

    // ────────────────── duo mode: the baton, and who is holding it ──────────────────

    /**
     * Answers what duo-mode guests ask of us, with this client's own host authority.
     *
     * Only the host is ever sent one of these — `suggestion_received` goes nowhere else — and it is
     * also the only side of the room the server accepts a `playback_action` or a `transfer_host`
     * from, which is what makes the arrangement work at all.
     *
     * The ask is normally for the ROLE: `transfer_host` to whoever requested it, after which that
     * device drives natively and this one becomes a follower. Nothing is performed on its behalf,
     * because a role change can express everything a command can — and the new host's first publish
     * is its own real state, position and queue included, rather than a command rebuilt here from a
     * carrier that had room for one track and nothing else.
     *
     * A transport command is still honoured, because a guest on a build that predates the handover
     * sends one; it is performed and this client keeps the role, which is the behaviour that build
     * was written against.
     *
     * Duo mode has to be on HERE as well as on the guest. Neither shape means anything between two
     * clients that do not both understand it, and a host that has not opted in should leave the ask
     * alone rather than act on one it never agreed to take. The suggestion is still cleared either
     * way, by the session, so nothing accumulates on the server.
     */
    private suspend fun applyRelayedControlsAsHost() {
        session.relayedControls.collect { control ->
            val state = repository.room.value
            if (!state.inRoom || !state.isHost) return@collect
            if (!repository.pairListeningMode) {
                Logger.w(TAG, "${control.action} dropped — duo mode is off on this host")
                return@collect
            }
            if (control.isTakeOver) {
                // The server refuses `transfer_host` from anyone but the host and refuses to
                // transfer to the host itself, so both of these mean a malformed or stale ask
                // rather than anything worth acting on.
                if (control.fromUserId.isBlank() || control.fromUserId == state.selfUserId) {
                    Logger.w(TAG, "Take-over request with nobody to hand the room to — ignored")
                    return@collect
                }
                Logger.i(TAG, "Handing the room to ${control.fromUserId} — they asked to control it")
                repository.transferHost(control.fromUserId)
                return@collect
            }
            Logger.i(TAG, "Guest relayed ${control.action} @ ${control.positionMs}ms on ${control.trackId} (peer older than the handover)")
            if (control.action == PlaybackActions.CHANGE_TRACK) {
                // The queue the guest sent is not part of the relay — it cannot be, see
                // [ControlRelay] — so the room keeps OURS. That is also what makes the guest's pick
                // land inside the running order instead of replacing it.
                val data = handler.queueData.value?.data
                publishOrRelay(
                    action = PlaybackActions.CHANGE_TRACK,
                    trackId = control.track.id,
                    position = 0L,
                    track = control.track,
                    queue = data?.listTracks.orEmpty().map { it.toTrackInfo() },
                    queueTitle = data?.playlistName.orEmpty(),
                )
                return@collect
            }
            // Loads the track locally as well, by the same route as any other room command: the
            // server broadcasts this publish straight back and the follower applies it.
            publishOrRelay(action = control.action, position = control.positionMs)
        }
    }

    /**
     * Acts on a transport command, from whichever side of the room we are on.
     *
     * A host publishes it. A duo-mode guest cannot — the server answers every `playback_action` from
     * a non-host with `not_host` before it even decodes the payload — so instead of sending the
     * command it ASKS FOR THE ROLE, and publishes everything from then on as the host it has just
     * become. [ControlRelay] sets that arrangement out; the short of it is that the server owns the
     * single-publisher rule, so the only way for a guest to break it is to hold the role itself.
     *
     * Asking rather than relaying the command is what keeps this simple. A relayed command would
     * arrive one round trip behind the guest's own player, would reach the room as a `change_track`
     * at position 0 instead of where the song actually is, and would be re-published by the new host
     * a moment later regardless. None of that happens with the handover, because the new host's first
     * act is to publish its own real state — see [publishCurrentStateOnJoin].
     *
     * [track] is metadata for a CHANGE_TRACK alone: play, pause and seek carry none, and the server
     * fills in its own current track. It is still needed when asking for the role, because the ask
     * travels as a suggestion and the server drops one without an id and a title — so the carrier is
     * whatever the room is on, falling back to whatever this device has loaded. `queue` and
     * `queueTitle` ride only on a host's publish; they do not fit in a carrier.
     */
    private suspend fun publishOrRelay(
        action: String,
        trackId: String = "",
        position: Long = 0L,
        track: TrackInfo? = null,
        queue: List<TrackInfo> = emptyList(),
        queueTitle: String = "",
    ) {
        val state = repository.room.value
        if (!state.inRoom) return
        if (state.isHost) {
            session.sendPlaybackAction(
                action = action,
                trackId = trackId,
                position = position,
                trackInfo = if (action == PlaybackActions.CHANGE_TRACK) track else null,
                queue = queue,
                queueTitle = queueTitle,
            )
            Logger.i(TAG, "Published $action to the room")
            return
        }
        // Only duo mode reaches here — every caller gates on `pairListeningMode`, which is what a
        // guest that has NOT opted in stays silent on. So this is an ask for the role, not a command.
        // The local player is preferred last rather than least: a room with nothing playing yet still
        // has to be takeable over, and this device is holding the song it wants to hear.
        val carrier = track ?: state.currentTrack?.toProtocol() ?: handler.nowPlaying.value?.toTrackInfo()
        if (carrier == null || carrier.id.isBlank()) {
            Logger.w(TAG, "Not asking for control — nothing on hand to carry the request")
            return
        }
        requestBaton(carrier)
    }

    /**
     * Asks the host to hand the room over. See [ControlRelay].
     *
     * Throttled, and not for politeness: an ask only means anything if the host is also in duo mode
     * and actually answers, so an unanswered one leaves this device a guest — and every further
     * transport action would send another. The server caps a room at 100 pending suggestions and a
     * flood would also start being rate-limited, which would take real suggestions down with it.
     *
     * [carrier] is not what the request is about; it exists because the ask travels as a suggestion
     * and the server drops one without a track id and a title.
     */
    private suspend fun requestBaton(carrier: TrackInfo) {
        val now = elapsedMs()
        if (now - batonRequestedAtMs < BATON_REQUEST_COOLDOWN_MS) {
            Logger.i(TAG, "Already asked to control the room — not asking again yet")
            return
        }
        batonRequestedAtMs = now
        Logger.i(TAG, "Duo guest asking the host for control of the room")
        session.requestHost(carrier)
    }

    /** Monotonic milliseconds since process start — wall-clock time cannot go backwards. */
    private fun elapsedMs(): Long = PROCESS_START.elapsedNow().inWholeMilliseconds

    // ─────────────────────────── the buffer barrier ───────────────────────────

    /**
     * Answers `buffer_ready` once the local player has the track the room is waiting on.
     *
     * Nobody in the room hears anything until every member answers, so a client that never sends
     * this silently freezes playback for everyone — including the host.
     */
    private suspend fun answerBufferBarrier() {
        repository.room
            .map { it.waitingFor to it.currentTrack?.id }
            .distinctUntilChanged()
            .collect { (waitingFor, trackId) ->
                val state = repository.room.value
                if (trackId.isNullOrBlank() || !state.inRoom) return@collect
                if (state.selfUserId !in waitingFor) return@collect
                // bufferedPercentage, not isPlaying: the barrier asks whether the track is loaded,
                // and playback is exactly what it is holding back.
                if (handler.player.bufferedPercentage >= READY_BUFFER_PERCENT) {
                    session.reportBufferReady(trackId)
                }
            }
    }

    private fun RoomTrack.toProtocol(): TrackInfo =
        TrackInfo(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = durationMs,
            thumbnail = thumbnail,
        )

    private fun Track.toTrackInfo(): TrackInfo =
        TrackInfo(
            id = videoId,
            title = title,
            artist = artists?.joinToString(", ") { it.name }.orEmpty(),
            album = album?.name.orEmpty(),
            duration = (durationSeconds?.toLong() ?: 0L) * 1000L,
            thumbnail = thumbnails?.lastOrNull()?.url.orEmpty(),
        )

    /** The room carries a videoId plus display metadata; the guest resolves its own stream. */
    private fun GenericMediaItem.toTrackInfo(): TrackInfo =
        TrackInfo(
            id = mediaId,
            title = metadata.title.orEmpty(),
            artist = metadata.artist.orEmpty(),
            album = metadata.albumTitle.orEmpty(),
            duration = handler.player.duration.coerceAtLeast(0L),
            thumbnail = metadata.artworkUri.orEmpty(),
        )

    private companion object {
        /** Monotonic reference for telling a seek apart from ordinary playback advancing. */
        val PROCESS_START = TimeSource.Monotonic.markNow()

        /**
         * Metrolist's own hard-sync threshold (`HARD_SYNC_THRESHOLD_MS`). Below it a seek costs
         * more in stutter than it buys in sync; above it the room is audibly apart.
         */
        const val SEEK_TOLERANCE_MS = 750L

        /**
         * A jump larger than this is a seek rather than playback advancing. Comfortably above the
         * progress tick interval so ordinary drift never trips it.
         */
        const val SEEK_DETECT_MS = 2_500L
        const val READY_BUFFER_PERCENT = 5

        /**
         * How long an applied play/pause stays attributable to the room rather than to the user.
         *
         * Long enough to cover the player's own reporting delay after a remote command, short enough
         * that pressing the button twice is still two commands.
         */
        const val REMOTE_ECHO_WINDOW_MS = 1_500L

        /**
         * How long after applying a room command the local player's position reports are still its
         * own settling rather than the user moving the playhead. See [publishOrRelaySeeks].
         *
         * Longer than a seek takes to land, because a track rebuild has to complete and seek
         * afterwards; shorter than the interval between two deliberate drags, so a real seek inside
         * it is a rare loss rather than a swallowed one.
         */
        const val REMOTE_SETTLE_WINDOW_MS = 1_500L

        /** How often a guest may ask for the role, so an unanswered ask cannot pile up. */
        const val BATON_REQUEST_COOLDOWN_MS = 5_000L
    }
}
