package org.simpmusic.listentogether

/**
 * A command a duo-mode guest asked the host to perform ([ControlRelay] carries it).
 *
 * [track] is the song the command is about: on a track change, the one being asked for; on play,
 * pause and seek, the one the room is already on, since the protocol carries no metadata for those
 * three. It is also what makes the suggestion well-formed — the server drops one without an id and
 * a title — so it is never empty. Its `suggestedBy` is cleared, so the host can publish it as an
 * ordinary track.
 *
 * [trackId] is that track's own id and nothing more — the command targets [track], and this exists
 * so a log line or a future staleness check can name the track without reaching into it.
 *
 * [fromUserId] is who asked, which is the whole answer to a take-over request: the host has to name
 * a new host, and `transfer_host` takes a user id.
 */
data class RelayedControl(
    val action: String,
    val positionMs: Long,
    val trackId: String,
    val track: TrackInfo,
    val fromUserId: String = "",
) {
    /** Whether this asks for the host role rather than for a transport command. See [ControlRelay]. */
    val isTakeOver: Boolean get() = action == ControlRelay.ACTION_TAKE_HOST
}

/**
 * How a duo-mode guest's intent reaches the host on a server that allows only one of them to
 * control playback.
 *
 * metroserver refuses every `playback_action` that does not come from the host — `handlePlaybackAction`
 * answers `not_host` ("Only the host can control playback") before it even decodes the payload — and
 * `listentogether.proto` has no role or permission field with which a client could ask for anything
 * else. `handleTransferHost` is host-only too, so a guest cannot simply seize the role.
 *
 * Of the three messages a guest in a room MAY send (`suggest_track`, `buffer_ready`, `request_sync`),
 * only `suggest_track` is delivered to another client, and it is delivered to the host. So the ask
 * rides on one: [encode] puts it in a suggestion's `suggestedBy`, the host recognises the marker,
 * `transfer_host`s the role to whoever asked, and answers `reject_suggestion` to clear the entry —
 * `room.PendingSuggestions` is capped at 100, so leaking one per request would eventually start
 * refusing real suggestions.
 *
 * **The baton.** The guest asks for the ROLE, not for one command, and once it holds it, it drives
 * natively: real `playback_action` payloads, the queue included, no marker and no 50-character
 * carrier. That is why [ACTION_TAKE_HOST] is the only thing a guest normally sends, and it is the
 * reason there is no echo problem — exactly one side of the room is ever publishing, and which side
 * that is comes straight from the server's own `host_changed`.
 *
 * [PlaybackActions.CHANGE_TRACK] and friends stay decodable because a guest on an older build still
 * sends them. A host performs those and keeps the role; only [ACTION_TAKE_HOST] moves it.
 *
 * Everything else about a relayed suggestion stays honest: id, title, artist and duration are the
 * track the message is about, so a host that does not understand the marker — a Metrolist client,
 * or SimpMusic without duo mode — sees an ordinary song suggestion rather than garbage.
 *
 * **The carrier is capped at [MAX_ENCODED_LENGTH] characters.** The server trims `suggestedBy` to
 * `MaxUsernameLength` (50) in `sanitizeTrackInfo`, and a payload that outgrew that would arrive cut
 * short — which is why the field order below is what it is, and why
 * `everyCommandFitsInTheFieldTheServerAllows` pins the budget.
 */
object ControlRelay {
    /** Versioned, so a future payload cannot be read as this one. */
    private const val MARKER = "SMPCTL1"

    private const val SEPARATOR = '|'
    private const val FIELD_COUNT = 3

    /**
     * Asks the host to hand the room over. The only action with a side effect beyond the room's
     * playback state, and the only one a current guest normally sends.
     *
     * Deliberately NOT a `PlaybackActions` constant: it is never a `playback_action`, and a value
     * that looked like one could be published into a payload the server would try to execute.
     */
    const val ACTION_TAKE_HOST = "take_host"

    /** `suggestedBy` is trimmed to this by the server, so a longer payload arrives corrupted. */
    const val MAX_ENCODED_LENGTH = 50

    /**
     * The commands a guest may relay.
     *
     * Deliberately not every action: the queue is the host's to order, and a whole queue would not
     * fit in the carrier anyway. Anything outside this set is refused by [decode], so a malformed
     * or hostile payload cannot reach a command the host would otherwise never receive from a guest.
     */
    val RELAYABLE_ACTIONS: Set<String> =
        setOf(
            PlaybackActions.CHANGE_TRACK,
            PlaybackActions.PLAY,
            PlaybackActions.PAUSE,
            PlaybackActions.SEEK,
            ACTION_TAKE_HOST,
        )

    /**
     * `SMPCTL1|<positionMs>|<action>`.
     *
     * The ORDER is load-bearing. The server truncates this field, and a truncated command has to be
     * refused rather than obeyed: a position cut in half decodes as 12ms where 1234567ms was meant,
     * which is a perfectly plausible command pointing somewhere else entirely. Putting the action
     * last is what makes that impossible — no action name is a prefix of another, so a cut one is
     * not in [RELAYABLE_ACTIONS] and [decode] answers null. A position cut anywhere fails even
     * earlier, as a field-count mismatch.
     *
     * Note that [ACTION_TAKE_HOST] keeps the same shape as the rest — `SMPCTL1|0|take_host` — even
     * though its position means nothing, because a second payload layout would be a second thing to
     * keep truncation-safe.
     *
     * The track is deliberately NOT encoded here: it travels as the suggestion itself, so repeating
     * its id would only lengthen the payload and hand truncation another field to corrupt.
     */
    fun encode(
        action: String,
        positionMs: Long,
    ): String = listOf(MARKER, positionMs.toString(), action).joinToString(SEPARATOR.toString())

    /** Encodes a request for the host role. See [ACTION_TAKE_HOST]. */
    fun encodeTakeHost(): String = encode(ACTION_TAKE_HOST, 0L)

    /**
     * The relayed command in [track]'s `suggestedBy`, or null when this is an ordinary suggestion.
     *
     * Returning null is the common case, not the error case: an unmarked suggestion is what a guest
     * without duo mode sends, and a mangled one — truncated by the server, or from a build that
     * spells the payload differently — must fall through to the suggestion path rather than act on a
     * command nobody can read.
     *
     * [fromUserId] comes from the suggestion envelope rather than the payload, because it is the one
     * thing the carrier has no room for and the one thing `transfer_host` cannot do without.
     */
    fun decode(
        track: TrackInfo?,
        fromUserId: String = "",
    ): RelayedControl? {
        val info = track ?: return null
        val raw = info.suggestedBy.trim()
        if (!raw.startsWith("$MARKER$SEPARATOR")) return null
        val fields = raw.split(SEPARATOR, limit = FIELD_COUNT)
        if (fields.size != FIELD_COUNT) return null
        val position = fields[1].toLongOrNull() ?: return null
        val action = fields[2]
        if (action !in RELAYABLE_ACTIONS) return null
        return RelayedControl(
            action = action,
            positionMs = position.coerceAtLeast(0L),
            trackId = info.id,
            track = info.copy(suggestedBy = ""),
            fromUserId = fromUserId,
        )
    }
}
