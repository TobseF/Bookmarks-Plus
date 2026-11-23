package de.bookmarksplus.util

import java.security.MessageDigest
import java.util.UUID

actual fun sha256Hex(data: ByteArray): String =
    // without java.util.HexFormat (only available on Android from API 34)
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

actual fun newUuid(): String = UUID.randomUUID().toString()

actual fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000
