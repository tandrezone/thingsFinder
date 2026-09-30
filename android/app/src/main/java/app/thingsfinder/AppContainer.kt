package app.thingsfinder

import android.content.Context
import app.thingsfinder.data.BarcodeRepository
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.SettingsRepository
import app.thingsfinder.data.backup.BackupRepository
import app.thingsfinder.data.db.DatabaseFiles
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.lookup.OpenBarcodeLookup
import app.thingsfinder.platform.ContentResolverFileIo
import app.thingsfinder.platform.FileIo
import app.thingsfinder.platform.TextScanner
import app.thingsfinder.sync.CloudApi
import app.thingsfinder.sync.CloudSession
import app.thingsfinder.sync.OkHttpCloudApi
import app.thingsfinder.sync.SyncEngine
import app.thingsfinder.sync.SyncScheduler

/**
 * Manual dependency injection: the app is small enough that Hilt would add
 * more build machinery than it saves. One instance lives on [ThingsFinderApp].
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ThingsFinderDatabase by lazy { ThingsFinderDatabase.create(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val cloudSession: CloudSession by lazy { CloudSession(appContext) }
    val cloudApi: CloudApi by lazy { OkHttpCloudApi() }
    val syncEngine: SyncEngine by lazy { SyncEngine(database, cloudApi, cloudSession) }
    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }
    private val onLocalChange: () -> Unit = { syncScheduler.requestSoon() }

    val inventory: InventoryRepository by lazy { InventoryRepository(database, onChange = onLocalChange) }
    val barcodes: BarcodeRepository by lazy { BarcodeRepository(database, OpenBarcodeLookup(), settings, onChange = onLocalChange) }
    val backup: BackupRepository by lazy { BackupRepository(database) }
    val databaseFiles: DatabaseFiles by lazy { DatabaseFiles(appContext, { database }) }
    val textScanner: TextScanner by lazy { TextScanner(appContext) }
    val files: FileIo by lazy { ContentResolverFileIo(appContext) }
}
