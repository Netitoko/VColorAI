package com.example.vcolorai.data.model

// Уведомление приложения
data class AppNotification(
    // ID уведомления
    val id: String = "",
    // Заголовок
    val title: String = "",
    // Текст уведомления
    val message: String = "",
    // Дата создания
    val createdAt: Long = 0L,
    // Прочитано или нет
    val isRead: Boolean = false,
    // ID палитры (если нужно открыть по клику)
    val paletteId: String? = null
)
