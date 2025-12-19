package com.example.vcolorai.cache

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Кэш изображений
object ImageCacheManager {

    // Директория кэша
    private fun cacheDir(context: Context): File =
        File(context.filesDir, "feed_images").apply { mkdirs() }

    // Локальный путь для URL
    fun localPathForUrl(context: Context, url: String): String {
        val name = "img_${url.hashCode()}.jpg"
        return File(cacheDir(context), name).absolutePath
    }

    // Проверка наличия локального файла
    fun existsLocal(context: Context, url: String): Boolean {
        val f = File(localPathForUrl(context, url))
        return f.exists() && f.length() > 0
    }

    // Загрузка и кэширование изображения
    suspend fun cacheUrlIfNeeded(
        context: Context,
        url: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(localPathForUrl(context, url))
            if (outFile.exists() && outFile.length() > 0)
                return@withContext outFile.absolutePath

            val downloaded = Glide.with(context)
                .asFile()
                .load(url)
                .submit()
                .get()

            downloaded.copyTo(outFile, overwrite = true)
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
