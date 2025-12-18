package com.example.vcolorai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.vcolorai.GuestGenerationFragment

/**
 * Activity для гостевого режима.
 *
 * Внутри отображается GuestGenerationFragment,
 * который полностью повторяет GenerationFragment,
 * но БЕЗ сохранения в Firestore.
 */
class GuestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Простой контейнер под Fragment
        setContentView(R.layout.activity_guest)

        // Чтобы не пересоздавать фрагмент при повороте экрана
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
