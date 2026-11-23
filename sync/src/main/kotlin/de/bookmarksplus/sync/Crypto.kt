package de.bookmarksplus.sync

import de.bookmarksplus.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : Exception(tr("sync.crypto.wrongPassphrase"))

/**
 * Key parameters, stored unencrypted as `keyinfo.json` at the sync target. `check` is a known text
 * encrypted with the derived key – so a device immediately detects a wrong passphrase.
 */
@Serializable
data class KeyInfo(
    val version: Int = 1,
    val kdf: String = "argon2id",
    val salt: String,
    val memoryKb: Int = 65_536,
    val iterations: Int = 3,
    val parallelism: Int = 1,
    val check: String = "",
)

/**
 * End-to-end encryption: key from the passphrase via Argon2id, data with AES-256-GCM.
 * File format: `BMP1` | nonce (12 bytes) | ciphertext + tag. The file name is additional authenticated information
 * so that files at the target cannot be swapped. Contents are compressed with GZIP beforehand.
 */
object Crypto {
    private val MAGIC = "BMP1".toByteArray()
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private const val CHECK_TEXT = "bookmarks-plus-key-check"

    fun newKeyInfo(passphrase: CharArray): Pair<KeyInfo, ByteArray> {
        val salt = ByteArray(16).also(random::nextBytes)
        val info = KeyInfo(salt = Base64.getEncoder().encodeToString(salt))
        val key = derive(passphrase, info)
        return info.copy(check = Base64.getEncoder().encodeToString(seal(key, CHECK_TEXT.toByteArray(), "keycheck", compress = false))) to key
    }

    /** Derives the key and checks it against `check`. */
    fun unlock(passphrase: CharArray, info: KeyInfo): ByteArray {
        val key = derive(passphrase, info)
        val ok = runCatching { String(open(key, Base64.getDecoder().decode(info.check), "keycheck")) == CHECK_TEXT }.getOrDefault(false)
        if (!ok) throw WrongPassphraseException()
        return key
    }

    fun derive(passphrase: CharArray, info: KeyInfo): ByteArray {
        require(info.kdf == "argon2id") { tr("sync.crypto.unknownKdf", info.kdf) }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(Base64.getDecoder().decode(info.salt))
            .withMemoryAsKB(info.memoryKb)
            .withIterations(info.iterations)
            .withParallelism(info.parallelism)
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, out)
        return out
    }

    fun seal(key: ByteArray, data: ByteArray, name: String, compress: Boolean = true): ByteArray {
        val plain = if (compress) gzip(data) else data
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(name.toByteArray())
        val ct = cipher.doFinal(plain)
        return MAGIC + nonce + ct
    }

    fun open(key: ByteArray, blob: ByteArray, name: String): ByteArray {
        if (blob.size < 4 + 12 + 16 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) throw WrongPassphraseException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 4, 12))
        cipher.updateAAD(name.toByteArray())
        val plain = try {
            cipher.doFinal(blob, 16, blob.size - 16)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        }
        return if (plain.size >= 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) gunzip(plain) else plain
    }

    fun encodeKeyInfo(info: KeyInfo): ByteArray = json.encodeToString(KeyInfo.serializer(), info).toByteArray()
    fun decodeKeyInfo(bytes: ByteArray): KeyInfo = json.decodeFromString(KeyInfo.serializer(), String(bytes))

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
