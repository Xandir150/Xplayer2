package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import org.json.JSONObject

/**
 * What the transport must do next. Effects are returned in the order they must happen.
 *
 * [Send] lines carry no trailing newline — the control channel is newline-delimited JSON and the
 * transport owns the framing.
 */
sealed interface PairingEffect {

    /** Write this line to the control channel. */
    data class Send(val line: String) : PairingEffect

    /**
     * The 6-digit code is ready: show it next to [serverName] and wait for the user.
     *
     * [serverName] is the name the *server* put in its `pair_pubkey`, not the one discovery
     * advertised — that string is inside the transcript, so it's the one bound to the code the user
     * is about to compare. A spoofed discovery name is corrected here.
     */
    data class ShowSas(val sas: String, val serverName: String, val serverId: String) : PairingEffect

    /**
     * Write this pairing to [PcLinkPairingStore] now. Emitted the moment the server's confirmation
     * tag verifies — the server persisted before sending it, so from here on both sides are paired
     * whatever happens to the connection.
     */
    data class Persist(val serverId: String, val serverName: String, val ltk: ByteArray) : PairingEffect {
        override fun equals(other: Any?): Boolean = other is Persist && serverId == other.serverId &&
            serverName == other.serverName && ltk.contentEquals(other.ltk)

        override fun hashCode(): Int = (serverId.hashCode() * 31 + serverName.hashCode()) * 31 +
            ltk.contentHashCode()

        override fun toString(): String = "Persist(serverId=${serverId.take(8)}…, serverName=$serverName)"
    }

    /** The session reached a terminal state. No further effects will be produced. */
    data class Finished(val outcome: PairingOutcome) : PairingEffect

    /**
     * Close the control connection. Emitted after every failure — the protocol makes each pairing
     * or authentication attempt cost one TCP connection, which is what bounds SAS/proof guessing to
     * a single try. Deliberately *not* emitted after success: an authenticated connection is the
     * one the session was for.
     */
    data object Close : PairingEffect
}

sealed interface PairingOutcome {

    /**
     * Authenticated. [videoToken] is the one-time token from `auth_ok`, or null when the pairing
     * completed but the token never arrived (the pairing is still valid — the server persisted
     * before its `pair_confirm`).
     */
    data class Success(
        val serverId: String,
        val serverName: String,
        val videoToken: String?,
        val paired: Boolean
    ) : PairingOutcome

    data class Failure(val reason: PairingFailure) : PairingOutcome
}

/** Why a session ended without an authenticated connection. Drives the error copy in the UI. */
enum class PairingFailure {
    /** The user tapped Cancel on this phone. Nothing to report back to them. */
    DECLINED_LOCALLY,

    /** The PC's user cancelled — including the normal "the codes didn't match, so I hit Cancel". */
    DECLINED_BY_PC,

    /** Another device is pairing with that PC right now (`busy`). */
    PC_BUSY,

    /** Too many attempts against that PC (`rate_limited`). */
    RATE_LIMITED,

    /** The PC doesn't speak our `pairingVersion`. */
    VERSION_UNSUPPORTED,

    /** Nobody answered in time. */
    TIMEOUT,

    /** Malformed, out-of-order, or unusable message — a broken or hostile peer. */
    PROTOCOL,

    /** Key confirmation failed: the two sides derived different keys. Nothing was stored. */
    CONFIRM_MISMATCH,

    /**
     * `auth_fail: unknown_client` — the PC has forgotten this phone. The UI may offer to pair
     * again, but only behind an explicit tap: re-pairing must always resurface the code ceremony
     * (§8.4), so a MITM can't silently force one.
     */
    UNKNOWN_TO_PC,

    /**
     * The PC failed to prove it holds our key, or rejected our proof (`bad_proof`). Do NOT offer
     * re-pairing: this is an impostor or corruption signal, not a forgotten pairing.
     */
    AUTH_FAILED,

