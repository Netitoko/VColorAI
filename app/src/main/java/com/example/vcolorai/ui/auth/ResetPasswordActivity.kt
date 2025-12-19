package com.example.vcolorai.ui.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.example.vcolorai.databinding.ActivityResetPasswordBinding
import com.google.firebase.auth.FirebaseAuth

class ResetPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResetPasswordBinding
    private val auth by lazy { FirebaseAuth.getInstance() }

    private var oobCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Режим edge-to-edge (без отступов системных баров)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()

        // Обработка входящей ссылки (при открытии из email)
        handleIncomingUri(intent.data)

        // Ручной режим: вставка ссылки из письма
        binding.btnParseLink.setOnClickListener {
            val raw = binding.etLink.text.toString().trim()
            if (raw.isBlank()) {
                toast("Вставь ссылку из письма")
                return@setOnClickListener
            }
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            handleIncomingUri(uri)
        }

        // Валидация пароля в реальном времени
        binding.etNewPassword.addTextChangedListener {
            updatePasswordRequirements(it?.toString().orEmpty())
            updateConfirmEnabled()
        }
        binding.etConfirmPassword.addTextChangedListener { updateConfirmEnabled() }

        // Подтверждение смены пароля
        binding.btnConfirm.setOnClickListener {
            val code = oobCode
            if (code.isNullOrBlank()) {
                toast("Сначала вставь ссылку из письма (или открой её)")
                return@setOnClickListener
            }

            val newPass = binding.etNewPassword.text.toString().trim()
            val confirm = binding.etConfirmPassword.text.toString().trim()

            if (!isPasswordStrongLikeRegister(newPass)) {
                toast("Пароль не соответствует требованиям")
                return@setOnClickListener
            }
            if (newPass != confirm) {
                toast("Пароли не совпадают")
                return@setOnClickListener
            }

            setLoading(true)
            auth.confirmPasswordReset(code, newPass)
                .addOnSuccessListener {
                    setLoading(false)
                    toast("Пароль обновлён. Войдите заново.", true)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .addOnFailureListener { e ->
                    setLoading(false)
                    toast("Ошибка: ${e.message}", true)
                }
        }

        // Возврат к экрану входа
        binding.btnBackToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUri(intent.data)
    }

    // -------------------------------------------------------------------------
    // АДАПТАЦИЯ ПОД СИСТЕМНЫЕ ОТСТУПЫ (EDGE-TO-EDGE)
    // -------------------------------------------------------------------------

    private fun applyInsets() {
        val initialTop = binding.root.paddingTop
        val initialBottom = binding.root.paddingBottom
        val initialLeft = binding.root.paddingLeft
        val initialRight = binding.root.paddingRight

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.root.updatePadding(
                left = initialLeft,
                top = initialTop + status.top,
                right = initialRight,
                bottom = initialBottom + nav.bottom
            )

            insets
        }
    }

    // -------------------------------------------------------------------------
    // ОБРАБОТКА ВХОДЯЩЕЙ ССЫЛКИ СБРОСА ПАРОЛЯ
    // -------------------------------------------------------------------------

    private fun handleIncomingUri(data: Uri?) {
        binding.tvDebugLink.text = data?.toString() ?: "Вставь ссылку из письма ниже"

        val actionUri = extractFirebaseActionUri(data)
        val code = actionUri?.getQueryParameter("oobCode")

        if (code.isNullOrBlank()) {
            setFormEnabled(false)
            return
        }

        oobCode = code
        setLoading(true)
        auth.verifyPasswordResetCode(code)
            .addOnSuccessListener {
                setLoading(false)
                setFormEnabled(true)
                toast("Ссылка подтверждена. Введи новый пароль.")
            }
            .addOnFailureListener {
                setLoading(false)
                setFormEnabled(false)
                toast("Ссылка недействительна или устарела", true)
            }
    }

    // Извлечение Firebase action URI из различных форматов ссылок
    private fun extractFirebaseActionUri(original: Uri?): Uri? {
        if (original == null) return null
        val path = original.path ?: ""

        // Формат: /__/auth/links?link=...
        if (path.startsWith("/__/auth/links")) {
            val inner = original.getQueryParameter("link") ?: return null
            return runCatching { Uri.parse(inner) }.getOrNull()
        }

        if (path.startsWith("/__/auth/action") || path.startsWith("/__/auth/handler")) {
            return original
        }

        return null
    }

    // -------------------------------------------------------------------------
    // УПРАВЛЕНИЕ СОСТОЯНИЕМ ФОРМЫ
    // -------------------------------------------------------------------------

    private fun setFormEnabled(enabled: Boolean) {
        binding.etNewPassword.isEnabled = enabled
        binding.etConfirmPassword.isEnabled = enabled
        binding.btnConfirm.isEnabled = enabled && canConfirm()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConfirm.isEnabled = !loading && canConfirm()
        binding.etNewPassword.isEnabled = !loading && (oobCode != null)
        binding.etConfirmPassword.isEnabled = !loading && (oobCode != null)
    }

    private fun updateConfirmEnabled() {
        binding.btnConfirm.isEnabled = canConfirm()
    }

    private fun canConfirm(): Boolean {
        val codeOk = !oobCode.isNullOrBlank()
        val pass = binding.etNewPassword.text?.toString().orEmpty().trim()
        val confirm = binding.etConfirmPassword.text?.toString().orEmpty().trim()
        return codeOk && pass.isNotBlank() && confirm.isNotBlank() && pass == confirm && isPasswordStrongLikeRegister(pass)
    }

    // -------------------------------------------------------------------------
    // ПРОВЕРКА СЛОЖНОСТИ ПАРОЛЯ
    // -------------------------------------------------------------------------

    private fun isPasswordStrongLikeRegister(password: String): Boolean {
        val pattern =
            Regex("^(?=.*[A-Z])(?=.*[0-9])(?=.*[!@#\$%^&*()_+=\\-{}\\[\\]:;\"'<>,?/]).{6,}\$")
        return password.matches(pattern)
    }

    // Визуальная индикация требований к паролю
    private fun updatePasswordRequirements(password: String) {
        val ok = 0xFF00C853.toInt()
        val gray = 0xFF888888.toInt()

        binding.tvReqLength.setTextColor(if (password.length >= 6) ok else gray)
        binding.tvReqUppercase.setTextColor(if (password.any { it.isUpperCase() }) ok else gray)
        binding.tvReqNumber.setTextColor(if (password.any { it.isDigit() }) ok else gray)

        val specials = "!@#\$%^&*()_+=-{}[]:;\"'<>,.?/"
        binding.tvReqSpecial.setTextColor(if (password.any { specials.contains(it) }) ok else gray)
    }

    // -------------------------------------------------------------------------
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // -------------------------------------------------------------------------

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}