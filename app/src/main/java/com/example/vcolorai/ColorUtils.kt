package com.example.vcolorai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

object ColorUtils {

    // 🎨 Извлекаем цвета из изображения с помощью Palette + собственная выборка пикселей
    fun extractColorsFromImage(context: Context, uri: Uri): List<Int> {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        if (bitmap == null) return emptyList()

        val palette = Palette.from(bitmap)
            .maximumColorCount(16)
            .generate()

        val paletteColors = palette.swatches.map { it.rgb }

        // Дополнительная обработка — получение средних тонов из пикселей
        val pixelColors = getPixelsFromUri(context, uri)
        val clustered = clusterColors(pixelColors, 5)

        return (paletteColors + clustered).distinct()
    }

    // 🧠 Генерация палитры на основе текста
    fun generateColorsFromText(text: String): List<Int> {
        val lower = text.lowercase()
        val base = when {
            listOf("ocean", "sea", "blue", "water").any { it in lower } ->
                listOf(0xFF006994, 0xFF0096C7, 0xFF90E0EF)
            listOf("sunset", "fire", "warm", "red", "orange").any { it in lower } ->
                listOf(0xFFFF6B6B, 0xFFFF9E80, 0xFFB23A48)
            listOf("forest", "green", "nature", "tree").any { it in lower } ->
                listOf(0xFF2E8B57, 0xFF3CB371, 0xFF98FB98)
            listOf("purple", "dream", "night", "magic").any { it in lower } ->
                listOf(0xFF7B2CBF, 0xFF9D4EDD, 0xFFC77DFF)
            listOf("earth", "brown", "sand").any { it in lower } ->
                listOf(0xFF7B4F2E, 0xFFA6785D, 0xFFD2B48C)
            else ->
                listOf(0xFF808080, 0xFFA0A0A0, 0xFFC0C0C0)
        }

        // Небольшой сдвиг оттенков для "живости"
        return base.map { color ->
            val hsv = FloatArray(3)
            Color.colorToHSV(color.toInt(), hsv)
            hsv[0] = (hsv[0] + Random.nextInt(-10, 10)) % 360
            Color.HSVToColor(hsv)
        }
    }

    // ⚗️ Комбинированная палитра: текст + изображение
    fun fusePalettes(imgColors: List<Int>, textColors: List<Int>): List<Int> {
        if (imgColors.isEmpty()) return textColors
        if (textColors.isEmpty()) return imgColors

        val result = mutableListOf<Int>()
        val count = min(imgColors.size, textColors.size)
        for (i in 0 until count) {
            result.add(mixColors(imgColors[i], textColors[i], 0.7f, 0.3f))
        }
        if (imgColors.size > count) result.addAll(imgColors.subList(count, imgColors.size))
        return result.distinct()
    }

    // 🎚️ Смешивание двух цветов
    fun mixColors(c1: Int, c2: Int, w1: Float, w2: Float): Int {
        val r = ((Color.red(c1) * w1) + (Color.red(c2) * w2)).toInt()
        val g = ((Color.green(c1) * w1) + (Color.green(c2) * w2)).toInt()
        val b = ((Color.blue(c1) * w1) + (Color.blue(c2) * w2)).toInt()
        return Color.rgb(r, g, b)
    }

    // 🧩 Получение пикселей из изображения
    fun getPixelsFromUri(context: Context, uri: Uri, maxSize: Int = 400): IntArray {
        val input = context.contentResolver.openInputStream(uri)
        val bmp = BitmapFactory.decodeStream(input) ?: return IntArray(0)
        input?.close()

        val w = bmp.width
        val h = bmp.height
        val scale = (maxSize.toDouble() / max(w, h)).coerceAtMost(1.0)
        val newW = max(1, (w * scale).roundToInt())
        val newH = max(1, (h * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, newW, newH, true)

        val pixels = IntArray(newW * newH)
        small.getPixels(pixels, 0, newW, 0, 0, newW, newH)
        small.recycle()

        return pixels
    }

    // 🌀 Простая кластеризация цветов (псевдо-k-means)
    private fun clusterColors(pixels: IntArray, clusterCount: Int): List<Int> {
        if (pixels.isEmpty()) return emptyList()

        val clusters = MutableList(clusterCount) { pixels.random() }

        repeat(4) {
            val groups = Array(clusterCount) { mutableListOf<Int>() }
            pixels.forEach { p ->
                val nearest = clusters.indices.minByOrNull { i -> colorDistance(p, clusters[i]) } ?: 0
                groups[nearest].add(p)
            }

            clusters.indices.forEach { i ->
                if (groups[i].isNotEmpty()) clusters[i] = averageColor(groups[i])
            }
        }

        return clusters
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun averageColor(colors: List<Int>): Int {
        val r = colors.map { Color.red(it) }.average().toInt()
        val g = colors.map { Color.green(it) }.average().toInt()
        val b = colors.map { Color.blue(it) }.average().toInt()
        return Color.rgb(r, g, b)
    }

    // Добавьте в ColorUtils.kt внутри object ColorUtils
    fun generateSimilarColors(baseColor: Int, count: Int = 6): List<Int> {
        val out = mutableListOf<Int>()
        val baseHsv = FloatArray(3)
        android.graphics.Color.colorToHSV(baseColor, baseHsv)

        // набор небольших сдвигов (в градусах)
        val hueShifts = listOf(-24f, -12f, -6f, 0f, 6f, 12f, 24f)
        val rand = kotlin.random.Random

        var i = 0
        while (out.size < count) {
            // берем шаблонный сдвиг и небольшую случайность
            val hShift = hueShifts[i % hueShifts.size] + rand.nextFloat() * 6f - 3f
            val sMult = (0.85f + rand.nextFloat() * 0.4f).coerceIn(0f, 1f) // 0.85..1.25
            val vMult = (0.85f + rand.nextFloat() * 0.3f).coerceIn(0f, 1f) // 0.85..1.15

            val newHsv = baseHsv.copyOf()
            newHsv[0] = (newHsv[0] + hShift + 360f) % 360f
            newHsv[1] = (newHsv[1] * sMult).coerceIn(0f, 1f)
            newHsv[2] = (newHsv[2] * vMult).coerceIn(0f, 1f)

            val newColor = android.graphics.Color.HSVToColor(newHsv)
            if (!out.contains(newColor)) out.add(newColor)
            i++
            // safety in case of weird collisions
            if (i > count * 10) break
        }

        return out.take(count)
    }

}
