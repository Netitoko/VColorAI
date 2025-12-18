package com.example.vcolorai.ui.notifications

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vcolorai.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsAdapter(
    private val onClick: (NotificationItem) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.VH>() {

    private val items = mutableListOf<NotificationItem>()

    fun submitList(list: List<NotificationItem>) { // ✅ вместо submit()
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvNotifTitle)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvNotifMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvNotifTime)

        fun bind(item: NotificationItem) {
            tvTitle.text = item.title
            tvMessage.text = item.message
            tvTime.text = formatTime(item.createdAt)

            itemView.alpha = if (item.isRead) 0.6f else 1f
            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return sdf.format(Date(ms))
    }
}
