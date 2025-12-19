package com.example.vcolorai.data.model

// Публичная палитра
data class PublicPalette(
    // ID палитры
    val id: String = "",

    // ID автора
    val userId: String = "",

    // Имя автора
    val authorName: String = "",

    // Название палитры
    val paletteName: String = "",

    // Цвета палитры
    val colors: List<String> = emptyList(),

    // Тип источника
    val sourceType: String = "",

    // Теги
    val tags: List<String> = emptyList(),

    // Связанное изображение
    val imageUri: String? = null,

    // Prompt генерации
    val promptText: String? = null,

    // Дата создания
    val creationDate: Long = 0L
)
