package com.example.vcolorai.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateTimeUtils {

    private val formatter =
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("ru"))

    fun format(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return ""
        return formatter.format(Date(timestamp))
    }
}