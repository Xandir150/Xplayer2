package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import com.teleteh.xplayer2.util.crypto.X25519
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The PC Link pairing derivations (`pairingVersion` 1), transcribed from the canonical spec —
 * `xplayer-link-server/docs/pairing-design.md` §4 / `protocol.md` §2.6 — and cross-checked against
 * the shared Rust⇄Kotlin fixtures in `app/src/test/resources/pclink_vectors.json`:
 *
 * ```
 * lp(s)         = u16be(len(utf8(s))) || utf8(s)
 * T             = "XPL2-PAIR-v1" || u32be(protocolVersion) || u32be(pairingVersion)
 *                 || lp(clientName) || lp(serverName)
 *                 || clientPub || serverPub || clientNonce || serverNonce
 * th            = SHA-256(T)
 * commitment    = SHA-256("XPL2-COMMIT-v1" || clientPub || clientNonce)
 * ss            = X25519(ownPrivate, peerPublic)          (abort if all-zero)
 * prk           = HKDF-Extract(salt = th, ikm = ss)
 * sas           = u64be(HKDF-Expand(prk, "XPL2 sas", 8)) mod 1000000
 * ltk           = HKDF-Expand(prk, "XPL2 ltk", 32)
 * kconf         = HKDF-Expand(prk, "XPL2 confirm", 32)
 * confirmClient = HMAC-SHA256(kconf, "client" || th)
 * confirmServer = HMAC-SHA256(kconf, "server" || th)
 * proof(role)   = HMAC-SHA256(ltk, "XPL2-AUTH-v1 <role>" || nonceC || nonceS)
 * proofV2(role) = HMAC-SHA256(ltk, "XPL2-AUTH-v2 <role>" || nonceC || nonceS
 *                                  || u32be(offered) || u32be(selected))
 * ```
 *
 * Everything is a pure function of its arguments: no sockets, no Android, no state — so the whole
 * file is exercised by plain JVM unit tests against the same vectors the server's `pair_kat`
 * example consumes. The *order* of the fields is the security property (§4.3): client fields
 * always precede server fields, so a swapped role, substituted key, edited name or bumped version
 * all change `th`, and therefore the code the two humans compare.
 */
object PcLinkPairingCrypto {

    /** The pairing method this client implements. Independent of `protocolVersion`. */
    const val PAIRING_VERSION = 1

    const val KEY_LEN = 32
    const val NONCE_LEN = 16
    const val MAC_LEN = 32
    const val TOKEN_LEN = 32

    private const val TRANSCRIPT_PREFIX = "XPL2-PAIR-v1"
    private const val COMMIT_PREFIX = "XPL2-COMMIT-v1"
    private const val AUTH_LABEL_PREFIX = "XPL2-AUTH-v1 "
    private const val AUTH_LABEL_V2_PREFIX = "XPL2-AUTH-v2 "
    private const val UNPAIR_LABEL_PREFIX = "XPL2-UNPAIR-v1 "
    private const val ROLE_CLIENT = "client"
    private const val ROLE_SERVER = "server"

    /** A device's long-term X25519 identity. [fingerprint] is what travels as `clientId`/`serverId`. */
    data class Identity(val privateKey: ByteArray, val publicKey: ByteArray) {
        val fingerprint: String get() = fingerprintOf(publicKey)

        // Value semantics on byte arrays: the data class defaults would compare by reference, which
        // would quietly break any equality check in tests or caches.
        override fun equals(other: Any?): Boolean = other is Identity &&
            privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)

