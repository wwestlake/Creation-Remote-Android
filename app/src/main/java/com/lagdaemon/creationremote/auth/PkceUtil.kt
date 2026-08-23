package com.lagdaemon.creationremote.auth

import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** PKCE helpers matching the desktop suite's SuiteDesktopAuthSession.cpp exactly. */
object PkceUtil {
    private val random = SecureRandom()

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun generateState(): String = randomBase64Url(16)

    fun generateCodeVerifier(): String = randomBase64Url(32)

    fun codeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