    /** The connection dropped mid-ceremony. */
    CONNECTION_LOST
}

/**
 * The phone-side (control-channel *client*) state machine for PC Link pairing and reconnect
 * authentication, implementing `xplayer-link-server/docs/pairing-design.md` §4, §5, §8.2 and §8.3.
 *
 * Deliberately transport-free: it consumes received lines, user taps and a clock, and produces
 * [PairingEffect]s. Everything about it is therefore exercised by plain JVM tests — including the
 * adversarial cases (tampered key, forged tag, replay, out-of-order, timeout) that are miserable to
 * provoke against a real socket. [PcLinkPairingClient] is the thin socket wrapper that drives it.
 *
 * Two modes, one class, because they are two branches of the same connection lifecycle:
 *
 * ```
 * pair():         pair_start -> pair_pubkey(server) -> pair_pubkey(client)
 *                            -> [user compares 6 digits] -> pair_confirm(client)
 *                            -> pair_confirm(server) -> persist -> auth_ok
 * authenticate(): auth_challenge -> auth_response(server, proves first)
 *                            -> auth_response(client) -> auth_ok
 * ```
 *
 * The caller must have sent `hello` with the same [protocolVersion] before [start] — that number is
 * inside the pairing transcript, so the two must agree or the codes won't match.
 *
 * Not thread-safe: drive it from one thread (the transport's read loop).
 */
