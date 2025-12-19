package com.example.vcolorai.ui.palettes

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vcolorai.R
import com.example.vcolorai.data.model.SavedPalette

// Адаптер списка палитр
class PalettesAdapter(
    private var items: List<SavedPalette>,
    private val onItemClickNormal: (SavedPalette) -> Unit
) : RecyclerView.Adapter<PalettesAdapter.PaletteViewHolder>() {

    // Выбранные палитры
    private val selectedIds = mutableSetOf<String>()

    // Режим множественного выбора
    private var selectionMode = false

    // ViewHolder палитры
    inner class PaletteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val colorViews: List<View> = listOf(
            itemView.findViewById(R.id.color1),
            itemView.findViewById(R.id.color2),
            itemView.findViewById(R.id.color3),
            itemView.findViewById(R.id.color4)
        )
        val tvName: TextView = itemView.findViewById(R.id.tvPaletteName)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaletteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_palette_preview, parent, false)
        return PaletteViewHolder(view)
    }

    override fun onBindViewHolder(holder: PaletteViewHolder, position: Int) {
        val item = items[position]

        // Подготовка цветов превью
        val colors = if (item.colors.size >= 4) {
            item.colors.take(4)
        } else {
            if (item.colors.isEmpty()) {
                listOf("#CCCCCC", "#CCCCCC", "#CCCCCC", "#CCCCCC")
            } else {
                val mutable = item.colors.toMutableList()
                while (mutable.size < 4) {
                    mutable.add(mutable.last())
                }
                mutable.take(4)
            }
        }

        colors.forEachIndexed { index, hex ->
            try {
                holder.colorViews[index]
                    .setBackgroundColor(Color.parseColor(hex))
            } catch (_: Exception) {
                holder.colorViews[index]
                    .setBackgroundColor(Color.parseColor("#CCCCCC"))
            }
        }

        holder.tvName.text = item.paletteName

        // Чекбокс и режим выбора
        if (selectionMode) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked = selectedIds.contains(item.id)
        } else {
            holder.cbSelect.visibility = View.GONE
            holder.cbSelect.isChecked = false
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(item)
                holder.cbSelect.isChecked = selectedIds.contains(item.id)
            } else {
                onItemClickNormal(item)
            }
        }

        holder.cbSelect.setOnClickListener {
            toggleSelection(item)
        }
    }

    override fun getItemCount(): Int = items.size

    // Обновление списка
    fun submitList(newItems: List<SavedPalette>) {
        items = newItems
        selectedIds.clear()
        notifyDataSetChanged()
    }

    // Режим выбора

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) {
            selectedIds.clear()
        }
        notifyDataSetChanged()
    }

    fun isInSelectionMode(): Boolean = selectionMode

    // Переключение выбора
    private fun toggleSelection(item: SavedPalette) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
        } else {
            selectedIds.add(item.id)
        }
        notifyDataSetChanged()
    }

    // Получение выбранных палитр
    fun getSelectedItems(): List<SavedPalette> =
        items.filter { selectedIds.contains(it.id) }

    // Удаление по ID
    fun removeByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        val mutable = items.toMutableList()
        mutable.removeAll { ids.contains(it.id) }
        items = mutable
        selectedIds.removeAll(ids.toSet())
        notifyDataSetChanged()
    }

    // Обновление палитры
    fun updateItem(id: String, newName: String, newTags: List<String>) {
        val index = items.indexOfFirst { it.id == id }
        if (index == -1) return

        val old = items[index]
        val updated = old.copy(
            paletteName = newName,
            tags = newTags
        )

        val mutable = items.toMutableList()
        mutable[index] = updated
        items = mutable
        notifyItemChanged(index)
    }
}
