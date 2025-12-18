package com.example.vcolorai.ui.common

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment

/**
 * Базовый фрагмент:
 * - добавляет ТОЛЬКО верхний inset (status bar)
 * - НЕ трогает нижний inset (bottom navigation / gesture bar)
 *
 * Если фрагменту нужно особое поведение — он переопределяет applyInsets()
 */
abstract class BaseFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyInsets(view)
    }

    open fun applyInsets(root: View) {
        val initialTopPadding = root.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
            )

            // ✅ добавляем только статус-бар
            v.updatePadding(
                top = initialTopPadding + statusBarInsets.top
            )

            // ❌ bottom НЕ добавляем вообще
            insets
        }
    }
}
