package com.example.vcolorai.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

object MarkdownTextFormatter {

    fun format(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val lines = text.lines()

        lines.forEach { line ->
            val start = sb.length

            when {
                line.startsWith("# ") -> {
                    sb.append(line.removePrefix("# ").uppercase()).append("\n\n")
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                line.startsWith("## ") -> {
                    sb.append(line.removePrefix("## ")).append("\n\n")
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                line.startsWith("- ") -> {
                    sb.append("• ").append(line.removePrefix("- ")).append("\n")
                }

                line.startsWith("> ") -> {
                    sb.append(line.removePrefix("> ")).append("\n")
                    sb.setSpan(
                        ForegroundColorSpan(0xFF777777.toInt()),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                else -> {
                    sb.append(line).append("\n")
                }
            }

            // 🔴 подсветка важных пунктов
            if (line.contains("❗")) {
                sb.setSpan(
                    ForegroundColorSpan(0xFFD32F2F.toInt()),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return sb
    }
}
