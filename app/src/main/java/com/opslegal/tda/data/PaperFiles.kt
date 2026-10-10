package com.opslegal.tda.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.opslegal.tda.core.agent.ChatItem
import com.opslegal.tda.core.plan.Paperwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * The phone side of documents and expenses: photos the app takes (receipts, scans), files the user picks (kept where
 * they are, by a lasting permission), what the AI is shown, and reports handed to the share menu. Nothing is uploaded.
 */
object PaperFiles {
    private fun authority(c: Context) = "${c.packageName}.files"

    /** A new photo file and its content URI, for the camera. */
    fun newPhoto(c: Context): Uri {
        val dir = File(c.filesDir, "docs").apply { mkdirs() }
        return FileProvider.getUriForFile(c, authority(c), File(dir, "${UUID.randomUUID().toString().take(8)}.jpg"))
    }

    /** Keeps a picked file reachable later (the file stays where it is). */
    fun keep(c: Context, uri: Uri) { runCatching { c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }

    fun name(c: Context, uri: Uri): String = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"

    fun type(c: Context, uri: Uri): String = c.contentResolver.getType(uri) ?: if (uri.toString().endsWith(".jpg")) "image/jpeg" else ""

    /** What the AI is shown: a photo made smaller (JPEG), or a PDF as is (up to 4 MB). Null for other files. */
    fun attachment(c: Context, uri: Uri): ChatItem.Attachment? = runCatching {
        val t = type(c, uri)
        when {
            t.startsWith("image/") -> {
                val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
                val k = minOf(1f, 1568f / maxOf(src.width, src.height))
                val img = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
                val out = ByteArrayOutputStream(); img.compress(Bitmap.CompressFormat.JPEG, 85, out)
                ChatItem.Attachment("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
            t == "application/pdf" -> {
                val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                if (bytes.size > 4 * 1024 * 1024) null else ChatItem.Attachment(t, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            else -> null
        }
    }.getOrNull()

    /** Opens a document with the phone's own viewer (or the browser for a link). */
    fun open(c: Context, uri: String) {
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun shareFile(c: Context, name: String, mime: String, write: (File) -> Unit) {
        val dir = File(c.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name); write(f)
        val uri = FileProvider.getUriForFile(c, authority(c), f)
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send the expense report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareCsv(c: Context, name: String, rows: List<Paperwork.Row>) = shareFile(c, name, "text/csv") { it.writeText(Paperwork.csv(rows)) }

    /** The report as a PDF: a table and the totals, then one page per receipt photo. The user sends it. */
    fun sharePdf(c: Context, name: String, title: String, rows: List<Paperwork.Row>) = shareFile(c, name, "application/pdf") { f ->
        val pdf = PdfDocument()
        val text = Paint().apply { textSize = 10f }
        val bold = Paint().apply { textSize = 10f; isFakeBoldText = true }
        var n = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
        var y = 50f
        page.canvas.drawText(title, 40f, y, Paint().apply { textSize = 16f; isFakeBoldText = true }); y += 26f
        val cols = floatArrayOf(40f, 100f, 230f, 300f, 430f, 490f)
        listOf("Date", "Where", "Category", "Project or task", "Taxes", "Total").forEachIndexed { i, h -> page.canvas.drawText(h, cols[i], y, bold) }; y += 16f
        rows.forEach { (e, w) ->
            if (y > 800f) { pdf.finishPage(page); n++; page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 50f }
            listOf(e.date, e.vendor.take(22), e.category, w.take(22), "%.2f".format(e.tax), "%.2f".format(e.amount)).forEachIndexed { i, v -> page.canvas.drawText(v, cols[i], y, text) }
            y += 14f
        }
        val t = Paperwork.totals(rows)
        y += 10f
        listOf("Total ${Paperwork.money(t.total)}", "of which taxes ${Paperwork.money(t.taxes)}", "of which tips ${Paperwork.money(t.tips)}", "billable to clients ${Paperwork.money(t.billable)}")
            .forEach { page.canvas.drawText(it, 40f, y, bold); y += 14f }
        pdf.finishPage(page)
        rows.filter { it.e.photo.isNotBlank() }.forEach { (e, _) ->
            val bmp = runCatching { c.contentResolver.openInputStream(Uri.parse(e.photo))?.use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@forEach
            n++; val p = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            p.canvas.drawText("${e.date} · ${e.vendor.ifBlank { e.category }} · ${Paperwork.money(e.amount)}", 40f, 40f, bold)
            val k = minOf(515f / bmp.width, 740f / bmp.height)
            p.canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true), 40f, 60f, null)
            pdf.finishPage(p)
        }
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }
}
