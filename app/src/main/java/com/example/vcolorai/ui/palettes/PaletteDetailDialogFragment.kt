package com.example.vcolorai.ui.palettes

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.example.vcolorai.model.SavedPalette
import com.example.vcolorai.ui.common.FullscreenImageDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class PaletteDetailDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        private const val ARG_NAME = "arg_name"
        private const val ARG_COLORS = "arg_colors"
        private const val ARG_SOURCE_TYPE = "arg_source_type"
        private const val ARG_IMAGE_URI = "arg_image_uri"
        private const val ARG_TAGS = "arg_tags"

        fun newInstance(palette: SavedPalette): PaletteDetailDialogFragment {
            return PaletteDetailDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, palette.id)
                    putString(ARG_NAME, palette.paletteName)
                    putStringArrayList(ARG_COLORS, ArrayList(palette.colors))
                    putString(ARG_SOURCE_TYPE, palette.sourceType)
                    putString(ARG_IMAGE_URI, palette.imageUri)
                    putStringArrayList(ARG_TAGS, ArrayList(palette.tags))
                }
            }
        }
    }

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_palette_detail, null)

        val btnClose: ImageButton = view.findViewById(R.id.btnClose)
        btnClose.setOnClickListener { dismiss() }

        val ivImage: ImageView = view.findViewById(R.id.ivPaletteImage)
        val etTitle: EditText = view.findViewById(R.id.etPaletteTitle)
        val container: LinearLayout = view.findViewById(R.id.fullPaletteContainer)
        val tagsContainer: LinearLayout = view.findViewById(R.id.tagsChipsContainer)
        val etTagInput: EditText = view.findViewById(R.id.etTagInput)
        val btnAddTag: ImageButton = view.findViewById(R.id.btnAddTag)
        val btnSave: Button = view.findViewById(R.id.btnSave)
        val btnPublish: Button = view.findViewById(R.id.btnPublish)

        val paletteId = arguments?.getString(ARG_ID).orEmpty()
        val name = arguments?.getString(ARG_NAME).orEmpty()
        val colors = arguments?.getStringArrayList(ARG_COLORS) ?: arrayListOf()
        val sourceType = arguments?.getString(ARG_SOURCE_TYPE).orEmpty()
        val imageUri = arguments?.getString(ARG_IMAGE_URI)
        val tags = arguments?.getStringArrayList(ARG_TAGS)?.toMutableList() ?: mutableListOf()

        etTitle.setText(name)

        // ---------- IMAGE ----------
        if (!imageUri.isNullOrBlank() && (sourceType == "image" || sourceType == "combined")) {
            ivImage.visibility = View.VISIBLE
            Glide.with(this).load(imageUri).centerCrop().into(ivImage)
            ivImage.setOnClickListener {
                FullscreenImageDialog(requireContext(), imageUri).show()
            }
        } else {
            ivImage.visibility = View.GONE
        }

        // ---------- COLORS ----------
        val density = resources.displayMetrics.density
        colors.forEach { hex ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val colorView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (48 * density).toInt(),
                    1f
                ).apply {
                    setMargins(
                        (6 * density).toInt(),
                        (6 * density).toInt(),
                        (6 * density).toInt(),
                        (6 * density).toInt()
                    )
                }

                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(Color.parseColor(hex))
                }
            }


            val tvHex = TextView(requireContext()).apply {
                text = hex
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    val cb = requireContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("color", hex))
                    Toast.makeText(requireContext(), "Скопировано", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(colorView)
            row.addView(tvHex)
            container.addView(row)
        }

        // ---------- TAGS ----------
        fun renderTags() {
            tagsContainer.removeAllViews()
            tags.forEach { tag ->
                val chip = TextView(requireContext()).apply {
                    text = tag
                    setPadding(20, 10, 20, 10)
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = 20f
                        setColor(Color.DKGRAY)
                    }
                    setOnLongClickListener {
                        tags.remove(tag)
                        renderTags()
                        true
                    }
                }
                tagsContainer.addView(chip)
            }
        }

        renderTags()

        btnAddTag.setOnClickListener {
            val t = etTagInput.text.toString().trim()
            if (t.isNotEmpty()) {
                tags.add(t)
                etTagInput.setText("")
                renderTags()
            }
        }

        // ---------- SAVE ----------
        btnSave.setOnClickListener {
            db.collection("color_palettes").document(paletteId)
                .update(
                    mapOf(
                        "paletteName" to etTitle.text.toString().trim(),
                        "tags" to tags
                    )
                )
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Сохранено", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
        }

        // ---------- PUBLISH / UNPUBLISH ----------
        db.collection("color_palettes").document(paletteId)
            .get()
            .addOnSuccessListener { snap ->
                val isPublic = snap.getBoolean("isPublic") == true
                val publishedAt = snap.getLong("publishedAt")

                when {
                    isPublic -> {
                        btnPublish.text = "Снять с публикации"
                        btnPublish.isEnabled = true
                        btnPublish.setOnClickListener { unpublishPalette(paletteId) }
                    }

                    publishedAt != null -> {
                        btnPublish.text = "Уже публиковалась"
                        btnPublish.isEnabled = false
                    }

                    else -> {
                        btnPublish.text = "Опубликовать"
                        btnPublish.isEnabled = true
                        btnPublish.setOnClickListener { publishPalette(paletteId) }
                    }
                }
            }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    private fun publishPalette(paletteId: String) {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection("color_palettes").document(paletteId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (snap.getBoolean("isPublic") == true) error("ALREADY_PUBLIC")
            if (snap.contains("publishedAt")) error("ALREADY_PUBLISHED_ONCE")

            tx.update(ref, mapOf(
                "isPublic" to true,
                "publishedAt" to System.currentTimeMillis()
            ))
        }.addOnSuccessListener {
            Toast.makeText(requireContext(), "Опубликовано", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun unpublishPalette(paletteId: String) {
        val ref = db.collection("color_palettes").document(paletteId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (snap.getBoolean("isPublic") != true) error("NOT_PUBLIC")

            tx.update(ref, mapOf(
                "isPublic" to false,
                "unpublishedAt" to System.currentTimeMillis()
            ))
        }.addOnSuccessListener {
            Toast.makeText(requireContext(), "Снято с публикации", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }
}
