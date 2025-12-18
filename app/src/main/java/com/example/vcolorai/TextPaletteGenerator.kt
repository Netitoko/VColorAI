package com.example.vcolorai

import android.content.Context
import android.graphics.Color
import kotlin.math.abs
import kotlin.random.Random

object TextPaletteGenerator {

    // ассоциативные модификаторы тона
    private val toneModifiers = mapOf(
        "dark" to Triple(0f, 0.1f, -0.3f),
        "deep" to Triple(0f, 0.15f, -0.2f),
        "light" to Triple(0f, -0.1f, 0.3f),
        "bright" to Triple(0f, 0.2f, 0.2f),
        "soft" to Triple(0f, -0.2f, 0.1f),
        "warm" to Triple(10f, 0.1f, 0.05f),
        "cold" to Triple(-10f, -0.1f, 0f),
        "cool" to Triple(-15f, -0.1f, 0.05f),
        "pale" to Triple(0f, -0.3f, 0.3f),
        "morning" to Triple(10f, -0.1f, 0.25f),
        "evening" to Triple(-10f, 0.05f, -0.1f),
        "night" to Triple(-5f, 0.1f, -0.25f)
    )

    fun generatePaletteFromText(context: Context, text: String): List<String> {
        ColorKeywordLookup.load(context)

        val words = text.lowercase().split(" ").filter { it.isNotBlank() }

        // определяем базовые цвета
        val foundColors = ColorKeywordLookup.findColors(words)
        if (foundColors.isEmpty()) return listOf("#808080", "#999999", "#AAAAAA", "#BBBBBB", "#CCCCCC", "#DDDDDD")

        // смешиваем два цвета, если найдено несколько
        val baseColor = if (foundColors.size >= 2) mixColors(foundColors[0], foundColors[1]) else foundColors[0]

        // применяем модификаторы, если есть
        var hueShift = 0f
        var satShift = 0f
        var valShift = 0f
        for (word in words) {
            toneModifiers[word]?.let {
                hueShift += it.first
                satShift += it.second
                valShift += it.third
            }
        }

        // создаём 6 оттенков
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)

        val palette = mutableListOf<String>()
        for (i in 0 until 6) {
            val newH = (hsv[0] + hueShift + Random.nextInt(-10, 10)) % 360
            val newS = (hsv[1] + satShift + Random.nextDouble(-0.15, 0.15)).toFloat().coerceIn(0f, 1f)
            val newV = (hsv[2] + valShift + Random.nextDouble(-0.15, 0.15)).toFloat().coerceIn(0f, 1f)
            val c = Color.HSVToColor(floatArrayOf(newH, newS, newV))
            palette.add(String.format("#%06X", 0xFFFFFF and c))
        }

        return palette.distinct()
    }

    private fun mixColors(c1: Int, c2: Int): Int {
        val r = (Color.red(c1) + Color.red(c2)) / 2
        val g = (Color.green(c1) + Color.green(c2)) / 2
        val b = (Color.blue(c1) + Color.blue(c2)) / 2
        return Color.rgb(r, g, b)
    }
}
