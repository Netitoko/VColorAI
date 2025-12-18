package com.example.vcolorai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.vcolorai.databinding.ActivitySplashBinding
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {

        // ✅ 1) Применяем сохранённый язык приложения (RU/EN/системный)
        AppCompatDelegate.setApplicationLocales(AppCompatDelegate.getApplicationLocales())

        // ✅ 2) (опционально, но советую) применяем сохранённую тему ДО setContentView
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedTheme = prefs.getInt(
            "app_theme",
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
        AppCompatDelegate.setDefaultNightMode(savedTheme)

        super.onCreate(savedInstanceState)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.root.postDelayed({
            val currentUser = auth.currentUser
            val localPrefs = getSharedPreferences("local_users", MODE_PRIVATE)
            val savedUsers = localPrefs.all

            // Если пользователь авторизован, но его нет в сохранённых — выходим из аккаунта
            if (currentUser != null && !savedUsers.containsKey(currentUser.uid)) {
                auth.signOut()
            }

            when {
                auth.currentUser != null -> {
                    startActivity(Intent(this, MainActivity::class.java))
                }
                savedUsers.isNotEmpty() -> {
                    startActivity(Intent(this, ChooseAccountActivity::class.java))
                }
                else -> {
                    startActivity(Intent(this, LoginActivity::class.java))
                }
            }
            finish()
        }, 2000)
    }
}
