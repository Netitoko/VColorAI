package com.example.vcolorai.ui.common

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment

// Базовый фрагмент
abstract class BaseFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyInsets(view)
    }

    // Применение системных inset'ов
    open fun applyInsets(root: View) {
        val initialTopPadding = root.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
            )

            // Добавляем только статус-бар
            v.updatePadding(
                top = initialTopPadding + statusBarInsets.top
            )

            // Нижний inset не трогаем
            insets
        }
    }
}
