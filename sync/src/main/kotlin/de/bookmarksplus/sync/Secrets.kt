package de.bookmarksplus.sync

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protects locally stored secrets (API keys, tokens, passwords, sync keys).
 * Windows: DPAPI – only the logged-in user can decrypt them. Otherwise: AES-GCM with a key file
 * that is readable only by the user. Protected values start with `dpapi:` or `aes:`.
 */
class Secrets(private val keyFile: File) {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val random = SecureRandom()

    fun isProtected(value: String) = value.startsWith("dpapi:") || value.startsWith("aes:")

    fun protect(plain: String): String {
        if (plain.isEmpty()) return ""
        val data = plain.toByteArray()
        if (windows) runCatching { return "dpapi:" + b64(Crypt32Util.cryptProtectData(data)) }
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey(), "AES"), GCMParameterSpec(128, nonce)) }
        return "aes:" + b64(nonce + c.doFinal(data))
    }

    /** Plaintext; unprotected (old) values are returned unchanged. */
    fun reveal(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return runCatching {
            when {
                stored.startsWith("dpapi:") -> String(Crypt32Util.cryptUnprotectData(unb64(stored.removePrefix("dpapi:"))))
                stored.startsWith("aes:") -> {
                    val b = unb64(stored.removePrefix("aes:"))
                    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey(), "AES"), GCMParameterSpec(128, b, 0, 12)) }
                    String(c.doFinal(b, 12, b.size - 12))
                }
                else -> stored
            }
        }.getOrDefault("")
    }

    private fun fileKey(): ByteArray {
        if (keyFile.isFile) return keyFile.readBytes()
        keyFile.parentFile?.mkdirs()
        val k = ByteArray(32).also(random::nextBytes)
        keyFile.writeBytes(k)
        runCatching { Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        return k
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