        override fun hashCode(): Int = privateKey.contentHashCode() * 31 + publicKey.contentHashCode()
    }

    /** Everything derived from one completed key exchange. */
    data class SessionKeys(
        /** The six digits the two humans compare, zero-padded (e.g. `"004217"`). */
        val sas: String,
        val ltk: ByteArray,
        val kconf: ByteArray,
        val confirmClient: ByteArray,
        val confirmServer: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = other is SessionKeys && sas == other.sas &&
            ltk.contentEquals(other.ltk) && kconf.contentEquals(other.kconf) &&
            confirmClient.contentEquals(other.confirmClient) &&
            confirmServer.contentEquals(other.confirmServer)

        override fun hashCode(): Int = sas.hashCode() * 31 + ltk.contentHashCode()
    }

    /** Generates a fresh identity from the OS CSPRNG. */
    fun generateIdentity(): Identity {
        // 32 raw random bytes, stored unclamped: X25519.computeSharedSecret clamps at use time
        // (RFC 7748 §5), which is also how the server's x25519-dalek StaticSecret behaves — so the
        // two sides' stored key material has the same shape.
        val privateKey = Hkdf.randomBytes(KEY_LEN)
        return Identity(privateKey, X25519.publicFromPrivate(privateKey))
    }

    /** Rebuilds an identity from a stored private key, or null if the stored bytes are unusable. */
    fun identityFromPrivateKey(privateKey: ByteArray): Identity? {
        if (privateKey.size != KEY_LEN) return null
        return try {
            Identity(privateKey, X25519.publicFromPrivate(privateKey))
        } catch (_: Exception) {
            null
        }
    }

    /** `clientId` / `serverId`: lowercase-hex SHA-256 of the 32-byte public key. */
    fun fingerprintOf(publicKey: ByteArray): String = Hex.encode(Hkdf.sha256(publicKey))

    /** `commitment = SHA-256("XPL2-COMMIT-v1" || clientPub || clientNonce)`. */
    fun commitment(clientPub: ByteArray, clientNonce: ByteArray): ByteArray =
        Hkdf.sha256(ascii(COMMIT_PREFIX) + clientPub + clientNonce)

    /** The full transcript `T`; [transcriptHash] is what the derivations actually use. */
    fun transcript(
        protocolVersion: Int,
        pairingVersion: Int,
        clientName: String,
        serverName: String,
        clientPub: ByteArray,
        serverPub: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): ByteArray {
        val clientNameBytes = clientName.toByteArray(Charsets.UTF_8)
        val serverNameBytes = serverName.toByteArray(Charsets.UTF_8)
        val size = TRANSCRIPT_PREFIX.length + 4 + 4 +
            2 + clientNameBytes.size + 2 + serverNameBytes.size +
            clientPub.size + serverPub.size + clientNonce.size + serverNonce.size
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(ascii(TRANSCRIPT_PREFIX))
            putInt(protocolVersion)
            putInt(pairingVersion)
            putLengthPrefixed(clientNameBytes)
            putLengthPrefixed(serverNameBytes)
            put(clientPub)
            put(serverPub)
            put(clientNonce)
            put(serverNonce)
        }.array()
    }

    fun transcriptHash(
        protocolVersion: Int,
        pairingVersion: Int,
        clientName: String,
        serverName: String,
        clientPub: ByteArray,
        serverPub: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): ByteArray = Hkdf.sha256(
        transcript(
            protocolVersion, pairingVersion, clientName, serverName,
            clientPub, serverPub, clientNonce, serverNonce
        )
    )

    /**
     * X25519 with the RFC 7748 §6.1 contributory check: null when the peer key is malformed, is one
     * of the low-order points Tink refuses, or would yield an all-zero shared secret. Callers must
     * treat null as "abort the ceremony", never as "carry on with zeros".
     */
    fun sharedSecret(ownPrivate: ByteArray, peerPublic: ByteArray): ByteArray? {
        if (ownPrivate.size != KEY_LEN || peerPublic.size != KEY_LEN) return null
        val ss = try {
            X25519.computeSharedSecret(ownPrivate, peerPublic)
        } catch (_: Exception) {
            return null
        }
        return if (Hkdf.constantTimeEquals(ss, ByteArray(KEY_LEN))) null else ss
    }

    /** `prk = HKDF-Extract(salt = th, ikm = ss)`. */
    fun prk(transcriptHash: ByteArray, sharedSecret: ByteArray): ByteArray =
        Hkdf.extract(transcriptHash, sharedSecret)

    /** SAS, LTK, kconf and both confirmation tags from one `prk` + transcript hash. */
    fun deriveSessionKeys(prk: ByteArray, transcriptHash: ByteArray): SessionKeys {
        val ltk = Hkdf.expand(prk, ascii("XPL2 ltk"), KEY_LEN)
        val kconf = Hkdf.expand(prk, ascii("XPL2 confirm"), KEY_LEN)
        return SessionKeys(
            sas = sas(prk),
            ltk = ltk,
            kconf = kconf,
            confirmClient = confirmTag(kconf, ROLE_CLIENT, transcriptHash),
            confirmServer = confirmTag(kconf, ROLE_SERVER, transcriptHash)
        )
    }

    /**
     * `u64be(HKDF-Expand(prk, "XPL2 sas", 8)) mod 1_000_000`, zero-padded to six digits.
     *
     * Read as unsigned via [java.lang.Long.remainderUnsigned]: `ByteBuffer.getLong` is signed, and
     * a plain `%` on a negative value would produce a negative "code" for half of all handshakes.
     */
    fun sas(prk: ByteArray): String {
        val raw = Hkdf.expand(prk, ascii("XPL2 sas"), 8)
        val value = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).long
        return String.format("%06d", java.lang.Long.remainderUnsigned(value, 1_000_000L))
    }

    /** Display grouping for the sheet: `"893122"` → `"893 122"`. */
    fun formatSas(sas: String): String =
        if (sas.length == 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas

    /** `HMAC-SHA256(kconf, role || th)` — the explicit key-confirmation tag in `pair_confirm`. */
    fun confirmTag(kconf: ByteArray, role: String, transcriptHash: ByteArray): ByteArray =
        Hkdf.hmacSha256(kconf, ascii(role) + transcriptHash)

    /**
     * `HMAC-SHA256(ltk, "XPL2-AUTH-v1 <role>" || nonceC || nonceS)`.
     *
     * The role is inside the MAC input, not implied by message position: that is what stops a
     * server's proof from being reflected back as the client's.
     */
    fun authProof(ltk: ByteArray, role: PeerRole, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        Hkdf.hmacSha256(ltk, ascii(AUTH_LABEL_PREFIX + role.wire) + clientNonce + serverNonce)

    /**
     * `HMAC-SHA256(ltk, "XPL2-AUTH-v2 <role>" || nonceC || nonceS || u32be(offered) ||
     * u32be(selected))` — [authProof] for a session whose encryption selection is 2 (§2.18.2).
     *
     * The negotiation rides inside the MAC, which is what makes it tamper-proof between two
     * version-2 endpoints: [offered] is the client's `encryption` field as each side knows it — as
     * sent by us, as received by the PC — and [selected] the server's the same way. Rewrite either
     * in flight and the two sides MAC different bytes, so the exchange dies on a proof rather than
     * quietly running plaintext. The label spells the *selected* version.
     */
    fun authProofV2(
        ltk: ByteArray,
        role: PeerRole,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        offered: Int,
        selected: Int
    ): ByteArray {
        val label = ascii(AUTH_LABEL_V2_PREFIX + role.wire)
        val input = ByteBuffer.allocate(label.size + clientNonce.size + serverNonce.size + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(label)
            .put(clientNonce)
            .put(serverNonce)
            .putInt(offered)
            .putInt(selected)
            .array()
        return Hkdf.hmacSha256(ltk, input)
    }

    /**
     * The proof for whatever was negotiated: [authProof] at a selection of 1, [authProofV2] above
     * it. The one entry point [PairingSession] uses, so "which formula" is decided by the selection
     * in one place rather than at each of the four call sites.
     */
    fun negotiatedAuthProof(
        ltk: ByteArray,
        role: PeerRole,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        offered: Int,
        selected: Int
    ): ByteArray = if (selected >= PcLinkEnvelope.AEAD) {
        authProofV2(ltk, role, clientNonce, serverNonce, offered, selected)
    } else {
        authProof(ltk, role, clientNonce, serverNonce)
    }

    /**
     * `HMAC-SHA256(ltk, "XPL2-UNPAIR-v1 <role>" || nonceC || nonceS)` — bound to the current
     * session's auth nonces, so an attacker injecting into the plaintext v1 transport can neither
     * forge nor replay an `unpair`.
     */
    fun unpairProof(ltk: ByteArray, role: PeerRole, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        Hkdf.hmacSha256(ltk, ascii(UNPAIR_LABEL_PREFIX + role.wire) + clientNonce + serverNonce)

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun ByteBuffer.putLengthPrefixed(bytes: ByteArray) {
        putShort(bytes.size.toShort())
        put(bytes)
    }
}

/** Which end of the control channel a message or proof belongs to. The phone is always the client. */
enum class PeerRole(val wire: String) {
    CLIENT("client"),
    SERVER("server");

    companion object {
        fun fromWire(value: String?): PeerRole? = entries.firstOrNull { it.wire == value }
    }
}
