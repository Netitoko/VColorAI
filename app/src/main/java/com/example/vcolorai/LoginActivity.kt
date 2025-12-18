package com.example.vcolorai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.vcolorai.databinding.ActivityLoginBinding
import com.example.vcolorai.ui.auth.ForgotPasswordDialogFragment
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Вход
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Пожалуйста, заполните все поля", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginUser(email, password)
        }

        // 🔐 Забыли пароль? — теперь открывает наш диалог
        binding.tvForgotPassword.setOnClickListener {
            ForgotPasswordDialogFragment()
                .show(supportFragmentManager, "forgot_password")
        }

        // Гостевой вход
        binding.btnGuest.setOnClickListener {
            startActivity(Intent(this, GuestActivity::class.java))
            finish()
        }

        // Переход к регистрации
        binding.tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Если пришёл email из ChooseAccountActivity — подставляем его
        val selectedEmail = intent.getStringExtra("selectedEmail")
        if (!selectedEmail.isNullOrEmpty()) {
            binding.etEmail.setText(selectedEmail)
        }
    }

    private fun loginUser(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    Toast.makeText(this, "Успешный вход!", Toast.LENGTH_SHORT).show()

                    // ✅ Сохраняем пользователя, если стоит галочка
                    if (binding.cbRememberMe.isChecked && user != null) {
                        saveUserLocally(user.uid, user.email ?: "")
                    }

                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "Ошибка входа: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun saveUserLocally(uid: String, email: String) {
        val prefs = getSharedPreferences("local_users", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(uid, email)
            .apply()
    }
}
