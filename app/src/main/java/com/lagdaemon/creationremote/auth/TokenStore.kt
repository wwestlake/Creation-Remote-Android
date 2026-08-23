package com.lagdaemon.creationremote.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "creation_remote_auth")

data class StoredSession(
    val token: String,
    val userId: String,
    val email: String,
    val expiresAtEpochMs: Long
)

/** Local persistence for the signed-in session -- this device's own copy, not shared with the desktop suite. */
class TokenStore(private val context: Context) {
    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val EXPIRES_AT = stringPreferencesKey("expires_at")
    }

    val session: Flow<StoredSession?> = context.authDataStore.data.map { prefs ->
        val token = prefs[Keys.TOKEN] ?: return@map null
        StoredSession(
            token = token,
            userId = prefs[Keys.USER_ID] ?: "",
            email = prefs[Keys.EMAIL] ?: "",
            expiresAtEpochMs = prefs[Keys.EXPIRES_AT]?.toLongOrNull() ?: 0L
        )
    }

    suspend fun currentSession(): StoredSession? = session.first()

    suspend fun save(session: StoredSession) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.TOKEN] = session.token
            prefs[Keys.USER_ID] = session.userId
            prefs[Keys.EMAIL] = session.email
            prefs[Keys.EXPIRES_AT] = session.expiresAtEpochMs.toString()
        }
    }

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }
}
