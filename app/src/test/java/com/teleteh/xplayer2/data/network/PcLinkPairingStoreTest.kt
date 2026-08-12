package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for [PcLinkPairingStore]'s record handling: identity persistence, re-pair semantics,
 * forget, host lookup, and what happens when a stored secret can no longer be opened.
 *
 * The store is built through its internal constructor with in-memory [PcLinkPairingStore.Backing]s
 * and the plain cipher, so no `SharedPreferences` (stubbed to throw in local unit tests) and no
 * AndroidKeyStore are involved — same reasoning as [PcServerListStateTest]. The Keystore wrapper
 * itself is device-only code; what is tested here is that the store treats an unopenable value as
 * "not paired", which is the behaviour that matters if the Keystore key ever goes away.
 */
class PcLinkPairingStoreTest {

    private class FakeBacking : PcLinkPairingStore.Backing {
        val map = LinkedHashMap<String, String>()
        override fun all(): Map<String, String> = LinkedHashMap(map)
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** Refuses to open anything — stands in for a device whose Keystore key vanished. */
    private object BrokenCipher : PcLinkPairingStore.SecretCipher {
        override fun seal(secret: ByteArray): String = Hex.encode(secret)
        override fun open(sealed: String): ByteArray? = null
    }

    private val identityBacking = FakeBacking()
    private val pairingBacking = FakeBacking()
    private var clock = Instant.parse("2026-08-11T14:03:00Z")

    private fun store(cipher: PcLinkPairingStore.SecretCipher = PcLinkPairingStore.PlainSecretCipher) =
        PcLinkPairingStore(identityBacking, pairingBacking, cipher, { clock })

    private fun ltk(seed: Byte) = ByteArray(32) { seed }

    @Test
    fun identityIsGeneratedOnceAndThenReused() {
        val first = store().identity()
        assertEquals(32, first.privateKey.size)
        assertEquals(64, first.fingerprint.length)
        // A second store over the same backing (i.e. the next app launch) must find the same key —
        // a regenerated identity would silently invalidate every pairing on every PC.
        assertEquals(first, store().identity())
        assertEquals(1, identityBacking.map.size)
        assertTrue(identityBacking.map.containsKey(PcLinkPairingStore.KEY_SECRET_KEY))
    }

    @Test
    fun corruptStoredIdentityIsReplacedRatherThanCrashing() {
        identityBacking.put(PcLinkPairingStore.KEY_SECRET_KEY, "not-hex")
        val identity = store().identity()
        assertEquals(32, identity.privateKey.size)
        assertEquals(Hex.encode(identity.privateKey), identityBacking.get(PcLinkPairingStore.KEY_SECRET_KEY))
    }

    @Test
    fun addGetRoundTripsEveryField() {
        val s = store()
        val serverId = "f".repeat(64)
        s.addOrUpdate(serverId, "Living Room PC", ltk(0x11), host = "192.168.1.10")

        val loaded = s.get(serverId)!!
        assertEquals(serverId, loaded.serverId)
        assertEquals("Living Room PC", loaded.name)
        assertArrayEquals(ltk(0x11), loaded.ltk)
        assertEquals("2026-08-11T14:03:00Z", loaded.createdAt)
        assertEquals("2026-08-11T14:03:00Z", loaded.lastSeenAt)
        assertEquals("192.168.1.10", loaded.lastHost)
        assertTrue(s.isPaired(serverId))
        assertEquals(listOf(loaded), s.getAll())
    }

    /** §11.2: re-pairing derives a fresh LTK, but the record is the relationship — keep createdAt. */
    @Test
    fun rePairingOverwritesTheKeyAndKeepsCreatedAt() {
        val s = store()
        val serverId = "a".repeat(64)
        s.addOrUpdate(serverId, "Living Room PC", ltk(0x11), host = "192.168.1.10")

        clock = Instant.parse("2026-09-01T09:30:00Z")
        s.addOrUpdate(serverId, "Renamed PC", ltk(0x22), host = "192.168.1.44")

        val loaded = s.get(serverId)!!
        assertArrayEquals(ltk(0x22), loaded.ltk)
        assertEquals("Renamed PC", loaded.name)
        assertEquals("2026-08-11T14:03:00Z", loaded.createdAt)
        assertEquals("2026-09-01T09:30:00Z", loaded.lastSeenAt)
        assertEquals("192.168.1.44", loaded.lastHost)
        assertEquals(1, s.getAll().size)
    }

