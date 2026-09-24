package app.thingsfinder.platform

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface ScanResult {
    data class Scanned(val value: String) : ScanResult
    data object Cancelled : ScanResult
    data class Failed(val message: String) : ScanResult
}

/**
 * Google code scanner (Play services): shows its own camera UI, needs no
 * CAMERA permission in this app, and hands back only the decoded value.
 * Replaces the web app's BarcodeDetector-based assets/scan.js.
 */
object CodeScanner {
    suspend fun scanProductBarcode(context: Context): ScanResult = scan(
        context,
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
            )
            .enableAutoZoom()
            .build(),
    )

    suspend fun scanQr(context: Context): ScanResult = scan(
        context,
        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build(),
    )

    private suspend fun scan(context: Context, options: GmsBarcodeScannerOptions): ScanResult =
        suspendCancellableCoroutine { cont ->
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val value = barcode.rawValue?.trim().orEmpty()
                    cont.resume(if (value.isEmpty()) ScanResult.Failed("Couldn't read that code.") else ScanResult.Scanned(value))
                }
                .addOnCanceledListener { cont.resume(ScanResult.Cancelled) }
                .addOnFailureListener { e ->
                    val message = if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                        "The scanner is still downloading — try again in a moment."
                    } else {
                        "The scanner isn't available on this phone. Type the code instead."
                    }
                    cont.resume(ScanResult.Failed(message))
                }
        }
}
