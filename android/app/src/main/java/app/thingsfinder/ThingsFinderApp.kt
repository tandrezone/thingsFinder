package app.thingsfinder

import android.app.Application

class ThingsFinderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Cloud sync: hourly in the background, plus once shortly after start.
        // Both are no-ops while sync is off or no account is signed in.
        container.syncScheduler.schedulePeriodic()
        container.syncScheduler.requestSoon(delaySeconds = 3)
    }
}
