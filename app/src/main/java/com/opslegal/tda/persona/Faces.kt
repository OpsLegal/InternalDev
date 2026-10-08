package com.opslegal.tda.persona

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.opslegal.tda.core.model.Persona
import java.io.File

/**
 * The assistant's face: one of six drawn faces, or the user's own photo (a small square kept on the phone only).
 * The same bitmap goes on notifications (Android's conversation style), the bell, replies and the assistant tab.
 */
object Faces {
    private data class Look(val bg: Int, val skin: Int, val hair: Int, val style: String, val shirt: Int, val glasses: Boolean = false, val beard: Boolean = false)

    private val LOOKS = listOf(
        Look(0xFF2C3A4F.toInt(), 0xFFC98E64.toInt(), 0xFF2A1D15.toInt(), "short", 0xFFE9EEF5.toInt()),
        Look(0xFF2F7D78.toInt(), 0xFFF1C9A5.toInt(), 0xFF7A4A2A.toInt(), "short", 0xFF24323F.toInt(), glasses = true),
        Look(0xFFC9822B.toInt(), 0xFF7A4E33.toInt(), 0xFF141010.toInt(), "curly", 0xFFF6EBDD.toInt()),
        Look(0xFFB4566E.toInt(), 0xFFF3CFB3.toInt(), 0xFF3B2418.toInt(), "long", 0xFFFFFFFF.toInt()),
        Look(0xFF6A55A6.toInt(), 0xFFA86E4B.toInt(), 0xFF1E1410.toInt(), "bun", 0xFFF1E9FF.toInt()),
        Look(0xFF4E7A3A.toInt(), 0xFFE2B48C.toInt(), 0xFF4A3424.toInt(), "short", 0xFFEDF3E6.toInt(), beard = true),
    )
    val COUNT = LOOKS.size

    fun photoFile(context: Context) = File(context.filesDir, "persona.jpg")

    /** The face for [persona] at [size] px: the photo, the drawn face, or a plain spark before a name is chosen. */
    fun bitmap(context: Context, persona: Persona, size: Int): Bitmap {
        if (persona.photo) photoFile(context).takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.let { return circle(Bitmap.createScaledBitmap(it, size, size, true)) }
        }
        return if (persona.name.isBlank()) spark(size) else face(persona.face, size)
    }

    fun face(index: Int, size: Int): Bitmap {
        val l = LOOKS[index.mod(LOOKS.size)]
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 64f, size / 64f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fill(color: Int) = p.apply { style = Paint.Style.FILL; this.color = color }
        val clip = Path().apply { addCircle(32f, 32f, 32f, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawCircle(32f, 32f, 32f, fill(l.bg))
        if (l.style == "long") c.drawPath(Path().apply { moveTo(15f, 30f); cubicTo(13f, 46f, 17f, 54f, 20f, 56f); lineTo(44f, 56f); cubicTo(47f, 54f, 51f, 46f, 49f, 30f); close() }, fill(l.hair))
        c.drawPath(Path().apply { moveTo(10f, 66f); cubicTo(12f, 50f, 52f, 50f, 54f, 66f); close() }, fill(l.shirt))
        c.drawRoundRect(RectF(28f, 40f, 36f, 48f), 3f, 3f, fill(l.skin))
        c.drawCircle(32f, 30f, 14f, fill(l.skin))
        fun cap(top: Float) = Path().apply { moveTo(18f, 29f); cubicTo(17f, top, 47f, top, 46f, 29f); cubicTo(43f, 21f, 22f, 21f, 18f, 29f); close() }
        when (l.style) {
            "short" -> c.drawPath(cap(14f), fill(l.hair))
            "curly" -> listOf(22f to 20f, 28f to 16f, 35f to 16f, 41f to 20f, 19f to 26f, 45f to 26f, 32f to 15f).forEach { (x, y) -> c.drawCircle(x, y, 6f, fill(l.hair)) }
            "long" -> c.drawPath(Path().apply { moveTo(17f, 31f); cubicTo(14f, 12f, 50f, 12f, 47f, 31f); cubicTo(42f, 22f, 24f, 20f, 17f, 31f); close() }, fill(l.hair))
            "bun" -> { c.drawCircle(32f, 12f, 6.5f, fill(l.hair)); c.drawPath(cap(15f), fill(l.hair)) }
        }
        if (l.beard) c.drawPath(Path().apply { moveTo(18.5f, 31f); cubicTo(19f, 47f, 45f, 47f, 45.5f, 31f); cubicTo(42f, 40f, 22f, 40f, 18.5f, 31f); close() }, fill(l.hair))
        val ink = 0xFF22201E.toInt()
        c.drawCircle(26.5f, 31f, 1.7f, fill(ink)); c.drawCircle(37.5f, 31f, 1.7f, fill(ink))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 1.5f; color = ink }
        if (l.glasses) { c.drawCircle(26.5f, 31f, 4.2f, line); c.drawCircle(37.5f, 31f, 4.2f, line); c.drawLine(30.7f, 31f, 33.3f, 31f, line) }
        c.drawPath(Path().apply { moveTo(27f, 37f); quadTo(32f, if (l.beard) 40f else 41f, 37f, 37f) }, line.apply { color = if (l.beard) 0xFFF6EBDD.toInt() else ink })
        return bmp
    }

    private fun spark(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C3A4F.toInt() }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFFF5C842.toInt(); p.textSize = size * 0.5f; p.textAlign = Paint.Align.CENTER
        c.drawText("✦", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** A person who wrote: their first letter on a soft colour. */
    fun initial(name: String, size: Int): Bitmap {
        val n = name.trim().ifBlank { "?" }
        val hue = n.sumOf { it.code } % 360
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.75f)) }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = android.graphics.Color.WHITE; p.textSize = size * 0.45f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText(n.first().uppercase(), size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    private fun circle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(src.width / 2f, src.height / 2f, src.width / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /** Keeps the chosen photo as a small centred square (192 px), on this phone only. */
    fun savePhoto(context: Context, uri: android.net.Uri): Boolean = runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val s = minOf(src.width, src.height)
        val square = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s), 192, 192, true)
        photoFile(context).outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        true
    }.getOrDefault(false)
}
