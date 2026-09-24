package app.thingsfinder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.lookup.BarcodeNameLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

fun inMemoryDb(): ThingsFinderDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ThingsFinderDatabase::class.java)
        .allowMainThreadQueries()
        .build()

class FakeSettings(enabled: Boolean = true) : Settings {
    val flow = MutableStateFlow(enabled)
    override val externalLookupEnabled: Flow<Boolean> = flow
    override suspend fun setExternalLookupEnabled(enabled: Boolean) {
        flow.value = enabled
    }
}

class FakeLookup(var answer: app.thingsfinder.lookup.LookupSuggestion? = null) : BarcodeNameLookup {
    var calls = 0
    override suspend fun lookup(barcode: String) = answer.also { calls++ }
}
