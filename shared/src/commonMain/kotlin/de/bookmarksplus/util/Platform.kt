package de.bookmarksplus.util

expect fun sha256Hex(data: ByteArray): String

expect fun newUuid(): String

expect fun nowEpochSeconds(): Long
