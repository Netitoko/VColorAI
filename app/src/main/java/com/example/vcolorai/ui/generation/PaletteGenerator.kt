package com.example.vcolorai.generation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// Генерация палитр (online)
object PaletteGenerator {

    // API ключи
    private const val API_KEY = ""
    private const val FOLDER_ID = ""

    private val gson = Gson()

    // Retrofit API
    private val api: YandexPaletteApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://llm.api.cloud.yandex.net/v1/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(YandexPaletteApi::class.java)
    }

    // Генерация палитры через Yandex GPT
    suspend fun generateOnlinePalette(prompt: String): List<String> =
        withContext(Dispatchers.IO) {

            val systemPrompt = """
                You are a professional color palette generator.
                You must output ONLY valid JSON.

                Produce exactly these groups:
                - base: 3 colors
                - warm_accents: 3 colors
                - cool_accents: 3 colors
                - neutrals: 3 colors
                - shadows: 3 colors
                - highlights: 3 colors

                Rules:
                - All colors must be in #RRGGBB format.
                - No text outside JSON.
                - If the user writes in Russian, translate internally.
            """.trimIndent()

            val userPrompt = """
                Create a color palette for: "$prompt"
                Return exactly 18 colors total.
            """.trimIndent()

            val body = JsonObject().apply {
                addProperty("model", "gpt://$FOLDER_ID/yandexgpt-lite/latest")
                add(
                    "messages",
                    gson.toJsonTree(
                        listOf(
                            mapOf("role" to "system", "content" to systemPrompt),
                            mapOf("role" to "user", "content" to userPrompt)
                        )
                    )
                )
                addProperty("temperature", 0.4)
                addProperty("max_tokens", 400)
            }

            val response = api.generatePalette(
                "Api-Key $API_KEY",
                FOLDER_ID,
                body
            )

            parsePalette(response)
        }

    // Парсинг JSON с цветами
    private fun parsePalette(json: JsonObject): List<String> {
        return try {
            val content =
                json["choices"].asJsonArray[0]
                    .asJsonObject["message"]
                    .asJsonObject["content"]
                    .asString

            val obj = gson.fromJson(content, JsonObject::class.java)

            val groups = listOf(
                "base",
                "warm_accents",
                "cool_accents",
                "neutrals",
                "shadows",
                "highlights"
            )

            val result = mutableListOf<String>()

            for (g in groups) {
                if (obj.has(g)) {
                    obj[g].asJsonArray.forEach {
                        result.add(it.asString)
                    }
                }
            }

            result.take(18)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Проверка наличия интернета
    fun hasInternet(context: Context): Boolean {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val nc = cm.getNetworkCapabilities(nw) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}

// Retrofit API
interface YandexPaletteApi {

    @POST("chat/completions")
    suspend fun generatePalette(
        @Header("Authorization") auth: String,
        @Header("OpenAI-Project") folderId: String,
        @Body body: JsonObject
    ): JsonObject
}
