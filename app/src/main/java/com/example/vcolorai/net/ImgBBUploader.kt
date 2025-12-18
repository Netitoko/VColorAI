package com.example.vcolorai.net

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object ImgBBUploader {

    // ⚠️ ВСТАВЬ СЮДА СВОЙ РЕАЛЬНЫЙ КЛЮЧ
    private const val API_KEY = "58bcae4caf2c5d2594fc58e5169343fa"
    private const val UPLOAD_URL = "https://api.imgbb.com/1/upload"

    private val client by lazy { OkHttpClient() }

    /**
     * Загружает картинку по uri на ImgBB и возвращает ПРЯМОЙ URL на изображение (jpg/png).
     * Возвращает null, если не удалось.
     */
    fun uploadImage(context: Context, imageUri: Uri): String? {
        return try {
            val cr = context.contentResolver
            val inputStream = cr.openInputStream(imageUri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("key", API_KEY)
                .addFormDataPart(
                    "image",
                    "upload.jpg",
                    bytes.toRequestBody("image/*".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val bodyStr = response.body?.string()
            response.close()

            if (bodyStr.isNullOrBlank()) return null

            val json = JSONObject(bodyStr)
            val success = json.optBoolean("success", false)
            if (!success) return null

            val data = json.optJSONObject("data") ?: return null

            // 🔍 Пробуем сначала ВЛОЖЕННЫЙ image.url — это обычно прямой линк
            val imageObj = data.optJSONObject("image")
            var directUrl: String? = null

            if (imageObj != null) {
                directUrl = imageObj.optString("url", null)
                if (directUrl.isNullOrBlank()) {
                    directUrl = imageObj.optString("display_url", null)
                }
            }

            // Если по какой-то причине во вложенном объекте ничего нет —
            // пробуем верхнеуровневые поля как запасной вариант
            if (directUrl.isNullOrBlank()) {
                directUrl = data.optString("url", null)
                if (directUrl.isNullOrBlank()) {
                    directUrl = data.optString("display_url", null)
                }
            }

            // На всякий случай — подрежем пробелы
            directUrl = directUrl?.trim()

            // Если ссылка есть, отдадим её — Glide дальше сам всё сделает
            directUrl
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
