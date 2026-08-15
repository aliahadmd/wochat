package com.aliahad.aichat.core

import java.security.MessageDigest

/** Shared hex SHA-256 used for stable ids, fingerprints, and digests. */
internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
