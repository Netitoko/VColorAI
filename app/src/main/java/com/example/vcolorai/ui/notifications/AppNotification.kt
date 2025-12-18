package com.example.vcolorai.ui.notifications

data class AppNotification(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val createdAt: Long = 0L,
    val isRead: Boolean = false,

    // опционально: чтобы по клику открыть палитру
    val paletteId: String? = null
)
