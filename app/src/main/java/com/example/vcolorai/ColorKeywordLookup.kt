package com.example.vcolorai

import android.content.Context
import org.json.JSONArray
import java.nio.charset.Charset

object ColorKeywordLookup {

    private val colorMap = mutableMapOf<String, String>()

    // Загрузка словаря соответствий ключевых слов и цветов из JSON-файла
    fun load(context: Context) {
        if (colorMap.isNotEmpty()) return // Словарь уже загружен

        try {
            val inputStream = context.assets.open("color_keywords.json")
            val json = inputStream.readBytes().toString(Charset.defaultCharset())
            val jsonArray = JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                colorMap[obj.getString("keyword").lowercase()] = obj.getString("hex")
            }

            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Поиск цветов по ключевым словам из текстового запроса
    fun findColors(words: List<String>): List<Int> {
        return words.mapNotNull { colorMap[it.lowercase()]?.let { hex -> android.graphics.Color.parseColor(hex) } }
    }
}