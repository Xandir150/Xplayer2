package com.teleteh.xplayer2.data.network

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.teleteh.xplayer2.util.crypto.Hex
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One paired PC. [serverId] is the server's identity fingerprint (lowercase-hex SHA-256 of its
 * X25519 public key) and is the storage key — a PC that changes name or IP is still the same
 * pairing, and a PC that regenerates its identity is correctly a different one.
 *
 * [serverId] and [name] are both authenticated: they come out of a ceremony the user confirmed with
 * a 6-digit code, not from a discovery reply or an invite. That is what makes this record the right
 * thing to label a paired device with — see `PcConnectActivity.pairedNameFor`.
 *
 * [lastHost] is phone-side bookkeeping, not part of the protocol: until the discovery reply carries
 * `serverId` (design §9.1), it is how [PcLinkPairingStore.findByHost] guesses which stored LTK to
 * try first. A wrong guess is harmless — it just fails the server's proof, and
 * [PcLinkPairingStore.getAll] then supplies the other candidates.
 */
data class PcLinkPairing(
    val serverId: String,
    val name: String,
    val ltk: ByteArray,
    val createdAt: String,
    val lastSeenAt: String,
    val lastHost: String? = null
) {
    // Byte-array field: the generated equals/hashCode would compare by identity.
    override fun equals(other: Any?): Boolean = other is PcLinkPairing &&
        serverId == other.serverId && name == other.name && ltk.contentEquals(other.ltk) &&
        createdAt == other.createdAt && lastSeenAt == other.lastSeenAt && lastHost == other.lastHost

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + ltk.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastSeenAt.hashCode()
        result = 31 * result + (lastHost?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PcLinkPairing(serverId=${serverId.take(8)}…, name=$name, lastSeenAt=$lastSeenAt)"
}

/**
 * Persistence for PC Link pairing: this phone's long-term X25519 identity, plus one record per
 * paired PC.
 *
 * Follows the [SmbStorage] convention — plain [SharedPreferences], `MODE_PRIVATE`, one prefs file
 * per concern, key = identifier / value = payload — as the pairing design (§11.2) specifies. Two
 * files: `pc_link_identity` (a single `secretKey`) and `pc_link_pairings` (fingerprint → JSON
 * record).
 *
 * Secrets — the identity private key and every LTK — are sealed with an AES-256-GCM key held in
 * the AndroidKeyStore, so a backup or a filesystem read off a rooted device doesn't hand over the
 * key material along with the JSON. If that Keystore key is unavailable or has been invalidated,
 * the sealed values simply don't open and the affected pairings are reported as gone: re-pairing is
 * a 6-digit code away, which is exactly why the design calls this cheap. (Jetpack's
 * `EncryptedSharedPreferences` is deprecated; this is the replacement it points at.)
 *
 * Everything Android-specific sits behind [Backing] and [SecretCipher], so the record encoding,
 * re-pair semantics and host lookup are covered by plain JVM unit tests.
 */
class PcLinkPairingStore internal constructor(
    private val identityBacking: Backing,
    private val pairingBacking: Backing,
    private val cipher: SecretCipher,
    private val now: () -> Instant = { Instant.now() },
    // Defaults to silence rather than to android.util.Log: the local unit tests run against the
    // JVM's stubbed android.jar, where every Log call throws. The Context constructor below wires
    // the real logger, so devices still get the message.
    private val warn: (String) -> Unit = {}
) {

    constructor(context: Context) : this(
        identityBacking = PrefsBacking(context, PREFS_IDENTITY),
        pairingBacking = PrefsBacking(context, PREFS_PAIRINGS),
        cipher = KeystoreSecretCipher.createOrPlain(),
        warn = { Log.w(TAG, it) }
    )

    /**
     * This phone's long-term identity, generated and stored on first use.
     *
     * Synchronized because two entry points can race for it (the connect flow and the reverse
     * -discovery responder, which needs the fingerprint for its probe reply) and generating two
     * identities would silently invalidate every existing pairing.
     */
    @Synchronized
    fun identity(): PcLinkPairingCrypto.Identity {
        val stored = identityBacking.get(KEY_SECRET_KEY)
            ?.let { cipher.open(it) }
            ?.let { PcLinkPairingCrypto.identityFromPrivateKey(it) }
        if (stored != null) return stored

        val fresh = PcLinkPairingCrypto.generateIdentity()
        identityBacking.put(KEY_SECRET_KEY, cipher.seal(fresh.privateKey))
        return fresh
    }

    /** Every stored pairing, newest-contact first — the order the UI wants for a "paired PCs" list. */
    fun getAll(): List<PcLinkPairing> = pairingBacking.all()
        .mapNotNull { (serverId, json) -> decode(serverId, json) }
        .sortedWith(compareByDescending<PcLinkPairing> { it.lastSeenAt }.thenBy { it.name.lowercase() })

    fun get(serverId: String): PcLinkPairing? =
        pairingBacking.get(serverId)?.let { decode(serverId, it) }

    fun isPaired(serverId: String): Boolean = pairingBacking.get(serverId) != null

    /**
     * The pairing most likely to belong to the PC at [host]: the one last seen there. Only a hint —
     * see [PcLinkPairing.lastHost]. Null when no stored pairing has ever been reached at that
     * address (a never-paired PC, so the caller runs the pairing ceremony instead of authenticating).
     */
    fun findByHost(host: String): PcLinkPairing? {
        if (host.isBlank()) return null
        return getAll().firstOrNull { it.lastHost.equals(host, ignoreCase = true) }
    }

    /**
     * Stores a pairing, or refreshes an existing one. Re-pairing the same PC overwrites the LTK
     * (each ceremony derives a fresh one) but keeps the original [PcLinkPairing.createdAt] — the
     * record is the relationship, not the handshake.
     */
    fun addOrUpdate(
        serverId: String,
        name: String,
        ltk: ByteArray,
        host: String? = null
    ): PcLinkPairing {
        val timestamp = timestamp()
        val existing = pairingBacking.get(serverId)?.let { runCatching { JSONObject(it) }.getOrNull() }
        // Start from the stored object so fields written by a future version of the app (or by a
        // future protocol revision) survive a rewrite by this one, matching the server's store.
        val record = existing ?: JSONObject()
        record.put(FIELD_NAME, name)
        record.put(FIELD_LTK, cipher.seal(ltk))
        if (!record.has(FIELD_CREATED_AT)) record.put(FIELD_CREATED_AT, timestamp)
        record.put(FIELD_LAST_SEEN_AT, timestamp)
        if (host != null) record.put(FIELD_LAST_HOST, host)
        pairingBacking.put(serverId, record.toString())
        return PcLinkPairing(
            serverId = serverId,
            name = name,
            ltk = ltk,
            createdAt = record.optString(FIELD_CREATED_AT, timestamp),
            lastSeenAt = timestamp,
            lastHost = record.optString(FIELD_LAST_HOST).ifEmpty { null }
        )
    }

    /**
     * Records a successful re-authentication: refreshes `lastSeenAt`, and the PC's name and address
     * if they changed. Does not create a record — a PC we aren't paired with has nothing to touch.
     */
    fun touch(serverId: String, host: String? = null, name: String? = null) {
        val raw = pairingBacking.get(serverId) ?: return
        val record = runCatching { JSONObject(raw) }.getOrNull() ?: return
        record.put(FIELD_LAST_SEEN_AT, timestamp())
        if (host != null) record.put(FIELD_LAST_HOST, host)
        if (!name.isNullOrBlank()) record.put(FIELD_NAME, name)
        pairingBacking.put(serverId, record.toString())
    }

    /** "Forget this PC". Purely local: the PC finds out at our next `auth_challenge`. */
    fun forget(serverId: String) = pairingBacking.remove(serverId)

    private fun decode(serverId: String, json: String): PcLinkPairing? {
        val record = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val ltk = record.optString(FIELD_LTK).takeIf { it.isNotEmpty() }?.let { cipher.open(it) }
        if (ltk == null || ltk.size != PcLinkPairingCrypto.KEY_LEN) {
            // Unopenable (Keystore key rotated away, restored backup) or corrupt: report it as not
            // paired rather than handing the FSM a key that can only fail the server's proof.
            warn("Dropping unreadable pairing record for ${serverId.take(8)}…")
            return null
        }
        val name = record.optString(FIELD_NAME).ifEmpty { serverId.take(8) }
        return PcLinkPairing(
            serverId = serverId,
            name = name,
            ltk = ltk,
            createdAt = record.optString(FIELD_CREATED_AT),
            lastSeenAt = record.optString(FIELD_LAST_SEEN_AT),
            lastHost = record.optString(FIELD_LAST_HOST).ifEmpty { null }
        )
    }

    /** ISO-8601 UTC to the second, the same shape the server writes into `pairings.json`. */
    private fun timestamp(): String = now().truncatedTo(ChronoUnit.SECONDS).toString()

    /** The couple of [SharedPreferences] operations this store needs, so tests can supply a map. */
    internal interface Backing {
        fun all(): Map<String, String>
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    internal class PrefsBacking(context: Context, name: String) : Backing {
        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

        override fun all(): Map<String, String> = prefs.all.entries
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()

        override fun get(key: String): String? = prefs.getString(key, null)

        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    /** Turns secret bytes into something storable and back. */
    internal interface SecretCipher {
        fun seal(secret: ByteArray): String

        /** Null when the value can't be recovered — the caller treats that as "not paired". */
        fun open(sealed: String): ByteArray?
    }

    /** Fallback when the device has no usable Keystore: app-private storage, plain hex. */
    internal object PlainSecretCipher : SecretCipher {
        override fun seal(secret: ByteArray): String = Hex.encode(secret)
        override fun open(sealed: String): ByteArray? = Hex.decode(sealed)
    }

    /**
     * AES-256-GCM under a non-exportable AndroidKeyStore key (alias [KEY_ALIAS], no user
     * authentication required so pairing works on a locked phone). Sealed values are
     * `{"iv": "<hex>", "ct": "<hex>"}`.
     */
    internal class KeystoreSecretCipher private constructor(private val key: SecretKey) : SecretCipher {

        override fun seal(secret: ByteArray): String {
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                JSONObject()
                    .put(FIELD_IV, Hex.encode(cipher.iv))
                    .put(FIELD_CT, Hex.encode(cipher.doFinal(secret)))
                    .toString()
            } catch (e: Exception) {
                // Storing the secret in the clear beats failing to pair at all: the fallback is
                // app-private storage, which an attacker can only read if they already own the phone.
                Log.w(TAG, "Keystore seal failed, storing unwrapped", e)
                PlainSecretCipher.seal(secret)
            }
        }

        override fun open(sealed: String): ByteArray? {
            // Plain hex: written before this device had a Keystore key, or by the fallback above.
            if (!sealed.startsWith("{")) return PlainSecretCipher.open(sealed)
            return try {
                val json = JSONObject(sealed)
                val iv = Hex.decode(json.getString(FIELD_IV)) ?: return null
                val ct = Hex.decode(json.getString(FIELD_CT)) ?: return null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(ct)
            } catch (e: Exception) {
                Log.w(TAG, "Keystore open failed; treating the record as forgotten", e)
                null
            }
        }

        companion object {
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val KEYSTORE = "AndroidKeyStore"
            private const val KEY_ALIAS = "xpl_pairing_master"
            private const val GCM_TAG_BITS = 128
            private const val FIELD_IV = "iv"
            private const val FIELD_CT = "ct"

            /** The Keystore-backed cipher, or [PlainSecretCipher] on a device that won't provide one. */
            fun createOrPlain(): SecretCipher = try {
                KeystoreSecretCipher(loadOrCreateKey())
            } catch (e: Exception) {
                Log.w(TAG, "No usable AndroidKeyStore; pairing secrets stay app-private only", e)
                PlainSecretCipher
            }

            private fun loadOrCreateKey(): SecretKey {
                val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    ?.let { return it }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
                return generator.generateKey()
            }
        }
    }

    companion object {
        private const val TAG = "PcLinkPairingStore"

        internal const val PREFS_IDENTITY = "pc_link_identity"
        internal const val PREFS_PAIRINGS = "pc_link_pairings"
        internal const val KEY_SECRET_KEY = "secretKey"

        internal const val FIELD_NAME = "name"
        internal const val FIELD_LTK = "ltk"
        internal const val FIELD_CREATED_AT = "createdAt"
        internal const val FIELD_LAST_SEEN_AT = "lastSeenAt"
        internal const val FIELD_LAST_HOST = "lastHost"
    }
}
