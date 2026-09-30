package app.thingsfinder.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Everything the phone remembers about its cloud account. Kept in its own
 * DataStore file ("cloud"), which is excluded from Android's automatic
 * backup (res/xml/backup_rules.xml) so the token never lands in a Google
 * backup or on another device.
 */
data class CloudState(
    /** "Sync to cloud" — on by default; nothing happens until an account is signed in. */
    val enabled: Boolean = true,
    val serverUrl: String = ServerUrl.DEFAULT,
    val username: String? = null,
    val token: String? = null,
    /** server_time from the last successful sync — "send me what changed since". */
    val cursor: Long = 0,
    /** Local clock when the last successful push was built — "send what changed since". */
    val lastPushAt: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
) {
    val signedIn: Boolean get() = token != null
}

interface CloudSessionStore {
    val state: Flow<CloudState>
    suspend fun current(): CloudState = state.first()
    suspend fun setEnabled(enabled: Boolean)
    suspend fun signedIn(serverUrl: String, username: String, token: String)
    /** Signing out also forgets the cursors, so the next sign-in (maybe another account) does a full sync. */
    suspend fun signedOut(error: String? = null)
    suspend fun synced(cursor: Long, lastPushAt: Long, at: Long)
    suspend fun failed(error: String)
    /** After importing a database or restoring a backup: resend everything and fetch everything. */
    suspend fun resetCursors()
}

private val Context.cloudStore: DataStore<Preferences> by preferencesDataStore(name = "cloud")

class CloudSession(context: Context) : CloudSessionStore {
    private val store = context.applicationContext.cloudStore

    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val server = stringPreferencesKey("server_url")
        val username = stringPreferencesKey("username")
        val token = stringPreferencesKey("token")
        val cursor = longPreferencesKey("cursor")
        val lastPush = longPreferencesKey("last_push_at")
        val lastSync = longPreferencesKey("last_sync_at")
        val lastError = stringPreferencesKey("last_error")
    }

    override val state: Flow<CloudState> = store.data.map { p ->
        CloudState(
            enabled = p[Keys.enabled] ?: true,
            serverUrl = p[Keys.server] ?: ServerUrl.DEFAULT,
            username = p[Keys.username],
            token = p[Keys.token],
            cursor = p[Keys.cursor] ?: 0,
            lastPushAt = p[Keys.lastPush] ?: 0,
            lastSyncAt = p[Keys.lastSync] ?: 0,
            lastError = p[Keys.lastError],
        )
    }

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[Keys.enabled] = enabled }
    }

    override suspend fun signedIn(serverUrl: String, username: String, token: String) {
        store.edit {
            it[Keys.server] = serverUrl
            it[Keys.username] = username
            it[Keys.token] = token
            it[Keys.cursor] = 0
            it[Keys.lastPush] = 0
            it.remove(Keys.lastError)
        }
    }

    override suspend fun signedOut(error: String?) {
        store.edit {
            it.remove(Keys.token)
            it[Keys.cursor] = 0
            it[Keys.lastPush] = 0
            if (error != null) it[Keys.lastError] = error else it.remove(Keys.lastError)
        }
    }

    override suspend fun synced(cursor: Long, lastPushAt: Long, at: Long) {
        store.edit {
            it[Keys.cursor] = cursor
            it[Keys.lastPush] = lastPushAt
            it[Keys.lastSync] = at
            it.remove(Keys.lastError)
        }
    }

    override suspend fun failed(error: String) {
        store.edit { it[Keys.lastError] = error }
    }

    override suspend fun resetCursors() {
        store.edit {
            it[Keys.cursor] = 0
            it[Keys.lastPush] = 0
        }
    }
}
