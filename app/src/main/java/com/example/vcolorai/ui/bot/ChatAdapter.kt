package com.example.vcolorai.ui.bot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vcolorai.R

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

class ChatAdapter(
    private val items: MutableList<ChatMessage>,
    private val onMessageLongClick: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_BOT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isUser) TYPE_USER else TYPE_BOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layoutId = if (viewType == TYPE_USER) {
            R.layout.item_chat_user
        } else {
            R.layout.item_chat_bot
        }

        val view = LayoutInflater.from(parent.context)
            .inflate(layoutId, parent, false)

        return ChatViewHolder(view, onMessageLongClick)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ChatViewHolder(
        itemView: View,
        private val onMessageLongClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(msg: ChatMessage) {
            tvMessage.text = msg.text

            itemView.setOnLongClickListener {
                onMessageLongClick(tvMessage.text.toString())
                true
            }
        }
    }
}
