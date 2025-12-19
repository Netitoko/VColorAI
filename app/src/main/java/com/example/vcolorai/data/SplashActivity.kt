package com.example.vcolorai.data

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.vcolorai.databinding.ActivitySplashBinding
import com.example.vcolorai.ui.auth.ChooseAccountActivity
import com.example.vcolorai.ui.auth.LoginActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {

        // Применение сохраненной темы перед инициализацией интерфейса
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

        // Задержка для отображения splash-экрана с последующим переходом
        binding.root.postDelayed({
            val currentUser = auth.currentUser
            val localPrefs = getSharedPreferences("local_users", MODE_PRIVATE)
            val savedUsers = localPrefs.all

            // Проверка согласованности: если пользователь авторизован в Firebase,
            // но отсутствует в локальном хранилище, выполняем выход
            if (currentUser != null && !savedUsers.containsKey(currentUser.uid)) {
                auth.signOut()
            }

            // Выбор активности для перехода в зависимости от состояния авторизации
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