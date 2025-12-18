package com.example.vcolorai.net

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Клиент для генерации цветовых палитр через YandexGPT.
 *
 * ONLINE-режим:
 *  - Принимает текстовый запрос пользователя (RU/EN).
 *  - Возвращает список HEX-цветов (#RRGGBB).
 *  - Внутри YandexGPT работает с группами:
 *      base, warm_accents, cool_accents, neutrals, shadows, highlights
 *    по 3 цвета в каждой (итого 18).
 *
 * OFFLINE-режим реализован в GenerationFragment, а тут только онлайн.
 */
object YandexPaletteClient {
    private const val YANDEX_API_KEY = ""
    private const val YANDEX_FOLDER_ID = ""

    private val api: YandexPaletteApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://llm.api.cloud.yandex.net/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(YandexPaletteApi::class.java)
    }

    /**
     * Генерация палитры через YandexGPT.
     *
     * @param prompt текст запроса пользователя (RU/EN)
     * @param expectedSize ожидаемое количество цветов (для нас 18)
     * @return список HEX-цветов (#RRGGBB), максимум expectedSize.
     *         Если что-то пошло не так — вернётся пустой список.
     */
    suspend fun generatePalette(prompt: String, expectedSize: Int = 18): List<String> =
        withContext(Dispatchers.IO) {
            if (
                YANDEX_API_KEY.startsWith("PUT_") ||
                YANDEX_FOLDER_ID.startsWith("PUT_")
            ) {
                throw IllegalStateException(
                    "Не задан API-ключ или FOLDER_ID для YandexPaletteClient"
                )
            }

            val systemPrompt = """
                You are a professional color palette generator.

                You must output ONLY valid JSON with this exact structure:

                {
                  "base": ["#RRGGBB", "#RRGGBB", "#RRGGBB"],
                  "warm_accents": ["#RRGGBB", "#RRGGBB", "#RRGGBB"],
                  "cool_accents": ["#RRGGBB", "#RRGGBB", "#RRGGBB"],
                  "neutrals": ["#RRGGBB", "#RRGGBB", "#RRGGBB"],
                  "shadows": ["#RRGGBB", "#RRGGBB", "#RRGGBB"],
                  "highlights": ["#RRGGBB", "#RRGGBB", "#RRGGBB"]
                }

                Rules:
                - Exactly 3 colors in each group.
                - Colors must be in #RRGGBB hexadecimal format.
                - Total of 18 colors across all groups.
                - No commentary, no markdown, no text outside the JSON object.
                - All colors must reflect the user's description of style, mood and theme.
                - If the user writes in Russian, silently translate their text to English in your mind before choosing colors.
            """.trimIndent()

            val userPrompt = """
                Generate a color palette for this request:

                "$prompt"
            """.trimIndent()

            val request = PaletteChatRequest(
                model = "gpt://$YANDEX_FOLDER_ID/yandexgpt-lite/latest",
                messages = listOf(
                    PaletteChatMessage(role = "system", content = systemPrompt),
                    PaletteChatMessage(role = "user", content = userPrompt)
                ),
                temperature = 0.4,
                maxTokens = 800
            )

            try {
                val response = api.getPalette(
                    authHeader = "Api-Key $YANDEX_API_KEY",
                    project = YANDEX_FOLDER_ID,
                    body = request
                )

                val content = response.choices.firstOrNull()?.message?.content?.trim()
                    ?: return@withContext emptyList()

                parsePaletteJson(content, expectedSize)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    private fun parsePaletteJson(raw: String, expectedSize: Int): List<String> {
        return try {
            val json = JSONObject(raw)

            val groups = listOf(
                "base",
                "warm_accents",
                "cool_accents",
                "neutrals",
                "shadows",
                "highlights"
            )

            val result = mutableListOf<String>()
            val hexRegex = Regex("^#([0-9A-Fa-f]{6})$")

            for (group in groups) {
                val arr = json.optJSONArray(group) ?: continue
                for (i in 0 until arr.length()) {
                    val hex = arr.optString(i)?.trim() ?: continue
                    if (hexRegex.matches(hex)) {
                        val normalized = hex.uppercase()
                        if (!result.contains(normalized)) {
                            result.add(normalized)
                        }
                    }
                }
            }

            if (result.isEmpty()) {
                emptyList()
            } else {
                result.take(expectedSize)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

/* --------- DTO + Retrofit API --------- */

data class PaletteChatRequest(
    val model: String,
    val messages: List<PaletteChatMessage>,
    val temperature: Double? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null
)

data class PaletteChatMessage(
    val role: String,
    val content: String
)

data class PaletteChatResponse(
    val choices: List<PaletteChoice> = emptyList()
)

data class PaletteChoice(
    val index: Int? = null,
    val message: PaletteChatMessage
)

interface YandexPaletteApi {

    @POST("chat/completions")
    suspend fun getPalette(
        @Header("Authorization") authHeader: String,   // "Api-Key <API_KEY>"
        @Header("OpenAI-Project") project: String,     // folder_id
        @Body body: PaletteChatRequest
    ): PaletteChatResponse
}
