package com.example.vcolorai.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vcolorai.databinding.FragmentNotificationsBinding
import com.example.vcolorai.ui.common.BaseFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class NotificationsFragment : BaseFragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var adapter: NotificationsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)

        setupRecycler()
        loadNotifications()

        return binding.root
    }

    private fun setupRecycler() {
        adapter = NotificationsAdapter { notif ->
            // пометить прочитанным
            markAsRead(notif)
            Toast.makeText(requireContext(), notif.title, Toast.LENGTH_SHORT).show()
        }

        binding.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotifications.adapter = adapter
    }

    private fun loadNotifications() {
        val user = auth.currentUser ?: run {
            showEmpty(true)
            return
        }

        db.collection("users")
            .document(user.uid)
            .collection("notifications")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.map { doc ->
                    NotificationItem(
                        id = doc.id,
                        type = doc.getString("type") ?: "system",
                        fromUserId = doc.getString("fromUserId"),
                        title = doc.getString("title") ?: "",
                        message = doc.getString("message") ?: "",
                        paletteId = doc.getString("paletteId"),
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        isRead = doc.getBoolean("isRead") ?: false
                    )
                }

                adapter.submitList(list) // ✅ больше нет submit()
                showEmpty(list.isEmpty())
            }
            .addOnFailureListener { e ->
                showEmpty(true)
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun markAsRead(item: NotificationItem) {
        val user = auth.currentUser ?: return
        if (item.isRead) return

        db.collection("users")
            .document(user.uid)
            .collection("notifications")
            .document(item.id)
            .update("isRead", true)
    }

    private fun showEmpty(isEmpty: Boolean) {
        binding.tvNotificationsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvNotifications.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
