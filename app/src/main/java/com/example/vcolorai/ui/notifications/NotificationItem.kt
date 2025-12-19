package com.example.vcolorai.ui.notifications

// Элемент уведомления
data class NotificationItem(
    // ID уведомления
    val id: String = "",
    // Тип уведомления
    val type: String = "system",
    // ID пользователя-источника
    val fromUserId: String? = null,
    // Имя пользователя-источника
    val fromUsername: String? = null,
    // Заголовок
    val title: String = "",
    // Текст
    val message: String = "",
    // ID палитры
    val paletteId: String? = null,
    // Дата создания
    val createdAt: Long = 0L,
    // Прочитано или нет
    val isRead: Boolean = false
)
