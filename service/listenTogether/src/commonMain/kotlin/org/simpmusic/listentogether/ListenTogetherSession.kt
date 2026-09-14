package org.simpmusic.listentogether

import com.greyhatguy007.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

private const val TAG = "ListenTogetherSession"

/** Where the socket is, independent of whether a room has been joined. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val serverVersion: String) : ConnectionState

    /** Retries are exhausted or the server refused us; the user has to act. */
    data class Failed(val reason: String) : ConnectionState
}

/** One person in the room, as the UI needs them. */
data class RoomMember(
    val userId: String,
    val username: String,
    val isHost: Boolean,
    val isConnected: Boolean,
    /** True while this member has not answered `buffer_ready` for the current track. */
    val isBuffering: Boolean = false,
)

data class PendingJoin(
    val userId: String,
    val username: String,
)

data class PendingSuggestion(
    val suggestionId: String,
    val fromUsername: String,
    val track: TrackInfo,
)

data class ListenTogetherState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Null until a room is created or joined. */
    val roomCode: String? = null,
    val selfUserId: String = "",
    val isHost: Boolean = false,
    val members: List<RoomMember> = emptyList(),
    val joinRequests: List<PendingJoin> = emptyList(),
    val suggestions: List<PendingSuggestion> = emptyList(),
    val currentTrack: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    /**
     * The room's queue, in the host's order.
     *
     * Without this a guest only ever knows the *current* track, so the moment it ends they carry
     * on with whatever their own player had queued — which is everyone listening to something
     * different one track later.
     */
    val queue: List<TrackInfo> = emptyList(),
    /** Non-empty while the room is held at the buffer barrier. */
    val waitingFor: List<String> = emptyList(),
    /** Server time the last transport command was captured at; 0 when unknown. */
    val lastActionServerTime: Long = 0L,
    /**
     * The code we asked to join and have not heard back about.
     *
     * Joining is not immediate — the host has to approve it — and without this the UI has nothing
     * to show between sending `join_room` and `join_approved` arriving, so a wrong code and a host
     * who simply has not looked at their phone are indistinguishable: both look like nothing
     * happened.
     */
    val pendingJoinCode: String? = null,
    /** Set once and cleared by the UI, so a transient failure cannot wedge the screen. */
    val error: String? = null,
) {
    val inRoom: Boolean get() = roomCode != null
    val isConnected: Boolean get() = connection is ConnectionState.Connected

    /** Names, not ids — "Waiting for Long" is the only useful phrasing of the barrier. */
    val waitingForNames: List<String>
        get() = waitingFor.mapNotNull { id -> members.firstOrNull { it.userId == id }?.username }
}

/**
 * The room state machine.
 *
 * Consumes [ListenTogetherClient.events] and never touches a frame, which is what keeps it
 * testable without a socket and identical on Android and Desktop — the two platforms run entirely
 * separate player handlers, so anything that reached into playback from here would have to be
 * written twice.
 */
/**
 * Whether this command tells us nothing about where the song is or whether it is running.
 *
 * True of exactly one case: a `change_track` naming the song we are already on. The server writes
 * both fields itself on every change_track — `ActionChangeTrack` sets `Position = 0` and
 * `IsPlaying = false` before rebroadcasting, discarding whatever the sender put in the payload — so
 * on a track that has not changed, neither number came from anyone. Acting on them rewinds the song
 * to the top and rebuilds the media item, and the device that goes through that then reports the
 * rewind it just suffered, which travels back as a fresh seek. That is the whole of the "seeking
 * restarts the music for everyone" bug, so this is deliberately a rule rather than a judgement
 * call at the call site.
 *
 * A change_track for a song we do NOT have is different, and the zero there is the right answer: a
 * new track really does start at the top. Neither is it true of `seek`, `play` or `pause` — those
 * carry a position the server applies and re-sends, so even one that names the current song is
 * saying something real.
 */
internal fun PlaybackActionPayload.saysNothingAboutPositionOrPlayState(currentTrackId: String?): Boolean =
    action == PlaybackActions.CHANGE_TRACK && trackInfo?.id != null && trackInfo.id == currentTrackId

