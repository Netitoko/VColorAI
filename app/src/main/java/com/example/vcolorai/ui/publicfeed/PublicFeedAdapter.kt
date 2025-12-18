package com.example.vcolorai.ui.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.example.vcolorai.ui.common.FullscreenImageDialog
import com.example.vcolorai.utils.DateTimeUtils
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

/**
 * UI-модель элемента ленты
 */
data class PublicFeedItem(
    val id: String,
    val paletteName: String,
    val colors: List<String>,

    // Автор
    val authorId: String,
    val authorName: String?,

    // Описание / теги
    val description: String?,
    val tags: List<String>,

    // Реакции
    val likesCount: Long,
    val dislikesCount: Long,

    // Онлайн URL (должен быть https://..., иначе на других устройствах не откроется)
    val imageUri: String?,

    // ✅ Локальная копия (для оффлайна на этом же устройстве)
    val localImagePath: String? = null,

    // текущий голос пользователя: 1 / -1 / 0
    val currentUserVote: Int,

    // дата публикации/создания
    val createdAt: Long
)

class PublicFeedAdapter(
    private var items: List<PublicFeedItem>,
    private val onSaveToMyPalettes: (PublicFeedItem) -> Unit,
    private val onSharePalette: (PublicFeedItem) -> Unit,
    private val onLike: (PublicFeedItem) -> Unit,
    private val onDislike: (PublicFeedItem) -> Unit,
    private val onAuthorClick: (authorId: String) -> Unit
) : RecyclerView.Adapter<PublicFeedAdapter.FeedViewHolder>() {

    private val db = FirebaseFirestore.getInstance()
    private val usernameCache = mutableMapOf<String, String>()

    inner class FeedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivPaletteImage: ImageView = itemView.findViewById(R.id.ivPaletteImage)
        val tvAuthorName: TextView = itemView.findViewById(R.id.tvAuthorName)
        val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
        val tagsContainer: LinearLayout = itemView.findViewById(R.id.tagsContainer)
        val paletteContainer: LinearLayout = itemView.findViewById(R.id.paletteContainer)
        val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
        val btnLike: ImageButton = itemView.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = itemView.findViewById(R.id.btnDislike)
        val tvLikesCount: TextView = itemView.findViewById(R.id.tvLikesCount)
        val tvDislikesCount: TextView = itemView.findViewById(R.id.tvDislikesCount)
        val tvPublishDate: TextView = itemView.findViewById(R.id.tvPublishDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_public_feed_palette, parent, false)
        return FeedViewHolder(view)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        // ---------- AUTHOR ----------
        bindAuthorName(holder, item)
        holder.tvAuthorName.setOnClickListener {
            val uid = item.authorId.trim()
            if (uid.isNotBlank()) onAuthorClick(uid)
        }

        // ---------- DESCRIPTION ----------
        if (item.description.isNullOrBlank()) {
            holder.tvDescription.visibility = View.GONE
        } else {
            holder.tvDescription.visibility = View.VISIBLE
            holder.tvDescription.text = item.description
        }

        // ---------- TAGS ----------
        renderTags(holder.tagsContainer, item.tags, density)

        // ---------- PALETTE ----------
        renderPalette(context, holder.paletteContainer, item.colors, density)

        // ---------- VOTES ----------
        holder.tvLikesCount.text = item.likesCount.toString()
        holder.tvDislikesCount.text = item.dislikesCount.toString()
        applyVoteState(holder, item.currentUserVote)

        holder.btnLike.setOnClickListener { onLike(item) }
        holder.btnDislike.setOnClickListener { onDislike(item) }

        // ---------- DATE ----------
        if (item.createdAt > 0L) {
            holder.tvPublishDate.visibility = View.VISIBLE
            holder.tvPublishDate.text = DateTimeUtils.format(item.createdAt)
        } else {
            holder.tvPublishDate.visibility = View.GONE
            holder.tvPublishDate.text = ""
        }

        // ---------- MENU ----------
        holder.btnMore.setOnClickListener {
            showMoreMenu(context, holder.btnMore, item)
        }

        // ---------- IMAGE ----------
        bindImage(holder, item)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<PublicFeedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    // ================= AUTHOR NAME =================

    private fun bindAuthorName(holder: FeedViewHolder, item: PublicFeedItem) {
        val authorId = item.authorId.trim()
        holder.tvAuthorName.tag = authorId

        usernameCache[authorId]?.let {
            holder.tvAuthorName.text = it
            return
        }

        holder.tvAuthorName.text = item.authorName ?: "User"

        if (authorId.isBlank()) return

        db.collection("public_users").document(authorId).get()
            .addOnSuccessListener { doc ->
                val name = doc.getString("username")?.trim().orEmpty()
                if (name.isNotBlank()) {
                    usernameCache[authorId] = name
                    if (holder.tvAuthorName.tag == authorId) {
                        holder.tvAuthorName.text = name
                    }
                }
            }
    }

    // ================= IMAGE =================

    private fun bindImage(holder: FeedViewHolder, item: PublicFeedItem) {
        val context = holder.itemView.context

        val localPath = item.localImagePath?.trim().orEmpty()
        val url = item.imageUri?.trim().orEmpty()
        val online = hasInternet(context)

        // 1) локальная копия (если есть)
        if (localPath.isNotBlank()) {
            val f = File(localPath)
            if (f.exists() && f.length() > 0) {
                holder.ivPaletteImage.visibility = View.VISIBLE
                Glide.with(context)
                    .load(f)
                    .centerCrop()
                    .placeholder(R.drawable.placeholder_image)
                    .into(holder.ivPaletteImage)

                holder.ivPaletteImage.setOnClickListener {
                    FullscreenImageDialog(context, f).show()
                }
                return
            }
        }

        // 2) нет локальной и нет url
        if (url.isBlank()) {
            holder.ivPaletteImage.visibility = View.GONE
            holder.ivPaletteImage.setImageDrawable(null)
            holder.ivPaletteImage.setOnClickListener(null)
            return
        }

        // 3) есть url, но оффлайн
        holder.ivPaletteImage.visibility = View.VISIBLE
        if (!online) {
            holder.ivPaletteImage.setImageResource(R.drawable.placeholder_image)
            holder.ivPaletteImage.setOnClickListener {
                Toast.makeText(
                    context,
                    "Нет интернета — изображение недоступно оффлайн",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        // 4) онлайн — грузим по url
        Glide.with(context)
            .load(url) // должен быть https://
            .centerCrop()
            .placeholder(R.drawable.placeholder_image)
            .into(holder.ivPaletteImage)

        holder.ivPaletteImage.setOnClickListener {
            FullscreenImageDialog(context, url).show()
        }
    }

    private fun hasInternet(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val nc = cm.getNetworkCapabilities(nw) ?: return false
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    // ================= UI HELPERS =================

    private fun isDarkTheme(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun renderTags(container: LinearLayout, tags: List<String>, density: Float) {
        container.removeAllViews()
        if (tags.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        val textColor = if (isDarkTheme(container.context)) Color.WHITE else Color.BLACK

        tags.forEach { tag ->
            val chip = TextView(container.context).apply {
                text = tag
                textSize = 13f
                setTextColor(textColor)
                setPadding(
                    (8 * density).toInt(),
                    (4 * density).toInt(),
                    (8 * density).toInt(),
                    (4 * density).toInt()
                )
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#66000000"))
                    cornerRadius = 16 * density
                }
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (6 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }

            container.addView(chip, lp)
        }
    }

    private fun renderPalette(
        context: Context,
        container: LinearLayout,
        colors: List<String>,
        density: Float
    ) {
        container.removeAllViews()

        val size = (64 * density).toInt()
        val margin = (8 * density).toInt()
        val textColor = if (isDarkTheme(context)) Color.WHITE else Color.BLACK

        colors.forEach { hex ->
            val colorInt = try { Color.parseColor(hex) } catch (_: Exception) { Color.GRAY }

            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, margin, margin, margin) }
            }

            val colorView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(colorInt)
                }
            }

            val tvHex = TextView(context).apply {
                text = hex
                textSize = 12f
                setTextColor(textColor)
                setPadding(
                    (8 * density).toInt(),
                    (4 * density).toInt(),
                    (8 * density).toInt(),
                    0
                )
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#66000000"))
                    cornerRadius = 8 * density
                }
                setOnClickListener { copyToClipboard(context, hex) }
            }

            wrapper.addView(colorView)
            wrapper.addView(tvHex)
            container.addView(wrapper)
        }
    }

    private fun applyVoteState(holder: FeedViewHolder, vote: Int) {
        when (vote) {
            1 -> {
                holder.btnLike.alpha = 1f
                holder.btnDislike.alpha = 0.4f
            }
            -1 -> {
                holder.btnLike.alpha = 0.4f
                holder.btnDislike.alpha = 1f
            }
            else -> {
                holder.btnLike.alpha = 0.8f
                holder.btnDislike.alpha = 0.8f
            }
        }
    }

    private fun showMoreMenu(context: Context, anchor: View, item: PublicFeedItem) {
        val popup = PopupMenu(context, anchor, Gravity.END)
        popup.menu.add("Сохранить в мои палитры").setOnMenuItemClickListener {
            onSaveToMyPalettes(item); true
        }
        popup.menu.add("Поделиться палитрой").setOnMenuItemClickListener {
            onSharePalette(item); true
        }
        popup.show()
    }

    private fun copyToClipboard(context: Context, hex: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("color", hex))
        Toast.makeText(context, "$hex скопирован", Toast.LENGTH_SHORT).show()
    }
}
