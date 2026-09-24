package app.thingsfinder

import android.content.Context
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.SettingsRepository
import app.thingsfinder.data.backup.BackupRepository
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.lookup.OpenBarcodeLookup
import app.thingsfinder.platform.ContentResolverFileIo
import app.thingsfinder.platform.FileIo
import app.thingsfinder.platform.TextScanner

/**
 * Manual dependency injection: the app is small enough that Hilt would add
 * more build machinery than it saves. One instance lives on [ThingsFinderApp].
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ThingsFinderDatabase by lazy { ThingsFinderDatabase.create(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val inventory: InventoryRepository by lazy { InventoryRepository(database) }
    val barcodes: BarcodeRepository by lazy { BarcodeRepository(database, OpenBarcodeLookup(), settings) }
    val backup: BackupRepository by lazy { BackupRepository(database) }
    val textScanner: TextScanner by lazy { TextScanner(appContext) }
    val files: FileIo by lazy { ContentResolverFileIo(appContext) }
}
