package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import com.teleteh.xplayer2.util.crypto.X25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation known-answer tests for [PcLinkPairingCrypto].
 *
 * The fixtures in `src/test/resources/pclink_vectors.json` are the canonical pairing vectors from
 * `xplayer-link-server/docs/pairing-design.md` §14.1 — the same file the Rust `xpl-pairing` KAT
 * consumes. Every intermediate is asserted in hex, not just the end result: if the two languages
 * ever disagree about a length prefix, a UTF-8 encoding, an HKDF info string or a MAC label, the
 * test that fails names the exact step rather than leaving a mismatched 6-digit code to be
 * debugged on a phone against a PC.
 *
 * Vector 1's key pair and `sharedSecret` are RFC 7748 §6.1's own known answer, so this doubles as
 * the acceptance test for the vendored Tink X25519 (see `util/crypto/NOTICE.md`).
 */
class PcLinkPairingCryptoTest {

    private val vectors: List<JSONObject> by lazy {
        val text = javaClass.classLoader!!
            .getResourceAsStream("pclink_vectors.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONObject(text).getJSONArray("vectors")
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun fixtureFileHasBothCanonicalVectors() {
        assertEquals(2, vectors.size)
        assertEquals("Vector 1", vectors[0].getString("name"))
        // Vector 2 is the one that pins UTF-8 + length-prefix handling; losing it would let a
        // Cyrillic-name bug through unnoticed.
        assertEquals("Мой телефон", vectors[1].getString("clientName"))
    }

    @Test
    fun publicKeysDeriveFromPrivateKeys() = forEachVector { v ->
        assertHex(v, "clientPub", X25519.publicFromPrivate(bytes(v, "clientPriv")))
        assertHex(v, "serverPub", X25519.publicFromPrivate(bytes(v, "serverPriv")))
    }

    @Test
    fun fingerprintsAreSha256OfPublicKeys() = forEachVector { v ->
        assertEquals(v.name(), v.getString("clientId"), PcLinkPairingCrypto.fingerprintOf(bytes(v, "clientPub")))
        assertEquals(v.name(), v.getString("serverId"), PcLinkPairingCrypto.fingerprintOf(bytes(v, "serverPub")))
    }

    @Test
    fun sharedSecretMatchesBothDirections() = forEachVector { v ->
        val fromClient = PcLinkPairingCrypto.sharedSecret(bytes(v, "clientPriv"), bytes(v, "serverPub"))
        val fromServer = PcLinkPairingCrypto.sharedSecret(bytes(v, "serverPriv"), bytes(v, "clientPub"))
        assertHex(v, "sharedSecret", fromClient!!)
        assertHex(v, "sharedSecret", fromServer!!)
    }

    @Test
    fun commitmentMatches() = forEachVector { v ->
        assertHex(v, "commitment", PcLinkPairingCrypto.commitment(bytes(v, "clientPub"), bytes(v, "clientNonce")))
    }

    /** The whole byte-level transcript, not just its hash — the field a length-prefix bug shows up in. */
    @Test
    fun transcriptBytesMatch() = forEachVector { v ->
        assertHex(v, "transcript", transcriptOf(v))
    }

    @Test
    fun transcriptHashMatches() = forEachVector { v ->
        assertHex(v, "transcriptHash", Hkdf.sha256(transcriptOf(v)))
    }

    @Test
    fun prkMatches() = forEachVector { v ->
        assertHex(v, "prk", PcLinkPairingCrypto.prk(bytes(v, "transcriptHash"), bytes(v, "sharedSecret")))
    }

    @Test
    fun sasLtkKconfAndConfirmTagsMatch() = forEachVector { v ->
        val keys = PcLinkPairingCrypto.deriveSessionKeys(bytes(v, "prk"), bytes(v, "transcriptHash"))
        assertEquals(v.name(), v.getString("sas"), keys.sas)
        assertHex(v, "ltk", keys.ltk)
        assertHex(v, "kconf", keys.kconf)
        assertHex(v, "confirmClient", keys.confirmClient)
        assertHex(v, "confirmServer", keys.confirmServer)
    }

    @Test
    fun authProofsMatchInBothRoles() = forEachVector { v ->
        val ltk = bytes(v, "ltk")
        val nonceC = bytes(v, "authClientNonce")
        val nonceS = bytes(v, "authServerNonce")
        assertHex(v, "proofServer", PcLinkPairingCrypto.authProof(ltk, PeerRole.SERVER, nonceC, nonceS))
        assertHex(v, "proofClient", PcLinkPairingCrypto.authProof(ltk, PeerRole.CLIENT, nonceC, nonceS))
    }

    @Test
    fun unpairProofsMatch() = forEachVector { v ->
        if (!v.has("unpairProofClient")) return@forEachVector // optional field in the shared fixture
        val ltk = bytes(v, "ltk")
        val nonceC = bytes(v, "authClientNonce")
        val nonceS = bytes(v, "authServerNonce")
        assertHex(v, "unpairProofServer", PcLinkPairingCrypto.unpairProof(ltk, PeerRole.SERVER, nonceC, nonceS))
        assertHex(v, "unpairProofClient", PcLinkPairingCrypto.unpairProof(ltk, PeerRole.CLIENT, nonceC, nonceS))
    }

    /** End-to-end: from raw key material to the digits on screen, with no fixture shortcuts. */
    @Test
    fun fullCeremonyDerivationFromKeysAlone() = forEachVector { v ->
        val th = PcLinkPairingCrypto.transcriptHash(
            protocolVersion = v.getInt("protocolVersion"),
            pairingVersion = v.getInt("pairingVersion"),
            clientName = v.getString("clientName"),
            serverName = v.getString("serverName"),
            clientPub = X25519.publicFromPrivate(bytes(v, "clientPriv")),
            serverPub = X25519.publicFromPrivate(bytes(v, "serverPriv")),
            clientNonce = bytes(v, "clientNonce"),
            serverNonce = bytes(v, "serverNonce")
        )
        val ss = PcLinkPairingCrypto.sharedSecret(bytes(v, "clientPriv"), bytes(v, "serverPub"))!!
        val keys = PcLinkPairingCrypto.deriveSessionKeys(PcLinkPairingCrypto.prk(th, ss), th)
        assertEquals(v.name(), v.getString("sas"), keys.sas)
        assertHex(v, "ltk", keys.ltk)
    }

    /** §4.3: swapping the two roles' fields must change the transcript, hence the code. */
    @Test
    fun transcriptIsPositionallyRoleBound() {
        val v = vectors[0]
        val swapped = PcLinkPairingCrypto.transcript(
            protocolVersion = 1, pairingVersion = 1,
            clientName = v.getString("serverName"), serverName = v.getString("clientName"),
            clientPub = bytes(v, "serverPub"), serverPub = bytes(v, "clientPub"),
            clientNonce = bytes(v, "serverNonce"), serverNonce = bytes(v, "clientNonce")
        )
        assertFalse(Hex.encode(swapped) == v.getString("transcript"))
    }

    /** §8.4: the version numbers are inside the transcript, so a downgrade can't stay invisible. */
    @Test
    fun bumpingAVersionChangesTheSas() {
        val v = vectors[0]
        val ss = bytes(v, "sharedSecret")
        fun sasFor(protocolVersion: Int, pairingVersion: Int): String {
            val th = PcLinkPairingCrypto.transcriptHash(
                protocolVersion, pairingVersion,
                v.getString("clientName"), v.getString("serverName"),
                bytes(v, "clientPub"), bytes(v, "serverPub"),
                bytes(v, "clientNonce"), bytes(v, "serverNonce")
            )
            return PcLinkPairingCrypto.sas(PcLinkPairingCrypto.prk(th, ss))
        }
        assertEquals("893122", sasFor(1, 1))
        assertFalse("893122" == sasFor(2, 1))
        assertFalse("893122" == sasFor(1, 2))
    }

    /** §14.4: one flipped bit in a revealed key ⇒ different SAS *and* mismatching confirm tags. */
    @Test
    fun tamperedServerKeyChangesSasAndConfirmTags() {
        val v = vectors[0]
        val honestTh = bytes(v, "transcriptHash")
        val honestKeys = PcLinkPairingCrypto.deriveSessionKeys(bytes(v, "prk"), honestTh)

        val tamperedServerPub = bytes(v, "serverPub").copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tamperedTh = PcLinkPairingCrypto.transcriptHash(
            1, 1, v.getString("clientName"), v.getString("serverName"),
            bytes(v, "clientPub"), tamperedServerPub, bytes(v, "clientNonce"), bytes(v, "serverNonce")
        )
        val ss = PcLinkPairingCrypto.sharedSecret(bytes(v, "clientPriv"), tamperedServerPub)
        assertNotNull("a tampered key that is still a valid point must still DH", ss)
        val tamperedKeys = PcLinkPairingCrypto.deriveSessionKeys(PcLinkPairingCrypto.prk(tamperedTh, ss!!), tamperedTh)

        assertFalse(honestKeys.sas == tamperedKeys.sas)
        assertFalse(honestKeys.confirmServer.contentEquals(tamperedKeys.confirmServer))
        assertFalse(honestKeys.ltk.contentEquals(tamperedKeys.ltk))
    }

    /**
     * §4.1: a peer key of small order — which would force an all-zero shared secret, letting a MITM
     * fix both sides' key material — must abort, never derive. Whether the rejection comes from
     * Tink's banned-key list or from our own explicit all-zero check, the answer must be null.
     */
    @Test
    fun lowOrderPeerKeyIsRejected() {
        val priv = bytes(vectors[0], "clientPriv")
        val lowOrderPoints = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000", // 0
            "0100000000000000000000000000000000000000000000000000000000000000", // 1
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800", // order 8
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157", // order 8
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p - 1
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"  // p + 1
        )
        for (point in lowOrderPoints) {
            assertNull(point, PcLinkPairingCrypto.sharedSecret(priv, Hex.decode(point)!!))
        }
        // Wrong-length key material is a protocol error, not something to pad or truncate.
        assertNull(PcLinkPairingCrypto.sharedSecret(priv, ByteArray(31)))
        assertNull(PcLinkPairingCrypto.sharedSecret(ByteArray(0), bytes(vectors[0], "serverPub")))
    }

    @Test
    fun generatedIdentitiesAgreeOnASharedSecret() {
        val a = PcLinkPairingCrypto.generateIdentity()
        val b = PcLinkPairingCrypto.generateIdentity()
        assertEquals(32, a.privateKey.size)
        assertEquals(32, a.publicKey.size)
        assertEquals(64, a.fingerprint.length)
        assertFalse(a.fingerprint == b.fingerprint)
        assertArrayEquals(
            PcLinkPairingCrypto.sharedSecret(a.privateKey, b.publicKey),
            PcLinkPairingCrypto.sharedSecret(b.privateKey, a.publicKey)
        )
        val restored = PcLinkPairingCrypto.identityFromPrivateKey(a.privateKey)
        assertEquals(a, restored)
        assertNull(PcLinkPairingCrypto.identityFromPrivateKey(ByteArray(31)))
    }

    /** §14.2: the modulo must never produce a negative or short code, and grouping is cosmetic. */
    @Test
    fun sasIsAlwaysSixDigitsIncludingHighBitAndLeadingZeroCases() {
        // A prk whose expand output has the top bit set: signed arithmetic would go negative here.
        val highBit = PcLinkPairingCrypto.sas(ByteArray(32) { 0xff.toByte() })
        assertEquals(6, highBit.length)
        assertTrue(highBit.all { it.isDigit() })
        // Brute-force a range of prks: every one must format as exactly six digits.
        for (i in 0 until 500) {
            val sas = PcLinkPairingCrypto.sas(Hkdf.sha256("seed$i".toByteArray()))
            assertEquals("prk seed$i -> $sas", 6, sas.length)
            assertTrue(sas.all { it.isDigit() })
        }
        assertEquals("004 217", PcLinkPairingCrypto.formatSas("004217"))
        assertEquals("893 122", PcLinkPairingCrypto.formatSas("893122"))
    }

    @Test
    fun hexDecoderRejectsUppercaseWrongLengthAndJunk() {
        assertArrayEquals(byteArrayOf(0x0a, 0x1b), Hex.decode("0a1b"))
        assertArrayEquals(byteArrayOf(0x0a, 0x1b), Hex.decode("0a1b", expectedLength = 2))
        assertNull("uppercase is not the wire format", Hex.decode("0A1B"))
        assertNull(Hex.decode("0a1"))
        assertNull(Hex.decode("0a1z"))
        assertNull(Hex.decode("0a1b", expectedLength = 3))
        assertEquals("0a1b", Hex.encode(byteArrayOf(0x0a, 0x1b)))
    }

    /** RFC 5869 §A.2 test case 2: HKDF itself, independent of anything PC Link-specific. */
    @Test
    fun hkdfMatchesRfc5869TestCase2() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        val prk = Hkdf.extract(salt, ikm)
        assertEquals(
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            Hex.encode(prk)
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hex.encode(Hkdf.expand(prk, info, 82))
        )
    }

    private fun transcriptOf(v: JSONObject): ByteArray = PcLinkPairingCrypto.transcript(
        protocolVersion = v.getInt("protocolVersion"),
        pairingVersion = v.getInt("pairingVersion"),
        clientName = v.getString("clientName"),
        serverName = v.getString("serverName"),
        clientPub = bytes(v, "clientPub"),
        serverPub = bytes(v, "serverPub"),
        clientNonce = bytes(v, "clientNonce"),
        serverNonce = bytes(v, "serverNonce")
    )

    private fun forEachVector(body: (JSONObject) -> Unit) = vectors.forEach(body)

    private fun bytes(v: JSONObject, field: String): ByteArray =
        requireNotNull(Hex.decode(v.getString(field))) { "fixture field $field is not lowercase hex" }

    private fun assertHex(v: JSONObject, field: String, actual: ByteArray) =
        assertEquals("${v.name()}.$field", v.getString(field), Hex.encode(actual))

    private fun JSONObject.name(): String = optString("name")
}
