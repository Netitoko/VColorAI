package com.example.vcolorai.ui.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.vcolorai.databinding.FragmentRulesBinding
import com.example.vcolorai.ui.common.BaseFragment
import com.example.vcolorai.util.MarkdownTextFormatter
import com.google.firebase.firestore.FirebaseFirestore

class RulesFragment : BaseFragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding

    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.btnCloseRules?.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        loadRulesFromFirestore()
    }

    override fun applyInsets(root: View) {
        val b = binding ?: return
        val initialRootTop = b.rulesRoot.paddingTop
        val initialBtnTop = b.btnCloseRules.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(b.rulesRoot) { _, insets ->
            val bb = binding ?: return@setOnApplyWindowInsetsListener insets

            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())

            bb.rulesRoot.updatePadding(top = initialRootTop + statusBar.top)
            bb.btnCloseRules.updatePadding(top = initialBtnTop + statusBar.top)

            insets
        }
    }

    private fun loadRulesFromFirestore() {
        val b = binding ?: return
        b.tvRules.text = "Загрузка правил…"

        db.collection("app_config")
            .document("publication_rules")
            .get()
            .addOnSuccessListener { doc ->
                val bb = binding ?: return@addOnSuccessListener

                if (!doc.exists()) {
                    val msg = "Документ app_config/publication_rules не найден"
                    bb.tvRules.text = msg + "\n\n(Проверь, что ты создала документ в Firestore)"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    Log.e(TAG, msg)
                    return@addOnSuccessListener
                }

                // важно: название поля должно совпадать
                val markdown = doc.getString("markdown")

                if (markdown.isNullOrBlank()) {
                    val msg = "Поле 'markdown' пустое или отсутствует"
                    bb.tvRules.text = msg + "\n\n(Проверь поле 'markdown' в документе)"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    Log.e(TAG, msg)
                    return@addOnSuccessListener
                }

                bb.tvRules.text = MarkdownTextFormatter.format(markdown)
            }
            .addOnFailureListener { e ->
                val bb = binding ?: return@addOnFailureListener
                val msg = "Ошибка загрузки правил: ${e.message}"

                bb.tvRules.text = """
                    Не удалось загрузить правила.

                    Причина:
                    ${e.message}

                    Частые причины:
                    • PERMISSION_DENIED — Firestore Rules запрещают чтение
                    • нет интернета
                    • документ/поле неверные
                """.trimIndent()

                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                Log.e(TAG, msg, e)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "RulesFragment"
    }
}
