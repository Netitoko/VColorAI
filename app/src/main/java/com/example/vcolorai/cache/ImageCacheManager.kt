package com.example.vcolorai.cache

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ImageCacheManager {

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "feed_images").apply { mkdirs() }

    fun localPathForUrl(context: Context, url: String): String {
        val name = "img_${url.hashCode()}.jpg"
        return File(cacheDir(context), name).absolutePath
    }

    fun existsLocal(context: Context, url: String): Boolean {
        val f = File(localPathForUrl(context, url))
        return f.exists() && f.length() > 0
    }

    suspend fun cacheUrlIfNeeded(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(localPathForUrl(context, url))
            if (outFile.exists() && outFile.length() > 0) return@withContext outFile.absolutePath

            // Скачиваем через Glide в временный файл…
            val downloaded = Glide.with(context)
                .asFile()
                .load(url)
                .submit()
                .get()

            // …и копируем в наш внутренний кэш
            downloaded.copyTo(outFile, overwrite = true)
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
