package com.example.vcolorai.ui.common

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import com.example.vcolorai.R

// Диалог загрузки
class LoadingDialog(context: Context) : Dialog(context) {

    init {
        // Запрет закрытия
        setCancelable(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Окно без заголовка
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_loading)

        // Прозрачный фон
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}
