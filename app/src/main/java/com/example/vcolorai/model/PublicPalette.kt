package com.example.vcolorai.model

data class PublicPalette(
    val id: String = "",
    val userId: String = "",
    val authorName: String = "",
    val paletteName: String = "",
    val colors: List<String> = emptyList(),
    val sourceType: String = "",
    val tags: List<String> = emptyList(),
    val imageUri: String? = null,
    val promptText: String? = null,
    val creationDate: Long = 0L
)
