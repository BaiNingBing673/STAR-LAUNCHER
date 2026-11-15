package com.star.launcher.data.security

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Encrypted secret storage contract for OAuth/Minecraft session tokens.
 * Tokens must never be placed in Room, SharedPreferences XML, or diagnostic logs.
 */
interface TokenVault {
    fun putSecret(alias: String, plaintextSecret: String)
    fun getSecret(alias: String): String?
    fun deleteSecret(alias: String)
    fun containsSecret(alias: String): Boolean
}

/**
 * Pure AES-256-GCM envelope codec (IV + ciphertext + auth tag) usable both on Android and JVM tests.
 */
internal object AesGcmEnvelopeCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(key: SecretKey, plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        val iv = cipher.iv
        require(iv.size == IV_LENGTH_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decrypt(key: SecretKey, payload: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val buf = ByteBuffer.wrap(payload)
        val ivLen = buf.int
        require(ivLen in 12..16) { "Corrupted IV header length: $ivLen" }
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val ciphertext = ByteArray(buf.remaining())
        buf.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(ciphertext)
    }
}

/**
 * Android Keystore-backed [TokenVault] writing encrypted blobs to `noBackupFilesDir`.
 * Uses native AndroidKeyStore AES-256-GCM on API 23+, and an RSA-wrapped AES-256 DEK on API 21–22.
 */
class AndroidKeystoreTokenVault(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : TokenVault {
    private val appContext = context.applicationContext
    private val vaultDir = File(appContext.noBackupFilesDir, "aether_vault").apply { mkdirs() }
    private val lock = Any()

    override fun putSecret(alias: String, plaintextSecret: String) {
        synchronized(lock) {
            val file = secretFileFor(alias)
            val key = getOrCreateAesKey()
            val aad = sanitizeAlias(alias).toByteArray(Charsets.UTF_8)
            val blob = AesGcmEnvelopeCodec.encrypt(
                key = key,
                plaintext = plaintextSecret.toByteArray(Charsets.UTF_8),
                associatedData = aad
            )
            file.writeBytes(blob)
        }
    }

    override fun getSecret(alias: String): String? {
        synchronized(lock) {
            val file = secretFileFor(alias)
            if (!file.isFile) return null
            val key = getOrCreateAesKey()
            val aad = sanitizeAlias(alias).toByteArray(Charsets.UTF_8)
            val plaintext = AesGcmEnvelopeCodec.decrypt(
                key = key,
                payload = file.readBytes(),
                associatedData = aad
            )
            return String(plaintext, Charsets.UTF_8)
        }
    }

    override fun deleteSecret(alias: String) {
        synchronized(lock) {
            secretFileFor(alias).delete()
        }
    }

    override fun containsSecret(alias: String): Boolean {
        synchronized(lock) {
            return secretFileFor(alias).isFile
        }
    }

    private fun secretFileFor(alias: String): File {
        val safe = Base64.encodeToString(
            sanitizeAlias(alias).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return File(vaultDir, "$safe.bin")
    }

    private fun sanitizeAlias(alias: String): String {
        require(alias.isNotBlank()) { "Secret alias cannot be blank" }
        return alias
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        } else {
            getOrCreateLegacyWrappedAesKey(keyStore)
        }
    }

    @Suppress("DEPRECATION")
    private fun getOrCreateLegacyWrappedAesKey(keyStore: KeyStore): SecretKey {
        val rsaAlias = "${keyAlias}_rsa_wrap"
        if (!keyStore.containsAlias(rsaAlias)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            val spec = KeyPairGeneratorSpec.Builder(appContext)
                .setAlias(rsaAlias)
                .setSubject(X500Principal("CN=AetherTokenVault"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        val wrappedDekFile = File(vaultDir, ".wrapped_dek.bin")
        val entry = keyStore.getEntry(rsaAlias, null) as KeyStore.PrivateKeyEntry
        if (wrappedDekFile.isFile) {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, entry.privateKey)
            val rawDek = cipher.doFinal(wrappedDekFile.readBytes())
            return SecretKeySpec(rawDek, "AES")
        }
        val rawDek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
        wrappedDekFile.writeBytes(cipher.doFinal(rawDek))
        return SecretKeySpec(rawDek, "AES")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "aether_token_vault_v1"
    }
}

/**
 * In-memory AES-256-GCM [TokenVault] implementation for deterministic JVM unit tests.
 */
class InMemoryEncryptedTokenVault : TokenVault {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val blobs = LinkedHashMap<String, ByteArray>()

    override fun putSecret(alias: String, plaintextSecret: String) {
        require(alias.isNotBlank()) { "Secret alias cannot be blank" }
        blobs[alias] = AesGcmEnvelopeCodec.encrypt(
            key = key,
            plaintext = plaintextSecret.toByteArray(Charsets.UTF_8),
            associatedData = alias.toByteArray(Charsets.UTF_8)
        )
    }

    override fun getSecret(alias: String): String? {
        val payload = blobs[alias] ?: return null
        val plain = AesGcmEnvelopeCodec.decrypt(
            key = key,
            payload = payload,
            associatedData = alias.toByteArray(Charsets.UTF_8)
        )
        return String(plain, Charsets.UTF_8)
    }

    override fun deleteSecret(alias: String) {
        blobs.remove(alias)
    }

    override fun containsSecret(alias: String): Boolean = blobs.containsKey(alias)

    /** Test-only inspection helper to verify ciphertext never contains plaintext bytes. */
    internal fun rawCiphertextBytes(alias: String): ByteArray? = blobs[alias]?.copyOf()
}
