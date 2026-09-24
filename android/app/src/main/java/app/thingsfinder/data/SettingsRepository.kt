package app.thingsfinder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

interface Settings {
    /** PHP: EXTERNAL_BARCODE_LOOKUP_ENABLED — on by default, like the web app. */
    val externalLookupEnabled: Flow<Boolean>
    suspend fun setExternalLookupEnabled(enabled: Boolean)
}

class SettingsRepository(context: Context) : Settings {
    private val store = context.settingsStore
    private val externalLookupKey = booleanPreferencesKey("external_barcode_lookup")

    override val externalLookupEnabled: Flow<Boolean> = store.data.map { it[externalLookupKey] ?: true }

    override suspend fun setExternalLookupEnabled(enabled: Boolean) {
        store.edit { it[externalLookupKey] = enabled }
    }

    suspend fun isExternalLookupEnabled(): Boolean = externalLookupEnabled.first()
}
