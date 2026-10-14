package com.opslegal.tda.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.opslegal.tda.data.PaperFiles

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/**
 * Scanning: the camera opens in document mode (Google's scanner): it finds the page's edges, crops and straightens it,
 * and lets the user retake. [pages] > 1 gives one PDF for a document of several pages; 1 gives one image (a receipt, a
 * bill). What comes back is kept small (see [PaperFiles.keepSmall]). Without Google Play services, a plain photo instead.
 * Returns the function that starts it.
 */
@Composable
internal fun rememberScanner(pages: Int = 1, onScanned: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        photo?.let { if (ok) onScanned(PaperFiles.keepSmall(context, it)) }
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val res = GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@rememberLauncherForActivityResult
        val pdf = res.pdf?.uri?.takeIf { pages > 1 && (res.pages?.size ?: 0) > 1 }
        val uri = pdf?.let { PaperFiles.keepSmall(context, it, pdf = true) }
            ?: res.pages?.firstOrNull()?.imageUri?.let { PaperFiles.keepSmall(context, it) }
        uri?.let(onScanned)
    }
    return remember(pages) {
        {
            val activity = context.activity()
            val fallback = { photo = PaperFiles.newPhoto(context); camera.launch(photo!!) }
            if (activity == null) fallback()
            else {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(false)
                    .setPageLimit(pages.coerceIn(1, 20))
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                    .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { fallback() }
            }
        }
    }
}
