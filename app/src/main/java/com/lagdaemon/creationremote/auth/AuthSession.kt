package com.lagdaemon.creationremote.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val AUTH_SITE_BASE = "https://lagdaemon.com"
private const val TOKEN_API_BASE = "https://lagdaemon.com/djehuti"

/**
 * Same desktop OAuth2/PKCE flow the suite's desktop apps use
 * (SuiteDesktopAuthSession.cpp / DesktopAuthRepository.fs), reused as-is:
 * a loopback HTTP redirect (RFC 8252), not a custom URI scheme -- the
 * server only accepts loopback redirect_uri values.
 */
class AuthSession(private val context: Context) {
    private val client = OkHttpClient()
    val tokenStore = TokenStore(context)

    val session: Flow<StoredSession?> get() = tokenStore.session

    /** Opens a Custom Tab for sign-in and suspends until the user completes it. Throws on failure. */
    suspend fun signIn(): StoredSession = withContext(Dispatchers.IO) {
        val server = LoopbackServer()
        try {
            val codeVerifier = PkceUtil.generateCodeVerifier()
            val codeChallenge = PkceUtil.codeChallenge(codeVerifier)
            val state = PkceUtil.generateState()
            val redirectUri = "http://127.0.0.1:${server.port}"

            val authorizeUrl = Uri.parse("$AUTH_SITE_BASE/auth/desktop").buildUpon()
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("app", "creation-remote-android")
                .build()

            withContext(Dispatchers.Main) {
                CustomTabsIntent.Builder().build().launchUrl(context, authorizeUrl)
            }

            val redirectParams = server.awaitRedirect()
            val code = redirectParams["code"] ?: throw IllegalStateException("No authorization code returned")
            if (redirectParams["state"] != state) throw IllegalStateException("State mismatch -- possible CSRF")

            exchangeCodeForToken(code, codeVerifier)
        } finally {
            server.close()
        }
    }

    private suspend fun exchangeCodeForToken(code: String, codeVerifier: String): StoredSession =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("code", code)
                put("codeVerifier", codeVerifier)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$TOKEN_API_BASE/api/auth/desktop/token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("Token exchange failed (HTTP ${response.code}): $text")

                val json = JSONObject(text)
                val user = json.getJSONObject("user")
                val session = StoredSession(
                    token = json.getString("token"),
                    userId = user.getString("id"),
                    email = user.getString("email"),
                    // expiresAt from the API is an ISO-8601 instant; the client only needs
                    // "is it plausibly still valid," so a coarse local re-auth-on-401 policy
                    // covers correctness without needing a full ISO-8601 parser here.
                    expiresAtEpochMs = System.currentTimeMillis() + 29L * 24 * 60 * 60 * 1000
                )
                tokenStore.save(session)
                session
            }
        }

    suspend fun signOut() = tokenStore.clear()
}
