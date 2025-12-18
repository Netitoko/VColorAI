package com.example.vcolorai.model

data class SavedPalette(
    val id: String = "",
    val paletteName: String = "",
    val colors: List<String> = emptyList(),
    val sourceType: String = "",
    val sourceData: String? = null,
    val creationDate: Long = 0L,
    val tags: List<String> = emptyList(),
    val imageUri: String? = null,
    val promptText: String? = null
)
