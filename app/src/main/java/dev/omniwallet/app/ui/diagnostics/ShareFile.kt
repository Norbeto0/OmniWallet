package dev.omniwallet.app.ui.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands the diagnostics log to the system share sheet.
 *
 * This is the feedback loop for a project developed without the hardware it
 * drives: a log the user can send back turns "it did not connect" into an
 * actual GATT status code and bond transition.
 */
fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "OmniWallet diagnostics")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share diagnostics")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
