package com.example.vcolorai.data.net

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Загрузка изображений в ImgBB
object ImgBBUploader {

    // Лог тег
    private const val TAG = "ImgBB"

    // API ключ ImgBB
    private const val API_KEY = "58bcae4caf2c5d2594fc58e5169343fa"

    // Endpoint загрузки
    private const val UPLOAD_URL = "https://api.imgbb.com/1/upload"

    // HTTP клиент
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    // Загрузка изображения по Uri
    fun uploadImage(context: Context, imageUri: Uri): String? {
        return try {
            val bytes = context.contentResolver
                .openInputStream(imageUri)
                ?.use { it.readBytes() }
                ?: run {
                    Log.e(TAG, "openInputStream returned null: $imageUri")
                    return null
                }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val formBody = FormBody.Builder()
                .add("key", API_KEY)
                .add("image", base64)
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}. Body: $bodyStr")
                response.close()
                return null
            }
            response.close()

            if (bodyStr.isNullOrBlank()) {
                Log.e(TAG, "Empty response body")
                return null
            }

            val json = JSONObject(bodyStr)
            val success = json.optBoolean("success", false)
            if (!success) {
                Log.e(TAG, "success=false. Body: $bodyStr")
                return null
            }

            val data = json.optJSONObject("data") ?: run {
                Log.e(TAG, "No data object. Body: $bodyStr")
                return null
            }

            val url = data.optString("url", "").trim()
            val displayUrl = data.optString("display_url", "").trim()

            val imageObj = data.optJSONObject("image")
            val nestedUrl =
                imageObj?.optString("url", "")?.trim().orEmpty()

            val result = when {
                url.isNotBlank() -> url
                displayUrl.isNotBlank() -> displayUrl
                nestedUrl.isNotBlank() -> nestedUrl
                else -> ""
            }

            if (result.isBlank()) {
                Log.e(TAG, "No url fields. Body: $bodyStr")
                null
            } else {
                Log.d(TAG, "Uploaded OK: $result")
                result
            }

        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}", e)
            null
        }
    }
}
