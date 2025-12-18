package com.example.vcolorai

import android.content.Context
import org.json.JSONArray
import java.nio.charset.Charset

object ColorKeywordLookup {

    private val colorMap = mutableMapOf<String, String>()

    fun load(context: Context) {
        if (colorMap.isNotEmpty()) return // уже загружено

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

    fun findColors(words: List<String>): List<Int> {
        return words.mapNotNull { colorMap[it.lowercase()]?.let { hex -> android.graphics.Color.parseColor(hex) } }
    }
}
