package com.example.vcolorai.ui.auth

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.vcolorai.EmailSender
import com.example.vcolorai.R
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth

// Диалог восстановления пароля
class ForgotPasswordDialogFragment : DialogFragment() {

    // Firebase auth
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Код подтверждения
    private var code: String? = null

    // Таймер повторной отправки
    private var timer: CountDownTimer? = null
    private var timerRunning = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appContext = requireContext().applicationContext
        val view = LayoutInflater.from(appContext)
            .inflate(R.layout.dialog_forgot_password, null)

        val etEmail = view.findViewById<EditText>(R.id.etEmail)
        val btnSendCode = view.findViewById<Button>(R.id.btnSendCode)

        val etCode = view.findViewById<EditText>(R.id.etCode)
        val btnConfirmCode = view.findViewById<Button>(R.id.btnConfirmCode)

        // Toast helper
        fun toast(msg: String, long: Boolean = false) {
            Toast.makeText(
                appContext,
                msg,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }

        // Показать ввод кода
        fun showCodeUi() {
            etCode.visibility = View.VISIBLE
            btnConfirmCode.visibility = View.VISIBLE
        }

        // Таймер ожидания перед повторной отправкой
        fun startCountdown() {
            timer?.cancel()
            timerRunning = true
            btnSendCode.isEnabled = false

            timer = object : CountDownTimer(60_000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    btnSendCode.text =
                        "Повторно через ${millisUntilFinished / 1000} c"
                }

                override fun onFinish() {
                    btnSendCode.text = "Отправить код снова"
                    btnSendCode.isEnabled = true
                    timerRunning = false
                }
            }.start()
        }

        // Диалоги после отправки письма Firebase
        fun showAfterEmailDialogs(email: String) {
            AlertDialog.Builder(requireContext())
                .setTitle("Письмо отправлено")
                .setMessage("Мы отправили письмо для сброса пароля на:\n$email")
                .setCancelable(false)
                .setPositiveButton("Ок") { d1, _ ->
                    d1.dismiss()

                    AlertDialog.Builder(requireContext())
                        .setTitle("Дальше — вставьте ссылку")
                        .setMessage(
                            "Откройте письмо, скопируйте ссылку целиком и вставьте её в приложении.\n\n" +
                                    "Нажмите «Вставить ссылку» — откроется нужное окно."
                        )
                        .setCancelable(false)
                        .setPositiveButton("Вставить ссылку") { d2, _ ->
                            d2.dismiss()
                            startActivity(
                                Intent(
                                    requireContext(),
                                    ResetPasswordActivity::class.java
                                )
                            )
                            dismissAllowingStateLoss()
                        }
                        .setNegativeButton("Закрыть") { d2, _ ->
                            d2.dismiss()
                            dismissAllowingStateLoss()
                        }
                        .show()
                }
                .show()
        }

        // Отправка reset-ссылки Firebase
        fun sendFirebaseResetLink(
            email: String,
            onDone: (Boolean, String?) -> Unit
        ) {
            val continueUrl =
                "https://vcolorai-a9616.firebaseapp.com/reset"

            val acs = ActionCodeSettings.newBuilder()
                .setUrl(continueUrl)
                .setHandleCodeInApp(true)
                .setAndroidPackageName(
                    appContext.packageName,
                    true,
                    null
                )
                .build()

            auth.sendPasswordResetEmail(email, acs)
                .addOnSuccessListener { onDone(true, null) }
                .addOnFailureListener { e ->
                    onDone(false, e.message)
                }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Восстановление пароля")
            .setView(view)
            .setNegativeButton("Закрыть", null)
            .create()

        // Отправка кода
        btnSendCode.setOnClickListener {
            if (timerRunning) {
                toast("Подождите перед повторной отправкой")
                return@setOnClickListener
            }

            val email = etEmail.text
                .toString()
                .trim()
                .lowercase()

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast("Введите корректный email")
                return@setOnClickListener
            }

            code = (100000..999999).random().toString()
            val subject = "VColorAI — восстановление пароля"
            val message = "Ваш код подтверждения: $code"

            toast("Отправка кода...")

            EmailSender.sendEmail(email, subject, message) { success, error ->
                activity?.runOnUiThread {
                    if (success) {
                        toast("Код отправлен на $email", true)
                        showCodeUi()
                        startCountdown()
                    } else {
                        toast("Ошибка отправки: $error", true)
                    }
                }
            }
        }

        // Проверка кода и отправка письма Firebase
        btnConfirmCode.setOnClickListener {
            val entered = etCode.text.toString().trim()
            if (code.isNullOrBlank() || entered != code) {
                toast("Неверный код")
                return@setOnClickListener
            }

            val email = etEmail.text
                .toString()
                .trim()
                .lowercase()

            sendFirebaseResetLink(email) { ok, err ->
                activity?.runOnUiThread {
                    if (ok) {
                        showAfterEmailDialogs(email)
                    } else {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Ошибка")
                            .setMessage("Не удалось отправить письмо:\n$err")
                            .setPositiveButton("Ок", null)
                            .show()
                    }
                }
            }
        }

        return dialog
    }

    // Очистка таймера
    override fun onDestroyView() {
        timer?.cancel()
        super.onDestroyView()
    }
}
