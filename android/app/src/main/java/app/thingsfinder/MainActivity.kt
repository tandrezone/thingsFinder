package app.thingsfinder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.thingsfinder.domain.AppLink
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.ui.navigation.AppRoot
import app.thingsfinder.ui.theme.ThingsFinderTheme

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<AppLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingLink = linkFrom(intent)
        setContent {
            ThingsFinderTheme {
                AppRoot(pendingLink = pendingLink, onLinkHandled = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    /** thingsfinder://box/…, place/… and join/… (see the manifest's intent filters and BoxLinks). */
    private fun linkFrom(intent: Intent?): AppLink? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(BoxLinks::parse)
}
