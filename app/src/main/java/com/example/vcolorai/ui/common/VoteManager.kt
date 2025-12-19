package com.example.vcolorai.ui.common

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// Управление лайками и дизлайками
object VoteManager {

    // Результат голосования
    data class VoteResult(
        val likes: Long,
        val dislikes: Long,
        val userVote: Int,
        val ownerId: String
    )

    // Лайк / дизлайк палитры
    fun vote(
        db: FirebaseFirestore,
        auth: FirebaseAuth,
        paletteId: String,
        paletteName: String,
        isLike: Boolean,
        onSuccess: (VoteResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val user = auth.currentUser ?: return
        val uid = user.uid
        val now = System.currentTimeMillis()

        val paletteRef = db.collection("color_palettes").document(paletteId)
        val voteRef = paletteRef.collection("votes").document(uid)

        // Индекс лайков пользователя
        val likedRef = db.collection("users")
            .document(uid)
            .collection("liked_palettes")
            .document(paletteId)

        db.runTransaction { tx ->
            val paletteSnap = tx.get(paletteRef)
            val ownerId = paletteSnap.getString("userId").orEmpty()

            val likes = paletteSnap.getLong("likesCount") ?: 0L
            val dislikes = paletteSnap.getLong("dislikesCount") ?: 0L

            val prevVote = tx.get(voteRef).getLong("value")?.toInt() ?: 0
            val target = if (isLike) 1 else -1

            val (newLikes, newDislikes, newVote) = when (prevVote) {
                target -> { // повторный тап -> отмена
                    if (target == 1) Triple(likes - 1, dislikes, 0)
                    else Triple(likes, dislikes - 1, 0)
                }
                1, -1 -> { // переключение лайк <-> дизлайк
                    if (target == 1) Triple(likes + 1, dislikes - 1, 1)
                    else Triple(likes - 1, dislikes + 1, -1)
                }
                else -> { // первый голос
                    if (target == 1) Triple(likes + 1, dislikes, 1)
                    else Triple(likes, dislikes + 1, -1)
                }
            }

            tx.update(
                paletteRef,
                mapOf(
                    "likesCount" to newLikes,
                    "dislikesCount" to newDislikes
                )
            )

            if (newVote == 0) {
                tx.delete(voteRef)
            } else {
                tx.set(voteRef, mapOf("value" to newVote))
            }

            // Синхронизация liked_palettes
            if (newVote == 1) {
                tx.set(likedRef, mapOf("createdAt" to now))
            } else {
                tx.delete(likedRef)
            }

            VoteResult(newLikes, newDislikes, newVote, ownerId)
        }
            .addOnSuccessListener { result ->
                // Уведомление владельцу (не себе и только если есть голос)
                if (
                    result.userVote != 0 &&
                    result.ownerId.isNotBlank() &&
                    result.ownerId != uid
                ) {
                    createOrUpdateVoteNotification(
                        db = db,
                        voterUid = uid,
                        ownerUid = result.ownerId,
                        paletteId = paletteId,
                        paletteName = paletteName,
                        userVote = result.userVote
                    )
                }
                onSuccess(result)
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }

    // Создание уведомления о голосе
    private fun createOrUpdateVoteNotification(
        db: FirebaseFirestore,
        voterUid: String,
        ownerUid: String,
        paletteId: String,
        paletteName: String,
        userVote: Int
    ) {
        db.collection("public_users")
            .document(voterUid)
            .get()
            .addOnSuccessListener { doc ->
                val username =
                    doc.getString("username")
                        ?.trim()
                        .takeUnless { it.isNullOrBlank() }
                        ?: "Пользователь"

                val type = if (userVote == 1) "LIKE" else "DISLIKE"
                val title = if (type == "LIKE") "Новый лайк" else "Новый дизлайк"
                val message =
                    "$username ${if (type == "LIKE") "поставил(а) лайк" else "поставил(а) дизлайк"} вашей палитре: $paletteName"

                val notifId = "vote_${paletteId}_${voterUid}"

                val data = hashMapOf(
                    "type" to type,
                    "fromUserId" to voterUid,
                    "fromUsername" to username,
                    "paletteId" to paletteId,
                    "title" to title,
                    "message" to message,
                    "createdAt" to System.currentTimeMillis(),
                    "isRead" to false
                )

                db.collection("users")
                    .document(ownerUid)
                    .collection("notifications")
                    .document(notifId)
                    .set(data)
                    .addOnFailureListener { e ->
                        Log.e("NOTIFS", "Notification write error", e)
                    }
            }
    }
}
