package com.example.vcolorai

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.example.vcolorai.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var generatedCode: String? = null
    private var isTimerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // 🧠 Подсказки по паролю
        binding.etPassword.addTextChangedListener {
            updatePasswordRequirements(it.toString())
        }

        // 💌 Отправка письма
        binding.btnSendCode.setOnClickListener {
            if (isTimerRunning) {
                Toast.makeText(this, "Подождите, прежде чем запросить новый код", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val email = binding.etEmail.text.toString().trim()
            val username = binding.etUsername.text.toString().trim()

            if (!isEmailValid(email)) {
                Toast.makeText(this, "Введите корректный email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isUsernameValid(username)) {
                Toast.makeText(this, "Ник не должен содержать пробелы", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            generatedCode = (100000..999999).random().toString()
            sendVerificationEmail(email, username, generatedCode!!)
            startCountdownTimer()
        }

        // ✅ Регистрация
        binding.btnRegister.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val confirmPassword = binding.etConfirmPassword.text.toString().trim()
            val code = binding.etVerifyCode.text.toString().trim()
            val phone = binding.etPhone.text.toString().trim()

            if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, "Заполните все обязательные поля", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isUsernameValid(username)) {
                Toast.makeText(this, "Ник не должен содержать пробелы", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isEmailValid(email)) {
                Toast.makeText(this, "Введите корректный email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isPasswordStrong(password)) {
                Toast.makeText(this, "Пароль не соответствует требованиям", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(this, "Пароли не совпадают", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (generatedCode == null || code != generatedCode) {
                Toast.makeText(this, "Неверный или отсутствующий код подтверждения", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            registerUser(email, password, username, phone)
        }

        // 🔄 Переход на вход
        binding.tvLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // ---------- Username helpers ----------

    private fun usernameKey(username: String): String =
        username.trim().lowercase()

    private fun isUsernameValid(username: String): Boolean {
        if (username.isBlank()) return false
        // запрет любых пробелов/табов/переводов строк
        if (username.any { it.isWhitespace() }) return false
        return true
    }

    // ✅ Регистрация пользователя + уникальный ник
    // ✅ Регистрация пользователя + уникальный ник (через транзакцию usernames/{key})
    private fun registerUser(email: String, password: String, username: String, phone: String) {
        val key = usernameKey(username)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Toast.makeText(
                        this,
                        "Ошибка регистрации: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addOnCompleteListener
                }

                val firebaseUser = auth.currentUser
                val userId = firebaseUser?.uid
                if (firebaseUser == null || userId.isNullOrBlank()) {
                    Toast.makeText(this, "Ошибка: не удалось создать пользователя", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                val usernameRef = db.collection("usernames").document(key)
                val now = System.currentTimeMillis()

                // 1) Занимаем ник атомарно
                db.runTransaction { tx ->
                    val snap = tx.get(usernameRef)
                    if (snap.exists()) {
                        throw IllegalStateException("Ник уже занят")
                    }
                    tx.set(usernameRef, mapOf("uid" to userId, "createdAt" to now))
                    true
                }.addOnSuccessListener {

                    // 2) displayName (не критично)
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(username)
                        .build()
                    firebaseUser.updateProfile(profileUpdates)

                    // 3) Пишем документы профиля
                    val userMap = hashMapOf(
                        "username" to username,
                        "email" to email,
                        "registrationDate" to now,
                        "favoritePalettes" to listOf<String>(),
                        "isPublic" to true,
                        "phone" to phone
                    )

                    val profileMap = hashMapOf(
                        "uid" to userId,
                        "email" to email,
                        "username" to username,
                        "phone" to phone,
                        "avatarUrl" to "default_gray",
                        "createdAt" to now
                    )

                    val publicUserMap = hashMapOf(
                        "uid" to userId,
                        "username" to username,
                        "avatarUrl" to "default_gray",
                        "createdAt" to now
                    )

                    val batch = db.batch()
                    batch.set(db.collection("users").document(userId), userMap)
                    batch.set(db.collection("user_profiles").document(userId), profileMap)
                    batch.set(db.collection("public_users").document(userId), publicUserMap)

                    batch.commit()
                        .addOnSuccessListener {
                            showSuccessDialog()
                        }
                        .addOnFailureListener { e ->
                            // откат: освобождаем ник и удаляем аккаунт
                            usernameRef.delete()
                            firebaseUser.delete()
                            Toast.makeText(this, "Ошибка сохранения профиля: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                }.addOnFailureListener { e ->
                    // Ник занят / ошибка транзакции -> удаляем auth пользователя
                    firebaseUser.delete()
                    val msg = if (e.message?.contains("Ник уже занят", true) == true)
                        "Ник уже занят. Выберите другой."
                    else
                        "Ошибка: ${e.message}"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
    }

    // 🎨 Красивое окно успешной регистрации
    private fun showSuccessDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_success, null)
        val successIcon = dialogView.findViewById<ImageView>(R.id.ivSuccess)
        val messageText = dialogView.findViewById<TextView>(R.id.tvMessage)

        messageText.text = "Регистрация успешно завершена! 🎉"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        val scaleAnim = ScaleAnimation(
            0.8f, 1.0f, 0.8f, 1.0f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 400
            fillAfter = true
        }

        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 500
            fillAfter = true
        }

        dialogView.startAnimation(fadeIn)
        successIcon.startAnimation(scaleAnim)

        dialogView.postDelayed({
            dialog.dismiss()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }, 2500)
    }

    // 💌 Отправка письма
    private fun sendVerificationEmail(email: String, username: String, code: String) {
        val subject = "🎨 Verify your VColorAI account!"
        val message = """
            Hello $username!
            
            Thank you for registering with VColorAI 🌈
            
            Your verification code is: $code

            Please enter this code in the app to confirm your email address.

            If you did not create an account, please ignore this message.
            
            — The VColorAI Team 🎨
        """.trimIndent()

        Toast.makeText(this, "Отправка письма...", Toast.LENGTH_SHORT).show()

        EmailSender.sendEmail(email, subject, message) { success, error ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Письмо отправлено на $email!", Toast.LENGTH_LONG).show()
                    binding.etVerifyCode.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Ошибка отправки письма: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ⏳ Таймер для кнопки отправки кода
    private fun startCountdownTimer() {
        isTimerRunning = true
        binding.btnSendCode.isEnabled = false

        object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                binding.btnSendCode.text = "Отправить повторно через $secondsLeft c"
            }

            override fun onFinish() {
                binding.btnSendCode.text = "Отправить код снова"
                binding.btnSendCode.isEnabled = true
                isTimerRunning = false
            }
        }.start()
    }

    // Проверки
    private fun isEmailValid(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun isPasswordStrong(password: String): Boolean {
        val pattern = Regex("^(?=.*[A-Z])(?=.*[0-9])(?=.*[!@#\$%^&*()_+=\\-{}\\[\\]:;\"'<>,.?/]).{6,}\$")
        return password.matches(pattern)
    }

    private fun updatePasswordRequirements(password: String) {
        binding.tvReqLength.setTextColor(if (password.length >= 6) 0xFF00C853.toInt() else 0xFF888888.toInt())
        binding.tvReqUppercase.setTextColor(if (password.any { it.isUpperCase() }) 0xFF00C853.toInt() else 0xFF888888.toInt())
        binding.tvReqNumber.setTextColor(if (password.any { it.isDigit() }) 0xFF00C853.toInt() else 0xFF888888.toInt())
        binding.tvReqSpecial.setTextColor(if (password.any { "!@#\$%^&*()_+=-{}[]:;\"'<>,.?/".contains(it) }) 0xFF00C853.toInt() else 0xFF888888.toInt())
    }
}