class ListenTogetherSession(
    private val client: ListenTogetherClient,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + dispatcher

    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()

    private var pump: Job? = null

    /** Host-side conveniences from settings; both default off, matching the design's toggles. */
    var autoApproveJoins: Boolean = false
    var autoApproveSuggestions: Boolean = false

    /** Opens the socket. Joining a room is a separate, later step. */
    fun connect() {
        if (pump == null) {
            pump = launch { client.events.collect(::onEvent) }
        }
        _state.update { it.copy(connection = ConnectionState.Connecting, error = null) }
        client.connect()
    }

    fun disconnect() {
        client.disconnect()
        _state.value = ListenTogetherState()
    }

    fun createRoom(username: String) =
        launch {
            pendingUsername = username.trim()
            client.send(MessageTypes.CREATE_ROOM, CreateRoomPayload(username = pendingUsername))
        }

    fun joinRoom(
        roomCode: String,
        username: String,
    ) = launch {
        pendingUsername = username.trim()
        val code = roomCode.trim().uppercase()
        _state.update { it.copy(pendingJoinCode = code, error = null) }
        val sent = client.send(MessageTypes.JOIN_ROOM, JoinRoomPayload(roomCode = code, username = pendingUsername))
        if (!sent) {
            _state.update { it.copy(pendingJoinCode = null, error = "Not connected") }
        }
    }

    /** Gives up on a join that has not been answered. Local only — the server needs no message. */
    fun cancelJoin() = _state.update { it.copy(pendingJoinCode = null) }

    fun leaveRoom() =
        launch {
            client.send(MessageTypes.LEAVE_ROOM, LeaveRoomPayload())
            // The server sends nothing back for leave_room, so the local state is cleared here —
            // waiting for a confirmation that never arrives would leave the room UI on screen.
            _state.update {
                it.copy(
                    roomCode = null,
                    isHost = false,
                    members = emptyList(),
                    joinRequests = emptyList(),
                    suggestions = emptyList(),
                    currentTrack = null,
                    waitingFor = emptyList(),
                )
            }
        }

    fun approveJoin(userId: String) =
        launch {
            client.send(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId = userId))
            _state.update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.userId == userId }) }
        }

    fun rejectJoin(userId: String) =
        launch {
            client.send(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId = userId))
            _state.update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.userId == userId }) }
        }

    fun approveSuggestion(suggestionId: String) =
        launch {
            client.send(MessageTypes.APPROVE_SUGGESTION, ApproveSuggestionPayload(suggestionId = suggestionId))
            dropSuggestion(suggestionId)
        }

    fun rejectSuggestion(suggestionId: String) =
        launch {
            client.send(MessageTypes.REJECT_SUGGESTION, RejectSuggestionPayload(suggestionId = suggestionId))
            dropSuggestion(suggestionId)
        }

    fun kickUser(userId: String) =
        launch { client.send(MessageTypes.KICK_USER, KickUserPayload(userId = userId)) }

    fun transferHost(userId: String) =
        launch { client.send(MessageTypes.TRANSFER_HOST, TransferHostPayload(newHostId = userId)) }

    fun suggestTrack(track: TrackInfo) =
        launch { client.send(MessageTypes.SUGGEST_TRACK, SuggestTrackPayload(trackInfo = track)) }

    /**
     * Publishes one transport command to the room. Host only — the server ignores it from a guest.
     *
     * `capturedAtServerTime` is what lets a late-arriving PLAY still land on the right position:
     * the receiver advances [position] by however long the frame spent in flight, measured on the
     * SERVER's clock rather than its own. Sending 0 (an uncalibrated clock) is safe — the receiver
     * treats it as "unknown" and uses the raw position.
     */
    fun sendPlaybackAction(
        action: String,
        trackId: String,
        position: Long,
        trackInfo: TrackInfo?,
        queue: List<TrackInfo> = emptyList(),
        queueTitle: String = "",
    ) = launch {
        client.send(
            MessageTypes.PLAYBACK_ACTION,
            PlaybackActionPayload(
                action = action,
                trackId = trackId,
                position = position,
                trackInfo = trackInfo,
                // Carried on the SAME message as the track deliberately. Sent separately they are
                // two independent sends with no ordering guarantee, and a queue arriving after the
                // track means the guest has already committed to a one-track queue — it then plays
                // its own next song and the room splits one track later.
                queue = queue,
                queueTitle = queueTitle,
                capturedAtServerTime = client.serverNow() ?: 0L,
            ),
        )
    }

    /** Publishes the whole queue. Host only; guests take the host's order verbatim. */
    fun sendQueue(
        tracks: List<TrackInfo>,
        queueTitle: String,
    ) = launch {
        client.send(
            MessageTypes.PLAYBACK_ACTION,
            PlaybackActionPayload(
                action = PlaybackActions.SYNC_QUEUE,
                queue = tracks,
                queueTitle = queueTitle,
                capturedAtServerTime = client.serverNow() ?: 0L,
            ),
        )
    }

    // ──────────────────────── duo mode: a guest's command, relayed ────────────────────────

    private val _relayedControls =
        MutableSharedFlow<RelayedControl>(
            extraBufferCapacity = RELAY_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * What a duo-mode guest asked THIS client to do. See [ControlRelay].
     *
     * Only a host ever receives one — `suggestion_received` goes to the host alone, which is also
     * the only side of the room the server lets publish. Normally that is a request for the host
     * role ([RelayedControl.isTakeOver]), which this client's owner grants by `transfer_host`ing to
     * [RelayedControl.fromUserId]; a transport command is only ever seen from an older peer, which
     * predates the handover. A buffer rather than a rendezvous, since the frame loop must not be
     * held up by whoever is applying them, but a small one: these are transport commands, and only
     * the newest few are still worth acting on.
     */
    val relayedControls: SharedFlow<RelayedControl> = _relayedControls.asSharedFlow()

    /**
     * Relays [action] to the host on a suggestion it recognises.
     *
     * Only a duo-mode guest calls this, and it is not a suggestion — it is either a transport command
     * that has to travel as one ([ControlRelay] sets the whole arrangement out) or, normally, the
     * request for the host role itself ([requestHost]). [track] is therefore both the carrier and, for
     * the older command shape, the subject: the song being asked for on a track change, and the track
     * the room is on for play, pause and seek. It MUST be a well-formed track — the server drops a
     * suggestion without an id and a title, and the ask would then never reach the host.
     */
    fun requestControl(
        action: String,
        position: Long,
        track: TrackInfo,
    ) = launch {
        client.send(
            MessageTypes.SUGGEST_TRACK,
            SuggestTrackPayload(
                trackInfo = track.copy(suggestedBy = ControlRelay.encode(action, position)),
            ),
        )
    }

    /**
     * Asks the host to hand the room over, so this client can drive it with real authority.
     *
     * The whole of duo mode rests on this: a guest cannot publish a `playback_action` at all, so
     * rather than have every command relayed one at a time, it asks for the ROLE once and then
     * drives natively — queue included. [track] is only the carrier that makes the ask a well-formed
     * suggestion (see [ControlRelay]); it is not what the request is about.
     */
    fun requestHost(track: TrackInfo) = requestControl(ControlRelay.ACTION_TAKE_HOST, 0L, track)

    /** See `ServerClock.positionAt` — corrects a room position for time spent in flight. */
    fun positionAt(
        position: Long,
        isPlaying: Boolean,
    ): Long = client.positionAt(position, _state.value.lastActionServerTime, isPlaying)

    /**
     * Asks the server for the room's current state.
     *
     * This is how a guest rejoins the room's timeline after driving its own transport: the server
     * answers `sync_state` with the live position, so pressing play lands where everyone else
     * actually is rather than where this device happened to stop.
     */
    fun requestSync() =
        launch {
            client.send(MessageTypes.REQUEST_SYNC, null)
        }

    /** Answers the buffer barrier for [trackId]; until every member does, nobody hears anything. */
    fun reportBufferReady(trackId: String) =
        launch { client.send(MessageTypes.BUFFER_READY, BufferReadyPayload(trackId = trackId)) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun release() {
        client.release()
        pump = null
    }

    private fun dropSuggestion(id: String) =
        _state.update { s -> s.copy(suggestions = s.suggestions.filterNot { it.suggestionId == id }) }

    private fun onEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.Connected ->
                _state.update { it.copy(connection = ConnectionState.Connected(event.serverVersion)) }

            is ListenTogetherEvent.ClockReady -> Unit

            is ListenTogetherEvent.Disconnected ->
                _state.update {
                    if (event.willRetry) {
                        it.copy(connection = ConnectionState.Connecting)
                    } else {
                        // Losing the socket loses the room with it; leaving the room UI up would
                        // offer controls that silently do nothing.
                        ListenTogetherState(connection = ConnectionState.Failed(event.reason ?: "Disconnected"))
                    }
                }

            is ListenTogetherEvent.Message -> onMessage(event.type, event.payload)
        }
    }

    private fun onMessage(
        type: String,
        payload: Any?,
    ) {
        when (type) {
            MessageTypes.ROOM_CREATED -> {
                val p = payload as? RoomCreatedPayload ?: return
                _state.update {
                    it.copy(
                        roomCode = p.roomCode,
                        selfUserId = p.userId,
                        isHost = true,
                        // The server sends no member list for a brand-new room: the host is alone
                        // in it, and USER_JOINED carries everyone who arrives afterwards.
                        members = listOf(RoomMember(p.userId, pendingUsername, isHost = true, isConnected = true)),
                    )
                }
            }

            MessageTypes.JOIN_APPROVED -> {
                val p = payload as? JoinApprovedPayload ?: return
                // Ask for the current state explicitly: JoinApproved carries whatever the server
                // last heard, which is nothing at all if the host has not issued a command yet.
                launch { client.send(MessageTypes.REQUEST_SYNC, null) }
                _state.update {
                    it.copy(
                        roomCode = p.roomCode,
                        selfUserId = p.userId,
                        isHost = false,
                        pendingJoinCode = null,
                        members = p.state?.users.orEmpty().map { u -> u.toMember() },
                        queue = p.state?.queue.orEmpty(),
                        currentTrack = p.state?.currentTrack,
                        isPlaying = p.state?.isPlaying ?: false,
                        position = p.state?.position ?: 0L,
                    )
                }
            }

            MessageTypes.JOIN_REJECTED ->
                _state.update {
                    it.copy(
                        pendingJoinCode = null,
                        error = (payload as? JoinRejectedPayload)?.reason?.ifBlank { null } ?: "The host declined",
                    )
                }

            MessageTypes.JOIN_REQUEST -> {
                val p = payload as? JoinRequestPayload ?: return
                if (autoApproveJoins) {
                    approveJoin(p.userId)
                    return
                }
                _state.update { s ->
                    if (s.joinRequests.any { it.userId == p.userId }) {
                        s
                    } else {
                        s.copy(joinRequests = s.joinRequests + PendingJoin(p.userId, p.username))
                    }
                }
            }

            MessageTypes.USER_JOINED -> {
                val p = payload as? UserJoinedPayload ?: return
                _state.update { s ->
                    if (s.members.any { it.userId == p.userId }) {
                        s
                    } else {
                        s.copy(members = s.members + RoomMember(p.userId, p.username, isHost = false, isConnected = true))
                    }
                }
            }

            MessageTypes.USER_LEFT -> {
                val p = payload as? UserLeftPayload ?: return
                _state.update { s -> s.copy(members = s.members.filterNot { it.userId == p.userId }) }
            }

            MessageTypes.USER_DISCONNECTED -> {
                val p = payload as? UserDisconnectedPayload ?: return
                _state.update { s -> s.copy(members = s.members.map { if (it.userId == p.userId) it.copy(isConnected = false) else it }) }
            }

            MessageTypes.USER_RECONNECTED -> {
                val p = payload as? UserReconnectedPayload ?: return
                _state.update { s -> s.copy(members = s.members.map { if (it.userId == p.userId) it.copy(isConnected = true) else it }) }
            }

            MessageTypes.HOST_CHANGED -> {
                val p = payload as? HostChangedPayload ?: return
                _state.update { s ->
                    s.copy(
                        isHost = p.newHostId == s.selfUserId,
                        members = s.members.map { it.copy(isHost = it.userId == p.newHostId) },
                    )
                }
            }

            MessageTypes.KICKED ->
                _state.value =
                    ListenTogetherState(
                        connection = _state.value.connection,
                        error = (payload as? KickedPayload)?.reason ?: "Removed from the room",
                    )

            MessageTypes.BUFFER_WAIT -> {
                val p = payload as? BufferWaitPayload ?: return
                _state.update { s ->
                    s.copy(
                        waitingFor = p.waitingFor,
                        members = s.members.map { it.copy(isBuffering = it.userId in p.waitingFor) },
                    )
                }
            }

            MessageTypes.BUFFER_COMPLETE ->
                _state.update { s -> s.copy(waitingFor = emptyList(), members = s.members.map { it.copy(isBuffering = false) }) }

            MessageTypes.SYNC_STATE -> {
                val p = payload as? SyncStatePayload ?: return
                _state.update {
                    it.copy(
                        currentTrack = p.currentTrack,
                        isPlaying = p.isPlaying,
                        position = p.position,
                        // Only replace the queue when the server actually sent one — sync_state
                        // with an empty queue means "nothing to say", not "the queue is empty".
                        queue = p.queue.ifEmpty { it.queue },
                    )
                }
            }

            MessageTypes.SYNC_PLAYBACK, MessageTypes.PLAYBACK_ACTION -> {
                val p = payload as? PlaybackActionPayload ?: return
                _state.update {
                    // `change_track` is NOT a statement about position or play state. The server
                    // sets `Position = 0` and `IsPlaying = false` itself on every one of them and
                    // rebroadcasts exactly that — whatever the sender put in the payload is
                    // discarded (see `ActionChangeTrack` in metroserver's playback.go, which ends
                    // `p.Position = 0`). For a track we do not have yet that zero is the right
                    // answer, since a new track really does start at the top.
                    //
                    // For the track we are ALREADY on it is only an artefact, and obeying it is
                    // expensive: the playhead rewinds to the start, the media item is rebuilt, and
                    // the listener then announces the rewind it just suffered as a fresh seek —
                    // which is how one person seeking ended up restarting the song for both. The
                    // song has not changed, so nothing in this payload is worth acting on but the
                    // queue, and the state is left alone. When it truly is a redundant command the
                    // three fields below are unchanged, the state compares equal, and this emits
                    // nothing at all.
                    val forcedByProtocol = p.saysNothingAboutPositionOrPlayState(it.currentTrack?.id)
                    it.copy(
                        currentTrack = p.trackInfo ?: it.currentTrack,
                        isPlaying =
                            when {
                                forcedByProtocol -> it.isPlaying
                                p.action == PlaybackActions.PAUSE -> false
                                p.action == PlaybackActions.PLAY -> true
                                else -> it.isPlaying
                            },
                        position =
                            if (p.action == PlaybackActions.SYNC_QUEUE || forcedByProtocol) {
                                it.position
                            } else {
                                p.position
                            },
                        queue = p.queue.ifEmpty { it.queue },
                        // The position we kept was captured at the OLDER command's time, so it has
                        // to keep that timestamp too or the flight correction would be measured
                        // from an action that never moved it.
                        lastActionServerTime = if (forcedByProtocol) it.lastActionServerTime else p.capturedAtServerTime,
                    )
                }
            }

            MessageTypes.SUGGESTION_RECEIVED -> {
                val p = payload as? SuggestionReceivedPayload ?: return
                val track = p.trackInfo ?: return
                // A duo-mode guest's transport command rather than a suggestion. Only the host is
                // ever sent one, so it is also the only client that can clear it: `reject_suggestion`
                // is host-only, and without it every relayed command would sit in
                // `room.PendingSuggestions` until the 100-entry cap started refusing real ones.
                // Checked BEFORE autoApproveSuggestions, which would otherwise queue the track the
                // command carries instead of performing the command.
                // fromUserId rides the envelope rather than the payload: it is what `transfer_host`
                // needs, and the carrier has no room for it.
                val relay = ControlRelay.decode(track, p.fromUserId)
                if (relay != null) {
                    launch {
                        client.send(MessageTypes.REJECT_SUGGESTION, RejectSuggestionPayload(suggestionId = p.suggestionId))
                    }
                    _relayedControls.tryEmit(relay)
                    return
                }
                if (autoApproveSuggestions) {
                    approveSuggestion(p.suggestionId)
                    return
                }
                _state.update { s ->
                    if (s.suggestions.any { it.suggestionId == p.suggestionId }) {
                        s
                    } else {
                        s.copy(suggestions = s.suggestions + PendingSuggestion(p.suggestionId, p.fromUsername, track))
                    }
                }
            }

            MessageTypes.ERROR -> {
                val p = payload as? ErrorPayload ?: return
                // `rate_limited` is about one message, not the session, and showing it would put an
                // alarming banner up for something the user cannot act on.
                if (p.code != "rate_limited") {
                    Logger.w(TAG, "Server error ${p.code}: ${p.message}")
                    // An error while waiting to be let in ends that wait — most often a bad code.
                    _state.update {
                        it.copy(pendingJoinCode = null, error = p.message.ifBlank { p.code })
                    }
                }
            }
        }
    }

    /**
     * The name the user typed, kept so ROOM_CREATED can name the host.
     *
     * The server echoes the room code and user id back but not the username it was given, and the
     * host has to appear in their own member list like everyone else.
     */
    private var pendingUsername: String = ""

    private companion object {
        /**
         * Relayed commands are applied one at a time and each supersedes the last: a seek or a
         * pause that arrives behind two newer ones is worse than useless, so the buffer is small
         * and overflow drops the OLDEST rather than stalling the reader — which is also why the
         * flow is DROP_OLDEST rather than the default: `tryEmit` on a SUSPEND flow discards the
         * NEW command instead, i.e. exactly the one worth keeping.
         */
        const val RELAY_BUFFER = 16
    }
}

private fun UserInfo.toMember() =
    RoomMember(
        userId = userId,
        username = username,
        isHost = isHost,
        isConnected = isConnected,
    )
