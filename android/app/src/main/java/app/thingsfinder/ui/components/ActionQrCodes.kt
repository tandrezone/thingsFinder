package app.thingsfinder.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.thingsfinder.R
import app.thingsfinder.domain.LinkAction
import app.thingsfinder.domain.Slugs
import app.thingsfinder.platform.LabelRenderer
import app.thingsfinder.platform.QrCodes
import app.thingsfinder.platform.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Add item" and "Remove item" QR codes for a box or a place. Each encodes
 * the server's https://host/add|remove/{token} page (see BoxLinks), so the
 * in-app scanner opens the container with the add-item sheet up, or in
 * remove mode, and a phone camera opens the web page, which offers to open
 * the app. Each can be
 * shared as a bare QR or as a printable label.
 */
@Composable
fun ActionQrCard(name: String, isPlace: Boolean, addLink: String, removeLink: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareLabelTitle = stringResource(R.string.action_share_label)
    val shareQrTitle = stringResource(R.string.action_share_qr)
    val addCaption = stringResource(R.string.label_scan_to_add)
    val removeCaption = stringResource(R.string.label_scan_to_remove)

    fun shareLabel(action: LinkAction) = scope.launch {
        val add = action == LinkAction.Add
        val bmp = withContext(Dispatchers.Default) {
            LabelRenderer.renderAction(if (add) addLink else removeLink, name, isPlace, if (add) addCaption else removeCaption, add)
        }
        Sharing.sharePng(context, bmp, "label-${Slugs.slugify(name)}-${action.segment}.png", shareLabelTitle)
    }

    fun shareQr(action: LinkAction) = scope.launch {
        shareQrPng(context, if (action == LinkAction.Add) addLink else removeLink, "qr-${Slugs.slugify(name)}-${action.segment}.png", shareQrTitle)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (isPlace) R.string.action_qr_explainer_place else R.string.action_qr_explainer_box),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionQrTile(
                    title = stringResource(R.string.action_add_item),
                    content = addLink,
                    contentDescription = stringResource(R.string.cd_add_qr, name),
                    onShareLabel = { shareLabel(LinkAction.Add) },
                    onShareQr = { shareQr(LinkAction.Add) },
                    modifier = Modifier.weight(1f),
                )
                ActionQrTile(
                    title = stringResource(R.string.action_remove_item),
                    content = removeLink,
                    contentDescription = stringResource(R.string.cd_remove_qr, name),
                    onShareLabel = { shareLabel(LinkAction.Remove) },
                    onShareQr = { shareQr(LinkAction.Remove) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionQrTile(
    title: String,
    content: String,
    contentDescription: String,
    onShareLabel: () -> Unit,
    onShareQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val qr = remember(content) { QrCodes.bitmap(content, 300) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        QrImage(qr, contentDescription, Modifier.size(112.dp))
        OutlinedButton(onClick = onShareLabel, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_label_short))
        }
        OutlinedButton(onClick = onShareQr, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_qr_short))
        }
    }
}

/** Always on white, so the code stays scannable in dark theme too. */
@Composable
fun QrImage(qr: Bitmap, contentDescription: String, modifier: Modifier = Modifier) {
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = contentDescription,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(4.dp),
    )
}

/** Shares [content] as a bare 1024 px QR code PNG. */
suspend fun shareQrPng(context: Context, content: String, fileName: String, title: String) {
    val bmp = withContext(Dispatchers.Default) { QrCodes.bitmap(content, 1024, margin = 2) }
    Sharing.sharePng(context, bmp, fileName, title)
}
