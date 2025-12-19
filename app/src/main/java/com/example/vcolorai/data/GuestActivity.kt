package com.example.vcolorai.data

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.vcolorai.R
import com.example.vcolorai.ui.generation.GuestGenerationFragment

class GuestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Установка контейнера для фрагмента
        setContentView(R.layout.activity_guest)

        // Инициализация фрагмента (только при первом создании)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    GuestGenerationFragment()
                )
                .commit()
        }
    }
}