class PairingSession private constructor(
    private val mode: Mode,
    private val identity: PcLinkPairingCrypto.Identity,
    private val protocolVersion: Int,
    private val clock: () -> Long,
    private val random: (Int) -> ByteArray
) {

    private sealed interface Mode {
        /** First-time pairing. [clientName] is what the PC shows and what the transcript binds. */
        data class Pair(val clientName: String) : Mode

        /**
         * Reconnect authentication against already-stored pairings.
         *
         * A *list*, not one pairing: discovery doesn't carry `serverId` yet, so the best the caller
         * can do is guess by address. Verifying the server's proof against each candidate costs
         * nothing and gives away nothing (we only ever verify), and it means a paired PC whose
         * DHCP address changed still authenticates instead of looking like an impostor. Whichever
         * key verifies identifies the PC.
         */
        data class Authenticate(val candidates: List<PcLinkPairing>) : Mode
    }

    enum class State {
        /** Nothing sent yet. */
        IDLE,

        /** `pair_start` sent; waiting for the server's reveal. */
        COMMIT_SENT,

        /** Both sides revealed, code displayed; waiting for the user's Pair/Cancel. */
        AWAITING_USER,

        /** User accepted, `pair_confirm` sent; waiting for the PC's user and its tag. */
        CONFIRM_SENT,

        /** Pairing stored; waiting for `auth_ok`. */
        AWAITING_AUTH_OK,

        /** `auth_challenge` sent; waiting for the server to prove itself. */
        CHALLENGE_SENT,

        /** Server verified and our proof sent; waiting for `auth_ok`. */
        RESPONSE_SENT,

        /** Terminal. */
        DONE
    }

    var state: State = State.IDLE
        private set

    private var clientNonce: ByteArray = ByteArray(0)
    private var serverNonce: ByteArray = ByteArray(0)
    private var serverPub: ByteArray = ByteArray(0)
    private var serverName: String = ""
    private var serverId: String = ""
    private var sessionKeys: PcLinkPairingCrypto.SessionKeys? = null
    private var transcriptHash: ByteArray = ByteArray(0)
    private var pairedRecord: PairingEffect.Persist? = null
    private var matchedPairing: PcLinkPairing? = null
    private var deadlineMs: Long = Long.MAX_VALUE

    /**
     * When the current wait expires, on the same clock the session was built with. The transport
     * uses it to size its socket read timeout; [onTick] is what actually fires the timeout.
     * [Long.MAX_VALUE] once the session is done.
     */
    val timeoutAtMs: Long get() = deadlineMs

    /** Opens the exchange. Must be called exactly once, after `hello` has gone out. */
    fun start(): List<PairingEffect> {
        check(state == State.IDLE) { "PairingSession.start() called twice" }
        return when (mode) {
            is Mode.Pair -> {
                clientNonce = random(PcLinkPairingCrypto.NONCE_LEN)
                val commitment = PcLinkPairingCrypto.commitment(identity.publicKey, clientNonce)
                state = State.COMMIT_SENT
                deadlineMs = clock() + STEP_TIMEOUT_MS
                listOf(
                    PairingEffect.Send(
                        json("pair_start") {
                            put("pairingVersion", PcLinkPairingCrypto.PAIRING_VERSION)
                            put("clientName", mode.clientName)
                            put("commitment", Hex.encode(commitment))
                        }
                    )
                )
            }

            is Mode.Authenticate -> {
                if (mode.candidates.isEmpty()) return fail(PairingFailure.UNKNOWN_TO_PC)
                clientNonce = random(PcLinkPairingCrypto.NONCE_LEN)
                state = State.CHALLENGE_SENT
                deadlineMs = clock() + AUTH_TIMEOUT_MS
                listOf(
                    PairingEffect.Send(
                        json("auth_challenge") {
                            put("pairingVersion", PcLinkPairingCrypto.PAIRING_VERSION)
                            put("clientId", identity.fingerprint)
                            put("nonce", Hex.encode(clientNonce))
                        }
                    )
                )
            }
        }
    }

    /**
     * Feeds one received control line.
     *
     * Messages outside the `pair_*` / `auth_*` family are ignored, per the protocol's
     * forward-compatibility rule — including `config`/`windows`, which a spec-compliant server
     * won't send before `auth_ok` anyway. Messages *inside* the family that don't belong in the
     * current state are a protocol error, which is what makes replayed or reordered ceremonies fail
     * instead of confusing the session.
     */
    fun onLine(line: String): List<PairingEffect> {
        if (state == State.DONE) return emptyList()
        val message = runCatching { JSONObject(line) }.getOrNull()
            ?: return failProtocol()
        return when (message.optString("type")) {
            "pair_pubkey" -> onPairPubkey(message)
            "pair_confirm" -> onPairConfirm(message)
            "pair_reject" -> onPairReject(message)
            "auth_response" -> onAuthResponse(message)
            "auth_ok" -> onAuthOk(message)
            "auth_fail" -> onAuthFail(message)
            // Unknown type, or a known one with no role in this exchange: silently ignored.
            else -> emptyList()
        }
    }

    /** The user tapped "Pair" on the code sheet. */
    fun onUserAccept(): List<PairingEffect> {
        if (state != State.AWAITING_USER) return emptyList()
        val keys = sessionKeys ?: return failProtocol()
        state = State.CONFIRM_SENT
        // Deadline unchanged: the PC's user is inside the same 90 s window we've been counting.
        return listOf(
            PairingEffect.Send(
                json("pair_confirm") {
                    put("role", PeerRole.CLIENT.wire)
                    put("confirm", Hex.encode(keys.confirmClient))
                }
            )
        )
    }

    /** The user tapped "Cancel", on the code sheet or anywhere earlier. */
    fun onUserDecline(): List<PairingEffect> {
        if (state == State.DONE) return emptyList()
        return reject(REASON_DECLINED, PairingFailure.DECLINED_LOCALLY)
    }

    /**
     * Fires whichever wait has expired. Safe (and free) to call whenever the transport wakes up;
     * returns nothing until [timeoutAtMs] has actually passed.
     */
    fun onTick(): List<PairingEffect> {
        if (state == State.DONE || clock() < deadlineMs) return emptyList()
        return when (state) {
            // Already paired on both sides — the missing token only costs a re-request later.
            State.AWAITING_AUTH_OK -> succeed(videoToken = null)
            State.CHALLENGE_SENT, State.RESPONSE_SENT -> fail(PairingFailure.TIMEOUT)
            else -> reject(REASON_TIMEOUT, PairingFailure.TIMEOUT)
        }
    }

    /** The transport lost the connection (peer closed, socket error). */
    fun onDisconnected(): List<PairingEffect> {
        if (state == State.DONE) return emptyList()
        // Same reasoning as the AWAITING_AUTH_OK timeout: the pairing is real, only the token is lost.
        if (state == State.AWAITING_AUTH_OK) return succeed(videoToken = null)
        state = State.DONE
        deadlineMs = Long.MAX_VALUE
        return listOf(PairingEffect.Finished(PairingOutcome.Failure(PairingFailure.CONNECTION_LOST)))
    }

    // ---- message handlers -------------------------------------------------------------------

    private fun onPairPubkey(message: JSONObject): List<PairingEffect> {
        if (state != State.COMMIT_SENT) return failProtocol()
        if (PeerRole.fromWire(message.optString("role")) != PeerRole.SERVER) return failProtocol()
        val name = message.optString("name")
        val pub = hexField(message, "pubkey", PcLinkPairingCrypto.KEY_LEN) ?: return failProtocol()
        val nonce = hexField(message, "nonce", PcLinkPairingCrypto.NONCE_LEN) ?: return failProtocol()
        if (name.isEmpty()) return failProtocol()

        // Abort on a low-order/zero-result key rather than deriving from a shared secret an
        // attacker can force (RFC 7748 §6.1).
        val sharedSecret = PcLinkPairingCrypto.sharedSecret(identity.privateKey, pub)
            ?: return failProtocol()

        serverPub = pub
        serverNonce = nonce
        serverName = name
        serverId = PcLinkPairingCrypto.fingerprintOf(pub)
        transcriptHash = PcLinkPairingCrypto.transcriptHash(
            protocolVersion = protocolVersion,
            pairingVersion = PcLinkPairingCrypto.PAIRING_VERSION,
            clientName = (mode as Mode.Pair).clientName,
            serverName = name,
            clientPub = identity.publicKey,
            serverPub = pub,
            clientNonce = clientNonce,
            serverNonce = nonce
        )
        val keys = PcLinkPairingCrypto.deriveSessionKeys(
            PcLinkPairingCrypto.prk(transcriptHash, sharedSecret),
            transcriptHash
        )
        sessionKeys = keys
        state = State.AWAITING_USER
        deadlineMs = clock() + CONFIRM_TIMEOUT_MS

        // Reveal, then show the code: the server can't compute its own SAS until we do, so any
        // delay here is a delay on the PC's dialog too.
        return listOf(
            PairingEffect.Send(
                json("pair_pubkey") {
                    put("role", PeerRole.CLIENT.wire)
                    put("pubkey", Hex.encode(identity.publicKey))
                    put("nonce", Hex.encode(clientNonce))
                }
            ),
            PairingEffect.ShowSas(keys.sas, name, serverId)
        )
    }

    private fun onPairConfirm(message: JSONObject): List<PairingEffect> {
        // Only ever legal after we accepted: the server sends its tag once its own user has
        // accepted *and* our tag verified. Anything earlier is a broken or probing peer.
        if (state != State.CONFIRM_SENT) return failProtocol()
        if (PeerRole.fromWire(message.optString("role")) != PeerRole.SERVER) return failProtocol()
        val keys = sessionKeys ?: return failProtocol()
        val confirm = hexField(message, "confirm", PcLinkPairingCrypto.MAC_LEN)
            ?: return failProtocol()

        if (!Hkdf.constantTimeEquals(confirm, keys.confirmServer)) {
            // Explicit key confirmation failed: the PC derived different keys, so something
            // tampered with a reveal. Nothing is stored, and the reason goes back on the wire.
            return reject(REASON_CONFIRM_MISMATCH, PairingFailure.CONFIRM_MISMATCH)
        }

        val persist = PairingEffect.Persist(serverId, serverName, keys.ltk)
        pairedRecord = persist
        state = State.AWAITING_AUTH_OK
        deadlineMs = clock() + STEP_TIMEOUT_MS
        return listOf(persist)
    }

    private fun onPairReject(message: JSONObject): List<PairingEffect> {
        if (state == State.DONE) return emptyList()
        // Unknown reasons are treated like "protocol", per §2.10.
        val failure = when (message.optString("reason")) {
            REASON_DECLINED -> PairingFailure.DECLINED_BY_PC
            "busy" -> PairingFailure.PC_BUSY
            REASON_TIMEOUT -> PairingFailure.TIMEOUT
            "rate_limited" -> PairingFailure.RATE_LIMITED
            "version" -> PairingFailure.VERSION_UNSUPPORTED
            REASON_CONFIRM_MISMATCH, "commitment_mismatch" -> PairingFailure.CONFIRM_MISMATCH
            else -> PairingFailure.PROTOCOL
        }
        return fail(failure)
    }

    private fun onAuthResponse(message: JSONObject): List<PairingEffect> {
        if (state != State.CHALLENGE_SENT) return failProtocol()
        val nonce = hexField(message, "nonce", PcLinkPairingCrypto.NONCE_LEN) ?: return failProtocol()
        val proof = hexField(message, "proof", PcLinkPairingCrypto.MAC_LEN) ?: return failProtocol()
        val candidates = (mode as Mode.Authenticate).candidates

        // The server proves first. A wrong proof means a spoofed discovery reply, a MITM, or a
        // corrupted pairing — so we abort here, before revealing a proof of our own.
        val matched = candidates.firstOrNull { pairing ->
            Hkdf.constantTimeEquals(
                proof,
                PcLinkPairingCrypto.authProof(pairing.ltk, PeerRole.SERVER, clientNonce, nonce)
            )
        } ?: return fail(PairingFailure.AUTH_FAILED)

        serverNonce = nonce
        matchedPairing = matched
        serverId = matched.serverId
        serverName = matched.name
        state = State.RESPONSE_SENT
        deadlineMs = clock() + AUTH_TIMEOUT_MS
        return listOf(
            PairingEffect.Send(
                json("auth_response") {
                    put(
                        "proof",
                        Hex.encode(
                            PcLinkPairingCrypto.authProof(
                                matched.ltk, PeerRole.CLIENT, clientNonce, nonce
                            )
                        )
                    )
                }
            )
        )
    }

    private fun onAuthOk(message: JSONObject): List<PairingEffect> {
        if (state != State.AWAITING_AUTH_OK && state != State.RESPONSE_SENT) return failProtocol()
        val token = message.optString("videoToken")
        // A malformed token isn't worth failing an otherwise-good session over: the client can ask
        // for a fresh one with `video_token` on the authenticated channel.
        val videoToken = token.takeIf { Hex.decode(it, PcLinkPairingCrypto.TOKEN_LEN) != null }
        return succeed(videoToken)
    }

    private fun onAuthFail(message: JSONObject): List<PairingEffect> {
        if (state == State.DONE) return emptyList()
        val failure = when (message.optString("reason")) {
            "unknown_client" -> PairingFailure.UNKNOWN_TO_PC
            "rate_limited" -> PairingFailure.RATE_LIMITED
            // `auth_fail` has no "version" reason — a server that doesn't speak our pairingVersion
            // answers an auth_challenge with "protocol" (only pair_start gets the richer
            // pair_reject{"version", supportedPairingVersions}). So "protocol" here is usually a
            // mismatch or a bug, and accusing the network of interference would be a lie. It still
            // gets no re-pair offer, so this only changes which sentence the user reads.
            REASON_PROTOCOL -> PairingFailure.PROTOCOL
            // "bad_proof" and anything unrecognised: an impostor until proven otherwise, and never
            // an automatic re-pair. Erring toward the loud message is the right default for a
            // failure we don't understand.
            else -> PairingFailure.AUTH_FAILED
        }
        return fail(failure)
    }

    // ---- terminal helpers -------------------------------------------------------------------

    private fun succeed(videoToken: String?): List<PairingEffect> {
        state = State.DONE
        deadlineMs = Long.MAX_VALUE
        return listOf(
            PairingEffect.Finished(
                PairingOutcome.Success(
                    serverId = serverId,
                    serverName = serverName,
                    videoToken = videoToken,
                    paired = pairedRecord != null
                )
            )
        )
    }

    private fun fail(reason: PairingFailure): List<PairingEffect> {
        state = State.DONE
        deadlineMs = Long.MAX_VALUE
        return listOf(PairingEffect.Finished(PairingOutcome.Failure(reason)), PairingEffect.Close)
    }

    /** Tells the peer why we're going, then fails. Only meaningful during the pairing ceremony. */
    private fun reject(reason: String, failure: PairingFailure): List<PairingEffect> {
        val announce = if (mode is Mode.Pair && state != State.DONE) {
            listOf(PairingEffect.Send(json("pair_reject") { put("reason", reason) }))
        } else {
            emptyList()
        }
        return announce + fail(failure)
    }

    private fun failProtocol(): List<PairingEffect> = reject(REASON_PROTOCOL, PairingFailure.PROTOCOL)

    // ---- parsing helpers --------------------------------------------------------------------

    /** Strict per §2.6: present, lowercase hex, exactly [length] bytes — or the message is refused. */
    private fun hexField(message: JSONObject, field: String, length: Int): ByteArray? {
        val text = message.optString(field)
        if (text.isEmpty()) return null
        return Hex.decode(text, length)
    }

    private inline fun json(type: String, build: JSONObject.() -> Unit): String =
        JSONObject().put("type", type).apply(build).toString()

    companion object {
        /** Each protocol step (§8.6): commit→reveal, and pairing→`auth_ok`. */
        const val STEP_TIMEOUT_MS = 10_000L

        /** The humans-comparing-codes window (§8.6), covering our user and the PC's. */
        const val CONFIRM_TIMEOUT_MS = 90_000L

        /** The whole authentication exchange (§8.6). */
        const val AUTH_TIMEOUT_MS = 10_000L

        private const val REASON_DECLINED = "declined"
        private const val REASON_TIMEOUT = "timeout"
        private const val REASON_PROTOCOL = "protocol"
        private const val REASON_CONFIRM_MISMATCH = "confirm_mismatch"

        /**
         * A first-time pairing ceremony. [clientName] is shown on the PC and bound into the code —
         * pass the same string the phone shows for itself.
         */
        fun pair(
            identity: PcLinkPairingCrypto.Identity,
            clientName: String,
            protocolVersion: Int = PcLinkDiscovery.PROTOCOL_VERSION,
            clock: () -> Long = { System.nanoTime() / 1_000_000 },
            random: (Int) -> ByteArray = Hkdf::randomBytes
        ): PairingSession =
            PairingSession(Mode.Pair(clientName), identity, protocolVersion, clock, random)

        /**
         * Silent re-authentication against stored pairings. [candidates] should lead with the most
         * likely PC (see [PcLinkPairingStore.findByHost]); the rest are tried only against the
         * server's own proof, so ordering is a performance detail, not a security one.
         */
        fun authenticate(
            identity: PcLinkPairingCrypto.Identity,
            candidates: List<PcLinkPairing>,
            protocolVersion: Int = PcLinkDiscovery.PROTOCOL_VERSION,
            clock: () -> Long = { System.nanoTime() / 1_000_000 },
            random: (Int) -> ByteArray = Hkdf::randomBytes
        ): PairingSession =
            PairingSession(Mode.Authenticate(candidates), identity, protocolVersion, clock, random)
    }
}
