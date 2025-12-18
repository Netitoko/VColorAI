package com.example.vcolorai.ui.notifications

data class NotificationItem(
    val id: String = "",
    val type: String = "system",
    val fromUserId: String? = null,
    val fromUsername: String? = null, // ✅ НОВОЕ
    val title: String = "",
    val message: String = "",
    val paletteId: String? = null,
    val createdAt: Long = 0L,
    val isRead: Boolean = false
)
