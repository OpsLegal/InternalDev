package com.opslegal.tda.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The text of a receipt photo, read on the phone (Google's on-device reader, no AI, nothing sent). Receipts print the
 * label on the left and the amount on the right: the lines are put back together by their height on the page, so
 * "TPS 5% ... 3.70" reads as one line. Null for a PDF or when the photo can't be read.
 */
object ReceiptOcr {
    suspend fun text(c: Context, uri: Uri): String? {
        if (uri.toString().endsWith(".pdf") || c.contentResolver.getType(uri) == "application/pdf") return null
        val image = runCatching { InputImage.fromFilePath(c, uri) }.getOrNull() ?: return null
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { k ->
            client.process(image).addOnSuccessListener { k.resume(it) }.addOnFailureListener { k.resume(null) }
        } ?: return null
        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { b -> Triple(b.centerY(), b.left, l.text) to b.height() } }
        if (lines.isEmpty()) return result.text
        val rows = mutableListOf<MutableList<Triple<Int, Int, String>>>()
        for ((l, h) in lines.sortedBy { it.first.first }) {
            val row = rows.lastOrNull()?.takeIf { r -> kotlin.math.abs(r.first().first - l.first) <= maxOf(8, h / 2) }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.second }.joinToString(" ") { it.third } }
    }
}
