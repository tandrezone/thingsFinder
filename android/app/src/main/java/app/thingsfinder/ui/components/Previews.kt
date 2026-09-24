package app.thingsfinder.ui.components

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview

/** Phone light, phone dark and tablet — applied to every key screen's stateless content. */
@Preview(name = "Phone · light", device = Devices.PIXEL_7, showBackground = true)
@Preview(name = "Phone · dark", device = Devices.PIXEL_7, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Tablet · light", device = Devices.PIXEL_TABLET, showBackground = true)
@Preview(name = "Tablet · dark", device = Devices.PIXEL_TABLET, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ScreenPreviews

/** Phone light + dark only, for the smaller loading / empty / error variants. */
@Preview(name = "light", device = Devices.PIXEL_7, showBackground = true)
@Preview(name = "dark", device = Devices.PIXEL_7, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class StatePreviews