    @Test
    fun touchRefreshesLastSeenHostAndNameButNeverCreatesARecord() {
        val s = store()
        val serverId = "b".repeat(64)
        s.addOrUpdate(serverId, "Living Room PC", ltk(0x33), host = "192.168.1.10")

        clock = Instant.parse("2026-08-12T20:00:00Z")
        s.touch(serverId, host = "192.168.1.77", name = "Living Room PC (new)")
        val loaded = s.get(serverId)!!
        assertEquals("2026-08-12T20:00:00Z", loaded.lastSeenAt)
        assertEquals("192.168.1.77", loaded.lastHost)
        assertEquals("Living Room PC (new)", loaded.name)
        assertArrayEquals("touch must not disturb the key", ltk(0x33), loaded.ltk)

        s.touch("c".repeat(64), host = "10.0.0.1")
        assertNull(s.get("c".repeat(64)))
        assertEquals(1, s.getAll().size)
    }

    @Test
    fun forgetRemovesOnlyThatPairing() {
        val s = store()
        s.addOrUpdate("a".repeat(64), "PC A", ltk(0x01), host = "10.0.0.1")
        s.addOrUpdate("b".repeat(64), "PC B", ltk(0x02), host = "10.0.0.2")

        s.forget("a".repeat(64))
        assertNull(s.get("a".repeat(64)))
        assertFalse(s.isPaired("a".repeat(64)))
        assertEquals(listOf("PC B"), s.getAll().map { it.name })
        // Forgetting something we never had is a no-op, not a crash (double-tap on the dialog).
        s.forget("a".repeat(64))
        assertEquals(1, s.getAll().size)
    }

    @Test
    fun findByHostPicksThePcLastSeenAtThatAddress() {
        val s = store()
        s.addOrUpdate("a".repeat(64), "PC A", ltk(0x01), host = "192.168.1.10")
        clock = clock.plusSeconds(60)
        s.addOrUpdate("b".repeat(64), "PC B", ltk(0x02), host = "192.168.1.11")

        assertEquals("PC A", s.findByHost("192.168.1.10")?.name)
        assertEquals("PC B", s.findByHost("192.168.1.11")?.name)
        assertNull("a never-seen address means 'pair', not 'authenticate'", s.findByHost("192.168.1.99"))
        assertNull(s.findByHost(""))
        assertEquals("PC A", s.findByHost("192.168.1.10")?.name)
    }

    /**
     * A PC that forgot us by **regenerating its identity** re-pairs under a new fingerprint, so the
     * store ends up holding two records for the same machine at the same address: the fresh pairing
     * and an orphan whose LTK the PC no longer has.
     *
     * The orphan is deliberately not swept — nothing here can tell "this PC replaced its identity"
     * from "two PCs took turns on one DHCP lease", and deleting a pairing on a guess is worse than
     * keeping a dead one. What must hold is that the *fresh* record wins the host lookup, so the
     * connect screen leads with a key that can actually authenticate. (Even if it didn't, the auth
     * FSM tries every stored key, so this is a "leads with the right one" contract rather than a
     * correctness cliff — but it is the contract [PcLinkPairingStore.findByHost] documents.)
     */
    @Test
    fun aRegeneratedPcsFreshPairingWinsTheHostLookup() {
        val s = store()
        s.addOrUpdate("a".repeat(64), "Living Room PC", ltk(0x01), host = "192.168.1.10")
        clock = clock.plusSeconds(86_400)
        s.addOrUpdate("b".repeat(64), "Living Room PC", ltk(0x02), host = "192.168.1.10")

        val found = s.findByHost("192.168.1.10")!!
        assertEquals("b".repeat(64), found.serverId)
        assertArrayEquals(ltk(0x02), found.ltk)
        // The orphan stays, and stays available as a fallback auth candidate.
        assertEquals(2, s.getAll().size)
        assertEquals("b".repeat(64), s.getAll().first().serverId)
    }

    /** Newest contact first: the PC you used last is the one you're most likely reconnecting to. */
    @Test
    fun getAllOrdersByLastSeenDescending() {
        val s = store()
        s.addOrUpdate("a".repeat(64), "PC A", ltk(0x01))
        clock = clock.plusSeconds(3600)
        s.addOrUpdate("b".repeat(64), "PC B", ltk(0x02))
        clock = clock.plusSeconds(3600)
        s.addOrUpdate("c".repeat(64), "PC C", ltk(0x03))
        assertEquals(listOf("PC C", "PC B", "PC A"), s.getAll().map { it.name })
    }

