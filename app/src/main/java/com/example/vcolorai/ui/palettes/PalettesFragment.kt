package com.example.vcolorai.ui.palettes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.vcolorai.databinding.FragmentPalettesBinding
import com.example.vcolorai.model.SavedPalette
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.vcolorai.ui.common.BaseFragment

class PalettesFragment : BaseFragment() {

    private var _binding: FragmentPalettesBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var adapter: PalettesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPalettesBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupRecyclerView()
        setupResultListener()
        setupTopBar()
        loadPalettes()

        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = PalettesAdapter(emptyList()) { palette ->
            showPaletteDetail(palette)
        }

        binding.rvPalettes.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvPalettes.adapter = adapter
    }

    private fun setupResultListener() {
        parentFragmentManager.setFragmentResultListener(
            "palette_updated",
            viewLifecycleOwner
        ) { _, bundle ->
            val id = bundle.getString("id") ?: return@setFragmentResultListener
            val name = bundle.getString("name") ?: return@setFragmentResultListener
            val tags = bundle.getStringArrayList("tags")?.toList() ?: emptyList()
            adapter.updateItem(id, name, tags)
        }
    }

    private fun setupTopBar() {
        binding.tvTitle.text = "VColor AI"

        binding.btnDeletePalettes.setOnClickListener {
            if (!adapter.isInSelectionMode()) {
                adapter.setSelectionMode(true)
                Toast.makeText(requireContext(), "Выберите палитры для удаления", Toast.LENGTH_SHORT).show()
            } else {
                val selected = adapter.getSelectedItems()
                if (selected.isEmpty()) {
                    Toast.makeText(requireContext(), "Не выбрано ни одной палитры", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val count = selected.size

                AlertDialog.Builder(requireContext())
                    .setTitle("Удалить палитры")
                    .setMessage("Вы уверены, что хотите удалить $count палитр(ы)?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        deleteSelectedPalettes(selected)
                    }
                    .show()
            }
        }
    }

    private fun deleteSelectedPalettes(selected: List<SavedPalette>) {
        val user = auth.currentUser ?: return
        val ids = selected.map { it.id }

        ids.forEach { paletteId ->

            val paletteRef = db.collection("color_palettes").document(paletteId)
            val userLikeRef = db.collection("users")
                .document(user.uid)
                .collection("liked_palettes")
                .document(paletteId)

            db.runBatch { batch ->
                batch.delete(paletteRef)
                batch.delete(userLikeRef) // ✅ КЛЮЧЕВОЕ ИЗМЕНЕНИЕ
            }.addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    "Ошибка удаления палитры: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        adapter.removeByIds(ids)
        adapter.setSelectionMode(false)
        Toast.makeText(requireContext(), "Удалено: ${ids.size}", Toast.LENGTH_SHORT).show()
    }


    private fun loadPalettes() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(
                requireContext(),
                "Доступно только авторизованным пользователям",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        db.collection("color_palettes")
            .whereEqualTo("userId", user.uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.mapNotNull { doc ->
                    val colorsAny = doc.get("colors") as? List<*>
                    if (colorsAny == null) return@mapNotNull null
                    val colors = colorsAny.map { it.toString() }

                    val tagsAny = doc.get("tags") as? List<*>
                    val tags = tagsAny?.map { it.toString() } ?: emptyList()

                    val sourceType = doc.getString("sourceType") ?: ""
                    val sourceData = doc.getString("sourceData")

                    // 👉 Пытаемся вытащить imageUri максимально надёжно
                    val fieldImageUri = doc.getString("imageUri")
                    val rawImageUri = when {
                        !fieldImageUri.isNullOrBlank() -> fieldImageUri
                        !sourceData.isNullOrBlank() -> sourceData
                        else -> null
                    }

                    SavedPalette(
                        id = doc.id,
                        paletteName = doc.getString("paletteName") ?: "",
                        colors = colors,
                        sourceType = sourceType,
                        sourceData = sourceData,
                        creationDate = doc.getLong("creationDate") ?: 0L,
                        tags = tags,
                        imageUri = rawImageUri,
                        promptText = doc.getString("promptText")
                    )
                }
                    .sortedByDescending { it.creationDate }

                adapter.submitList(list)
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    "Ошибка загрузки палитр: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun showPaletteDetail(palette: SavedPalette) {
        val dialog = PaletteDetailDialogFragment.newInstance(palette)
        dialog.show(parentFragmentManager, "palette_detail")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
