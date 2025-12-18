package com.example.vcolorai.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import com.bumptech.glide.Glide
import io.getstream.photoview.PhotoView

class FullscreenImageDialog(
    context: Context,
    private val image: Any
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val photoView = PhotoView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)

            // hero-like анимация появления
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .start()
        }

        Glide.with(context)
            .load(image)
            .into(photoView)

        // Тап — закрыть с анимацией
        photoView.setOnClickListener {
            photoView.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(180)
                .withEndAction { dismiss() }
                .start()
        }

        setContentView(photoView)
    }
}