    /** §11.2: a secret we can't unwrap means "forgotten", never a half-usable record. */
    @Test
    fun unopenableSecretsAreReportedAsNotPaired() {
        store().addOrUpdate("a".repeat(64), "PC A", ltk(0x01), host = "192.168.1.10")
        assertTrue(pairingBacking.map.isNotEmpty())

        val broken = store(BrokenCipher)
        assertNull(broken.get("a".repeat(64)))
        assertEquals(emptyList<PcLinkPairing>(), broken.getAll())
        assertNull(broken.findByHost("192.168.1.10"))
        // A fresh identity too, rather than an exception on a null private key.
        assertEquals(32, broken.identity().privateKey.size)
    }

    @Test
    fun corruptRecordJsonIsSkipped() {
        pairingBacking.put("a".repeat(64), "{not json")
        pairingBacking.put("b".repeat(64), JSONObject().put("name", "No key here").toString())
        pairingBacking.put(
            "c".repeat(64),
            JSONObject().put("name", "Short key").put("ltk", Hex.encode(ByteArray(8))).toString()
        )
        val s = store()
        assertEquals(emptyList<PcLinkPairing>(), s.getAll())
        assertNull(s.get("a".repeat(64)))
        assertNull(s.get("b".repeat(64)))
        assertNull(s.get("c".repeat(64)))
    }

    /** Mirrors the server store's rule: a field this build doesn't know must survive a rewrite. */
    @Test
    fun unknownRecordFieldsSurviveARewrite() {
        val serverId = "a".repeat(64)
        pairingBacking.put(
            serverId,
            JSONObject()
                .put("name", "PC A")
                .put("ltk", Hex.encode(ltk(0x01)))
                .put("createdAt", "2020-01-01T00:00:00Z")
                .put("futureField", "keep me")
                .toString()
        )
        store().addOrUpdate(serverId, "PC A", ltk(0x09))
        val raw = JSONObject(pairingBacking.get(serverId)!!)
        assertEquals("keep me", raw.getString("futureField"))
        assertEquals("2020-01-01T00:00:00Z", raw.getString("createdAt"))
        assertEquals(Hex.encode(ltk(0x09)), raw.getString("ltk"))
    }

    /**
     * Undo on the PC-Mirror tab's swipe has to be an undo, not a fresh pairing.
     *
     * Re-adding through [PcLinkPairingStore.addOrUpdate] restores the key but stamps the record as
     * seen just now — and since the record was already deleted, as *paired* just now too — so the
     * row comes back at the top of a list sorted by last contact instead of where the user swiped
     * it from, claiming a conversation with the PC that never happened.
     */
    @Test
    fun forgetHandsBackTheRecordSoUndoPutsItBackUnchanged() {
        val s = store()
        val office = "a".repeat(64)
        s.addOrUpdate(office, "Office", ltk(0x01), host = "192.168.1.10")
        // A field only a future version of the app knows about: an undo must not drop it either.
        pairingBacking.put(
            office,
            JSONObject(pairingBacking.get(office)!!).put("futureField", "keep me").toString()
        )
        clock = clock.plusSeconds(86_400)
        s.addOrUpdate("b".repeat(64), "Home", ltk(0x02), host = "192.168.1.11")
        assertEquals(listOf("Home", "Office"), s.getAll().map { it.name })

        val before = s.get(office)!!
        val forgotten = s.forget(office)!!
        assertNull(s.get(office))

        // The undo lands later than the swipe, as it always does — the record must not notice.
        clock = clock.plusSeconds(30)
        s.restore(office, forgotten)

        val after = s.get(office)!!
        assertEquals(before, after)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.lastSeenAt, after.lastSeenAt)
        assertArrayEquals(before.ltk, after.ltk)
        assertEquals("keep me", JSONObject(pairingBacking.get(office)!!).getString("futureField"))
        assertEquals("back where it was swiped from", listOf("Home", "Office"), s.getAll().map { it.name })
    }

    /** Nothing to hand back for a PC we were not paired with — the caller offers no undo. */
    @Test
    fun forgettingSomethingWeNeverHadReturnsNothing() {
        assertNull(store().forget("c".repeat(64)))
    }

    @Test
    fun plainCipherRoundTripsAndRejectsJunk() {
        val sealed = PcLinkPairingStore.PlainSecretCipher.seal(ltk(0x5a))
        assertArrayEquals(ltk(0x5a), PcLinkPairingStore.PlainSecretCipher.open(sealed))
        assertNull(PcLinkPairingStore.PlainSecretCipher.open("zz"))
    }

    @Test
    fun twoDevicesDoNotShareAnIdentity() {
        val a = store().identity()
        val other = PcLinkPairingStore(
            FakeBacking(), FakeBacking(), PcLinkPairingStore.PlainSecretCipher, { clock }
        ).identity()
        assertNotEquals(a.fingerprint, other.fingerprint)
    }
}
