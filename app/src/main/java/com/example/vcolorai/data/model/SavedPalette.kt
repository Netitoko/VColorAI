package com.example.vcolorai.data.model

// Сохранённая палитра
data class SavedPalette(
    // ID палитры
    val id: String = "",

    // Название палитры
    val paletteName: String = "",

    // Цвета палитры
    val colors: List<String> = emptyList(),

    // Тип источника
    val sourceType: String = "",

    // Данные источника
    val sourceData: String? = null,

    // Дата создания
    val creationDate: Long = 0L,

    // Теги
    val tags: List<String> = emptyList(),

    // Связанное изображение
    val imageUri: String? = null,

    // Prompt генерации
    val promptText: String? = null
)
