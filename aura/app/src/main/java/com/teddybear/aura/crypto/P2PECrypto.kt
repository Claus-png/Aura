package com.teddybear.aura.crypto

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "AuraP2PE"

/**
 * P2PE (Point-to-Point Encryption) module for AURA.
 *
 * 666-bit master key structure:
 *   [0..31]  = AES-256 key (32 bytes)   — symmetric encryption
 *   [32..63] = HMAC key  (32 bytes)     — integrity/auth
 *   [64..82] = Handshake salt (19 bytes = 152 bits, rounded to 154)
 *
 * Protocol per-request:
 *   1. Generate ephemeral X25519 key pair
 *   2. Derive session key via HKDF(X25519_shared + handshake_salt)
 *   3. Encrypt payload with AES-256-GCM (nonce = HMAC of counter)
 *   4. Send: [eph_pub(32) | salt(16) | ciphertext | tag(16)]
 */
object P2PECrypto {

    // Master key components (set after QR scan)
    private var aesKey:       ByteArray? = null
    private var hmacKey:      ByteArray? = null
    private var handshakeSalt: ByteArray? = null

    private val rng = SecureRandom()

    init {
        // Register BouncyCastle provider for X25519
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    val isReady: Boolean get() = aesKey != null

    // ── Key setup ─────────────────────────────────────────────────────────────

    /** Load 666-bit (84-byte) master key from Base64 string (from QR code). */
    fun loadMasterKey(base64Key: String): Boolean {
        return try {
            val raw = Base64.decode(base64Key, Base64.DEFAULT)
            if (raw.size < 84) throw IllegalArgumentException("Key too short: ${raw.size} bytes")
            aesKey        = raw.copyOfRange(0, 32)
            hmacKey       = raw.copyOfRange(32, 64)
            handshakeSalt = raw.copyOfRange(64, 83)   // 19 bytes = ~152 bits
            Log.i(TAG, "Master key loaded (${raw.size * 8} bits)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Key load failed: ${e.message}")
            false
        }
    }

    /** Generate a fresh 666-bit master key (for testing/first setup). */
    fun generateMasterKey(): String {
        val key = ByteArray(84).also { rng.nextBytes(it) }
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    // ── Encryption ────────────────────────────────────────────────────────────

    data class EncryptedPacket(
        val data: ByteArray,    // full wire packet: eph_pub | salt | ciphertext | tag
        val ephPub: ByteArray,  // 32-byte X25519 public key (sent so server can decrypt)
    )

    /**
     * Encrypt a plaintext payload for sending to the server.
     * Returns the full binary packet ready to be Base64-encoded and sent.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val aes = aesKey ?: error("P2PE not initialized — call loadMasterKey first")

        // 1. Ephemeral X25519 key pair
        val gen  = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val ephPriv = pair.private as X25519PrivateKeyParameters
        val ephPub  = pair.public  as X25519PublicKeyParameters

        // 2. Derive session key: HKDF(AES_key || eph_pub || handshakeSalt)
        val salt        = ByteArray(16).also { rng.nextBytes(it) }
        val sessionKey  = hkdf(
            inputKey = aes + ephPub.encoded + (handshakeSalt ?: ByteArray(0)),
            salt     = salt,
            info     = "AURA-P2PE-v1".toByteArray(),
            length   = 32,
        )

        // 3. Nonce = first 12 bytes of HMAC(session_key, "req")
        val nonce = hmacSha256(sessionKey, "req".toByteArray()).copyOf(12)

        // 4. AES-256-GCM encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        // 5. Wire format: eph_pub(32) | salt(16) | nonce(12) | ciphertext+tag
        return ephPub.encoded + salt + nonce + ciphertext
    }

    /**
     * Decrypt a server response.
     * Format mirror: eph_pub(32) | salt(16) | nonce(12) | ciphertext+tag
     */
    fun decrypt(packet: ByteArray): ByteArray {
        val aes = aesKey ?: error("P2PE not initialized")

        var offset = 0
        val serverEphPub = packet.copyOfRange(offset, offset + 32); offset += 32
        val salt         = packet.copyOfRange(offset, offset + 16); offset += 16
        val nonce        = packet.copyOfRange(offset, offset + 12); offset += 12
        val ciphertext   = packet.copyOfRange(offset, packet.size)

        val sessionKey = hkdf(
            inputKey = aes + serverEphPub + (handshakeSalt ?: ByteArray(0)),
            salt     = salt,
            info     = "AURA-P2PE-v1".toByteArray(),
            length   = 32,
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    // ── TLV payload helpers ───────────────────────────────────────────────────

    /** Wrap a string URL in TLV type 0x01 */
    fun wrapUrl(url: String): ByteArray = tlv(0x01, url.toByteArray())

    /** Wrap a JSON string in TLV type 0x03 */
    fun wrapJson(json: String): ByteArray = tlv(0x03, json.toByteArray())

    /** Unwrap TLV, return (type, payload) */
    fun unwrap(data: ByteArray): Pair<Int, ByteArray> {
        val type   = data[0].toInt() and 0xFF
        val length = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val payload = data.copyOfRange(3, 3 + length)
        return Pair(type, payload)
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray {
        val len = value.size
        return byteArrayOf(
            type.toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        ) + value
    }

    // ── Crypto primitives ─────────────────────────────────────────────────────

    private fun hkdf(inputKey: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // HKDF-SHA256 extract
        val prk  = hmacSha256(salt, inputKey)
        // HKDF expand
        val out  = ByteArray(length)
        var prev = ByteArray(0)
        var filled = 0; var counter = 1
        while (filled < length) {
            prev = hmacSha256(prk, prev + info + byteArrayOf(counter.toByte()))
            val copy = minOf(prev.size, length - filled)
            System.arraycopy(prev, 0, out, filled, copy)
            filled += copy; counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ByteArray concatenation helper
    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
