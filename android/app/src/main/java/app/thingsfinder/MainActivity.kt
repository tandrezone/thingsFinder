package app.thingsfinder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.ui.navigation.AppRoot
import app.thingsfinder.ui.theme.ThingsFinderTheme

class MainActivity : ComponentActivity() {
    private var pendingBoxToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingBoxToken = tokenFrom(intent)
        setContent {
            ThingsFinderTheme {
                AppRoot(pendingBoxToken = pendingBoxToken, onBoxTokenHandled = { pendingBoxToken = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tokenFrom(intent)?.let { pendingBoxToken = it }
    }

    private fun tokenFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(BoxLinks::tokenFrom)
}
