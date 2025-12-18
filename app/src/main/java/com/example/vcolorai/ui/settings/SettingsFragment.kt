package com.example.vcolorai.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.example.vcolorai.EmailSender
import com.example.vcolorai.LoginActivity
import com.example.vcolorai.R
import com.example.vcolorai.databinding.FragmentSettingsBinding
import com.example.vcolorai.ui.auth.ForgotPasswordDialogFragment
import com.example.vcolorai.ui.common.BaseFragment
import com.example.vcolorai.ui.profile.EditProfileDialogFragment
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class SettingsFragment : BaseFragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // --- смена почты ---
    private var emailChangeCode: String? = null
    private var emailTimer: CountDownTimer? = null
    private var isEmailTimerRunning = false

    // --- смена пароля ---
    private var passwordChangeCode: String? = null
    private var passwordTimer: CountDownTimer? = null
    private var isPasswordTimerRunning = false

    // --- prefs для темы ---
    private val prefs by lazy {
        requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupListeners()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseSettings.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
    }

    override fun applyInsets(root: View) {
        val initialRootTop = binding.settingsRoot.paddingTop
        val initialCloseTop = binding.btnCloseSettings.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())

            binding.settingsRoot.updatePadding(
                top = initialRootTop + statusBar.top
            )

            binding.btnCloseSettings.updatePadding(
                top = initialCloseTop + statusBar.top
            )

            insets
        }
    }

    private fun setupListeners() {

        // ✅ Изменить профиль
        binding.btnEditProfile.setOnClickListener {
            val user = auth.currentUser
            if (user == null) {
                Toast.makeText(requireContext(), "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                return@setOnClickListener
            }

            db.collection("users").document(user.uid)
                .get()
                .addOnSuccessListener { doc ->
                    val username = doc.getString("username") ?: ""
                    val avatarUrl = doc.getString("avatarUrl")

                    EditProfileDialogFragment
                        .newInstance(username = username, avatarUrl = avatarUrl)
                        .show(parentFragmentManager, "edit_profile")
                }
                .addOnFailureListener {
                    EditProfileDialogFragment
                        .newInstance(username = "", avatarUrl = null)
                        .show(parentFragmentManager, "edit_profile")
                }
        }

        // ✅ Сменить email
        binding.btnChangeEmail.setOnClickListener {
            val user = auth.currentUser
            if (user == null) {
                Toast.makeText(requireContext(), "Войдите в аккаунт, чтобы сменить email", Toast.LENGTH_SHORT).show()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                return@setOnClickListener
            }
            showChangeEmailDialogLikeRegister()
        }

        // ✅ Сменить пароль
        binding.btnChangePassword.setOnClickListener {
            val user = auth.currentUser
            if (user == null) {
                ForgotPasswordDialogFragment().show(parentFragmentManager, "forgot_password")
                return@setOnClickListener
            }
            showChangePasswordDialogLikeRegister()
        }

        // ✅ Моя активность
        binding.btnMyStats.setOnClickListener {
            UserStatsDialogFragment().show(parentFragmentManager, "user_stats")
        }

        // ✅ Экспорт PDF (только 7 дней)
        binding.btnExportPdf.setOnClickListener {
            exportStatsToPdf()
        }

        // ✅ Правила публикаций (отдельный экран)
        binding.btnRules.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, RulesFragment())
                .addToBackStack("rules")
                .commit()
        }

        // ✅ Тема приложения
        binding.btnTheme.setOnClickListener {
            showThemeDialog()
        }

        // Выйти
        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }

        // Удалить аккаунт
        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }
    }

    // -------------------------------------------------------------------------
    // THEME
    // -------------------------------------------------------------------------

    private fun showThemeDialog() {
        val items = arrayOf("Светлая", "Тёмная", "Как на устройстве")

        val currentMode = prefs.getInt(
            "app_theme",
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )

        val checked = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 0
            AppCompatDelegate.MODE_NIGHT_YES -> 1
            else -> 2
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Тема приложения")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val newMode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }

                prefs.edit().putInt("app_theme", newMode).apply()
                AppCompatDelegate.setDefaultNightMode(newMode)

                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // EMAIL CHANGE
    // -------------------------------------------------------------------------

    private fun showChangeEmailDialogLikeRegister() {
        val user = auth.currentUser ?: return

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_change_email, null)
        val etNewEmail = view.findViewById<EditText>(R.id.etNewEmail)
        val btnSend = view.findViewById<Button>(R.id.btnSendEmailCode)
        val etCode = view.findViewById<EditText>(R.id.etEmailCode)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmEmailChange)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Сменить email")
            .setView(view)
            .setNegativeButton("Отмена", null)
            .create()

        btnSend.setOnClickListener {
            if (isEmailTimerRunning) {
                Toast.makeText(requireContext(), "Подождите перед повторной отправкой", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newEmail = etNewEmail.text.toString().trim().lowercase()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                Toast.makeText(requireContext(), "Введите корректный email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            emailChangeCode = (100000..999999).random().toString()
            val subject = "VColorAI — подтверждение смены почты"
            val message = "Ваш код подтверждения: $emailChangeCode"

            Toast.makeText(requireContext(), "Отправка кода...", Toast.LENGTH_SHORT).show()

            EmailSender.sendEmail(newEmail, subject, message) { success, error ->
                requireActivity().runOnUiThread {
                    if (success) {
                        Toast.makeText(requireContext(), "Код отправлен на $newEmail", Toast.LENGTH_LONG).show()
                        etCode.visibility = View.VISIBLE
                        btnConfirm.visibility = View.VISIBLE
                        startEmailCountdown(btnSend)
                    } else {
                        Toast.makeText(requireContext(), "Ошибка отправки: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnConfirm.setOnClickListener {
            val newEmail = etNewEmail.text.toString().trim().lowercase()
            val code = etCode.text.toString().trim()

            if (emailChangeCode.isNullOrBlank() || code != emailChangeCode) {
                Toast.makeText(requireContext(), "Неверный код", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            updateEmailWithReauthIfNeeded(newEmail) {
                db.collection("users").document(user.uid)
                    .set(mapOf("email" to newEmail), SetOptions.merge())
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun updateEmailWithReauthIfNeeded(newEmail: String, onDone: () -> Unit) {
        val user = auth.currentUser ?: return

        fun doUpdate() {
            user.updateEmail(newEmail)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Email обновлён", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                .addOnFailureListener { e ->
                    if (isRecentLoginRequired(e)) {
                        showReauthDialog(
                            title = "Подтверждение",
                            message = "Для смены email введите текущий пароль",
                            onOk = { doUpdate() }
                        )
                    } else {
                        Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        doUpdate()
    }

    private fun startEmailCountdown(btn: Button) {
        emailTimer?.cancel()
        isEmailTimerRunning = true
        btn.isEnabled = false

        emailTimer = object : CountDownTimer(60_000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                btn.text = "Повторно через ${millisUntilFinished / 1000} c"
            }

            override fun onFinish() {
                btn.text = "Отправить код снова"
                btn.isEnabled = true
                isEmailTimerRunning = false
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // PASSWORD CHANGE
    // -------------------------------------------------------------------------

    private fun showChangePasswordDialogLikeRegister() {
        val user = auth.currentUser ?: return
        val email = user.email ?: run {
            Toast.makeText(requireContext(), "У аккаунта нет email", Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_change_password, null)

        val etCurrent = view.findViewById<EditText>(R.id.etCurrentPassword)
        val tvForgot = view.findViewById<TextView>(R.id.tvForgotPassword)

        val etNewPass = view.findViewById<EditText>(R.id.etNewPassword)
        val etConfirm = view.findViewById<EditText>(R.id.etConfirmPassword)

        val tvReqLength = view.findViewById<TextView>(R.id.tvReqLength)
        val tvReqUppercase = view.findViewById<TextView>(R.id.tvReqUppercase)
        val tvReqNumber = view.findViewById<TextView>(R.id.tvReqNumber)
        val tvReqSpecial = view.findViewById<TextView>(R.id.tvReqSpecial)

        val btnSend = view.findViewById<Button>(R.id.btnSendPasswordCode)
        val etCode = view.findViewById<EditText>(R.id.etPasswordCode)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmPasswordChange)

        etNewPass.addTextChangedListener { txt ->
            updatePasswordRequirements(
                password = txt?.toString().orEmpty(),
                tvReqLength = tvReqLength,
                tvReqUppercase = tvReqUppercase,
                tvReqNumber = tvReqNumber,
                tvReqSpecial = tvReqSpecial
            )
        }

        tvForgot.paintFlags = tvForgot.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvForgot.setOnClickListener {
            ForgotPasswordDialogFragment().show(parentFragmentManager, "forgot_password")
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Сменить пароль")
            .setView(view)
            .setNegativeButton("Отмена", null)
            .create()

        btnSend.setOnClickListener {
            if (isPasswordTimerRunning) {
                Toast.makeText(requireContext(), "Подождите перед повторной отправкой", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentPassword = etCurrent.text.toString()
            if (currentPassword.isBlank()) {
                Toast.makeText(requireContext(), "Введите текущий пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val credential = EmailAuthProvider.getCredential(email, currentPassword)
            user.reauthenticate(credential)
                .addOnFailureListener {
                    tvForgot.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), "Неверный текущий пароль", Toast.LENGTH_SHORT).show()
                }
                .addOnSuccessListener {
                    tvForgot.visibility = View.GONE

                    val newPass = etNewPass.text.toString().trim()
                    val confirm = etConfirm.text.toString().trim()

                    if (!isPasswordStrongLikeRegister(newPass)) {
                        Toast.makeText(requireContext(), "Новый пароль не соответствует требованиям", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    if (newPass != confirm) {
                        Toast.makeText(requireContext(), "Пароли не совпадают", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    passwordChangeCode = (100000..999999).random().toString()
                    val subject = "VColorAI — подтверждение смены пароля"
                    val message = "Ваш код подтверждения: $passwordChangeCode"

                    Toast.makeText(requireContext(), "Отправка кода...", Toast.LENGTH_SHORT).show()

                    EmailSender.sendEmail(email, subject, message) { success, error ->
                        requireActivity().runOnUiThread {
                            if (success) {
                                Toast.makeText(requireContext(), "Код отправлен на $email", Toast.LENGTH_LONG).show()
                                etCode.visibility = View.VISIBLE
                                btnConfirm.visibility = View.VISIBLE
                                startPasswordCountdown(btnSend)
                            } else {
                                Toast.makeText(requireContext(), "Ошибка отправки: $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
        }

        btnConfirm.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (passwordChangeCode.isNullOrBlank() || code != passwordChangeCode) {
                Toast.makeText(requireContext(), "Неверный код", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newPass = etNewPass.text.toString().trim()
            if (!isPasswordStrongLikeRegister(newPass)) {
                Toast.makeText(requireContext(), "Новый пароль не соответствует требованиям", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            user.updatePassword(newPass)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Пароль изменён", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        dialog.show()
    }

    private fun startPasswordCountdown(btn: Button) {
        passwordTimer?.cancel()
        isPasswordTimerRunning = true
        btn.isEnabled = false

        passwordTimer = object : CountDownTimer(60_000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                btn.text = "Повторно через ${millisUntilFinished / 1000} c"
            }

            override fun onFinish() {
                btn.text = "Отправить код снова"
                btn.isEnabled = true
                isPasswordTimerRunning = false
            }
        }.start()
    }

    private fun isPasswordStrongLikeRegister(password: String): Boolean {
        val pattern =
            Regex("^(?=.*[A-Z])(?=.*[0-9])(?=.*[!@#\$%^&*()_+=\\-{}\\[\\]:;\"'<>,?/]).{6,}\$")
        return password.matches(pattern)
    }

    private fun updatePasswordRequirements(
        password: String,
        tvReqLength: TextView,
        tvReqUppercase: TextView,
        tvReqNumber: TextView,
        tvReqSpecial: TextView
    ) {
        val ok = 0xFF00C853.toInt()
        val gray = 0xFF888888.toInt()

        tvReqLength.setTextColor(if (password.length >= 6) ok else gray)
        tvReqUppercase.setTextColor(if (password.any { it.isUpperCase() }) ok else gray)
        tvReqNumber.setTextColor(if (password.any { it.isDigit() }) ok else gray)

        val specials = "!@#\$%^&*()_+=-{}[]:;\"'<>,.?/"
        tvReqSpecial.setTextColor(if (password.any { specials.contains(it) }) ok else gray)
    }

    // -------------------------------------------------------------------------
    // REAUTH DIALOG
    // -------------------------------------------------------------------------

    private fun showReauthDialog(title: String, message: String, onOk: () -> Unit) {
        val user = auth.currentUser ?: return
        val email = user.email ?: return

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_reauth_password, null)
        val etCurrent = view.findViewById<EditText>(R.id.etCurrentPassword)
        val tvForgot = view.findViewById<TextView>(R.id.tvForgotPasswordInDialog)

        val dlg = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setView(view)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", null)
            .create()

        tvForgot.paintFlags = tvForgot.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvForgot.setOnClickListener {
            ForgotPasswordDialogFragment().show(parentFragmentManager, "forgot_password")
        }

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val currentPassword = etCurrent.text.toString()
                if (currentPassword.isBlank()) {
                    Toast.makeText(requireContext(), "Введите текущий пароль", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val credential = EmailAuthProvider.getCredential(email, currentPassword)
                user.reauthenticate(credential)
                    .addOnSuccessListener {
                        dlg.dismiss()
                        onOk()
                    }
                    .addOnFailureListener {
                        tvForgot.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), "Неверный текущий пароль", Toast.LENGTH_SHORT).show()
                    }
            }
        }

        dlg.show()
    }

    private fun isRecentLoginRequired(e: Exception): Boolean {
        return (e is FirebaseAuthRecentLoginRequiredException) ||
                (e.message?.contains("requires recent login", ignoreCase = true) == true) ||
                (e.message?.contains("ERROR_REQUIRES_RECENT_LOGIN", ignoreCase = true) == true)
    }

    // -------------------------------------------------------------------------
    // DELETE ACCOUNT
    // -------------------------------------------------------------------------

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить аккаунт?")
            .setMessage("Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteAccount()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteAccount() {
        val user = auth.currentUser ?: run {
            Toast.makeText(requireContext(), "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            return
        }
        val uid = user.uid

        // 1) сначала удаляем данные из Firestore
        deleteUserDataEverywhere(
            uid = uid,
            onSuccess = {
                // 2) затем удаляем Auth аккаунт
                user.delete()
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Аккаунт удалён", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(requireContext(), LoginActivity::class.java))
                        requireActivity().finish()
                    }
                    .addOnFailureListener { e ->
                        if (isRecentLoginRequired(e)) {
                            showReauthDialog(
                                title = "Подтверждение",
                                message = "Для удаления аккаунта введите текущий пароль",
                                onOk = { deleteAccount() }
                            )
                        } else {
                            Toast.makeText(requireContext(), "Ошибка удаления: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            },
            onError = { e ->
                Toast.makeText(requireContext(), "Не удалось удалить данные: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun usernameKey(username: String): String = username.trim().lowercase()

    private fun deleteUserDataEverywhere(
        uid: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Сначала узнаем username (чтобы удалить usernames/{key})
        db.collection("users").document(uid).get()
            .addOnSuccessListener { userDoc ->
                val username = userDoc.getString("username")?.trim().orEmpty()
                val uKey = usernameKey(username)

                // 1) удалить подколлекции внутри users/{uid}
                deleteCollection(db.collection("users").document(uid).collection("notifications"),
                    onDone = {
                        deleteCollection(db.collection("users").document(uid).collection("liked_palettes"),
                            onDone = {
                                deleteCollection(db.collection("users").document(uid).collection("following"),
                                    onDone = {
                                        deleteCollection(db.collection("users").document(uid).collection("followers"),
                                            onDone = {

                                                // 2) удалить "следы" подписок у других пользователей
                                                // другие -> following/{uid}
                                                deleteCollectionGroupByDocId("following", uid,
                                                    onDone = {
                                                        // другие -> followers/{uid}
                                                        deleteCollectionGroupByDocId("followers", uid,
                                                            onDone = {

                                                                // 3) удалить все голоса пользователя votes/{uid} по всем палитрам
                                                                deleteCollectionGroupByDocId("votes", uid,
                                                                    onDone = {

                                                                        // 4) удалить все палитры пользователя + их votes/*
                                                                        deleteMyPalettesAndTheirVotes(uid,
                                                                            onDone = {

                                                                                // 5) удалить основные документы пользователя
                                                                                val batch = db.batch()
                                                                                batch.delete(db.collection("public_users").document(uid))
                                                                                batch.delete(db.collection("user_profiles").document(uid))
                                                                                batch.delete(db.collection("users").document(uid))

                                                                                if (uKey.isNotBlank()) {
                                                                                    batch.delete(db.collection("usernames").document(uKey))
                                                                                }

                                                                                batch.commit()
                                                                                    .addOnSuccessListener { onSuccess() }
                                                                                    .addOnFailureListener { e -> onError(e) }
                                                                            },
                                                                            onError = onError
                                                                        )
                                                                    },
                                                                    onError = onError
                                                                )
                                                            },
                                                            onError = onError
                                                        )
                                                    },
                                                    onError = onError
                                                )
                                            },
                                            onError = onError
                                        )
                                    },
                                    onError = onError
                                )
                            },
                            onError = onError
                        )
                    },
                    onError = onError
                )
            }
            .addOnFailureListener { e -> onError(e) }
    }

    private fun deleteCollection(
        col: CollectionReference,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        col.get()
            .addOnSuccessListener { snap ->
                val refs = snap.documents.map { it.reference }
                if (refs.isEmpty()) {
                    onDone(); return@addOnSuccessListener
                }
                deleteDocsInChunks(refs, onDone, onError)
            }
            .addOnFailureListener { e -> onError(e) }
    }

    private fun deleteCollectionGroupByDocId(
        collectionName: String,
        docId: String,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val idPath = FieldPath.documentId()

        db.collectionGroup(collectionName)
            .whereEqualTo(idPath, docId)
            .get()
            .addOnSuccessListener { snap ->
                val refs = snap.documents.map { it.reference }
                if (refs.isEmpty()) {
                    onDone(); return@addOnSuccessListener
                }
                deleteDocsInChunks(refs, onDone, onError)
            }
            .addOnFailureListener { e -> onError(e) }
    }

    private fun deleteMyPalettesAndTheirVotes(
        uid: String,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        db.collection("color_palettes")
            .whereEqualTo("userId", uid)
            .get()
            .addOnSuccessListener { snap ->
                val palettes = snap.documents
                if (palettes.isEmpty()) {
                    onDone(); return@addOnSuccessListener
                }

                fun deleteNext(index: Int) {
                    if (index >= palettes.size) {
                        onDone(); return
                    }

                    val paletteRef = palettes[index].reference

                    // сначала удаляем votes под палитрой, потом саму палитру
                    paletteRef.collection("votes").get()
                        .addOnSuccessListener { votesSnap ->
                            val voteRefs = votesSnap.documents.map { it.reference }
                            deleteDocsInChunks(voteRefs,
                                onDone = {
                                    paletteRef.delete()
                                        .addOnSuccessListener { deleteNext(index + 1) }
                                        .addOnFailureListener { e -> onError(e) }
                                },
                                onError = onError
                            )
                        }
                        .addOnFailureListener { e -> onError(e) }
                }

                deleteNext(0)
            }
            .addOnFailureListener { e -> onError(e) }
    }

    private fun deleteDocsInChunks(
        refs: List<DocumentReference>,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (refs.isEmpty()) {
            onDone(); return
        }

        val chunkSize = 450 // безопасно < 500
        val chunks = refs.chunked(chunkSize)

        fun commitChunk(i: Int) {
            if (i >= chunks.size) {
                onDone(); return
            }

            val batch = db.batch()
            chunks[i].forEach { batch.delete(it) }

            batch.commit()
                .addOnSuccessListener { commitChunk(i + 1) }
                .addOnFailureListener { e -> onError(e) }
        }

        commitChunk(0)
    }

    // -------------------------------------------------------------------------
    // PDF EXPORT (ТОЛЬКО 7 ДНЕЙ)
    // -------------------------------------------------------------------------

    private data class PaletteDoc(
        val isPublic: Boolean,
        val likes: Int,
        val dislikes: Int,
        val createdAtMillis: Long
    )

    private data class StatsForPdf(
        val total: Int,
        val publicCount: Int,
        val privateCount: Int,
        val likesTotal: Int,
        val dislikesTotal: Int,
        val counts7: IntArray
    )

    private fun exportStatsToPdf() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Собираю статистику...", Toast.LENGTH_SHORT).show()

        db.collection("color_palettes")
            .whereEqualTo("userId", user.uid)
            .get()
            .addOnSuccessListener { snap ->
                val docs = snap.documents.map { doc ->
                    val isPublic = doc.getBoolean("isPublic") ?: false
                    val likes = (doc.getLong("likesCount") ?: 0L).toInt()
                    val dislikes = (doc.getLong("dislikesCount") ?: 0L).toInt()
                    val createdAt = (doc.getLong("creationDate") ?: 0L)
                    PaletteDoc(isPublic, likes, dislikes, createdAt)
                }

                val total = docs.size
                val publicCount = docs.count { it.isPublic }
                val privateCount = total - publicCount
                val likesTotal = docs.sumOf { it.likes }
                val dislikesTotal = docs.sumOf { it.dislikes }
                val counts7 = buildDailyCounts(docs, 7)

                val stats = StatsForPdf(
                    total = total,
                    publicCount = publicCount,
                    privateCount = privateCount,
                    likesTotal = likesTotal,
                    dislikesTotal = dislikesTotal,
                    counts7 = counts7
                )

                val file = createStatsPdf(stats)
                sharePdf(file)
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка Firestore: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun createStatsPdf(stats: StatsForPdf): File {
        val doc = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842

        val paintTitle = Paint().apply {
            isAntiAlias = true
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val paintText = Paint().apply {
            isAntiAlias = true
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val paintBold = Paint().apply {
            isAntiAlias = true
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        fun line(y: Float): Float = y + 18f
        fun gap(y: Float): Float = y + 26f

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        var y = 56f
        canvas.drawText("VColorAI — Статистика пользователя", 40f, y, paintTitle)

        y = line(y)
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        canvas.drawText("Дата формирования: ${sdf.format(Date())}", 40f, y, paintText)

        y = gap(y)
        canvas.drawText("Всего палитр:", 40f, y, paintText)
        canvas.drawText(stats.total.toString(), 220f, y, paintBold)

        y = line(y)
        canvas.drawText("Публичные:", 40f, y, paintText)
        canvas.drawText(stats.publicCount.toString(), 220f, y, paintBold)

        y = line(y)
        canvas.drawText("Приватные:", 40f, y, paintText)
        canvas.drawText(stats.privateCount.toString(), 220f, y, paintBold)

        y = line(y)
        canvas.drawText("Лайки:", 40f, y, paintText)
        canvas.drawText(stats.likesTotal.toString(), 220f, y, paintBold)

        y = line(y)
        canvas.drawText("Дизлайки:", 40f, y, paintText)
        canvas.drawText(stats.dislikesTotal.toString(), 220f, y, paintBold)

        val created7 = stats.counts7.sum()
        y = line(y)
        canvas.drawText("Создано за 7 дней:", 40f, y, paintText)
        canvas.drawText(created7.toString(), 220f, y, paintBold)

        // bars
        y = gap(y)
        canvas.drawText("Лайки vs дизлайки", 40f, y, paintBold)

        val barLeft = 40f
        val barRight = pageWidth - 40f
        val barMaxW = barRight - barLeft
        val barH = 16f
        val maxLD = max(1, max(stats.likesTotal, stats.dislikesTotal))

        fun drawHBar(label: String, value: Int, top: Float, color: Int): Float {
            val pFill = Paint().apply { style = Paint.Style.FILL; this.color = color }
            val pStroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }

            canvas.drawText(label, barLeft, top - 6f, paintText)
            canvas.drawRect(barLeft, top, barRight, top + barH, pStroke)

            val w = (value.toFloat() / maxLD) * barMaxW
            canvas.drawRect(barLeft, top, barLeft + w, top + barH, pFill)

            val valueText = value.toString()
            val tw = paintBold.measureText(valueText)
            canvas.drawText(valueText, barRight - tw, top + barH - 3f, paintBold)

            return top + barH + 20f
        }

        y += 18f
        y = drawHBar("Лайки", stats.likesTotal, y, 0xFF81C784.toInt())
        y = drawHBar("Дизлайки", stats.dislikesTotal, y, 0xFFE57373.toInt())

        // histogram 7
        y = gap(y)
        canvas.drawText("Палитры по дням — 7 дней", 40f, y, paintBold)
        y += 12f

        drawDailyHistogram(
            canvas = canvas,
            left = 40f,
            top = y,
            width = pageWidth - 80f,
            height = 220f,
            counts = stats.counts7
        )

        doc.finishPage(page)

        val file = File(requireContext().cacheDir, "vcolorai_stats_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { out -> doc.writeTo(out) }
        doc.close()

        Toast.makeText(requireContext(), "PDF готов: ${file.name}", Toast.LENGTH_SHORT).show()
        return file
    }

    private fun drawDailyHistogram(
        canvas: android.graphics.Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        counts: IntArray
    ) {
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        val fill = Paint().apply { style = Paint.Style.FILL; color = 0xFF90CAF9.toInt() }
        val text = Paint().apply { isAntiAlias = true; textSize = 9.5f }
        val bold = Paint().apply { isAntiAlias = true; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }

        val bottom = top + height
        canvas.drawRect(left, top, left + width, bottom, border)

        val n = counts.size
        val maxV = max(1, counts.maxOrNull() ?: 1)
        val barW = width / n

        val labelArea = 26f
        val topPad = 10f
        val barMaxH = height - labelArea - topPad

        val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
        val startToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun labelForIndex(i: Int): String {
            val diff = (n - 1) - i
            val c = Calendar.getInstance()
            c.timeInMillis = startToday
            c.add(Calendar.DAY_OF_YEAR, -diff)
            return sdf.format(c.time)
        }

        val safeTopTextY = top + 12f
        val safeBottomTextY = bottom - labelArea - 4f

        for (i in 0 until n) {
            val v = counts[i]

            val xStart = left + i * barW
            val xCenter = xStart + barW / 2f

            val bw = barW * 0.72f
            val bx0 = xCenter - bw / 2f
            val bx1 = xCenter + bw / 2f

            val h = (v.toFloat() / maxV) * barMaxH
            val yBarBottom = bottom - labelArea
            val yBarTop = yBarBottom - h

            canvas.drawRect(bx0, yBarTop, bx1, yBarBottom, fill)

            if (v > 0) {
                val vs = v.toString()
                val tw = bold.measureText(vs)

                val insideY = yBarTop + 12f
                val aboveY = yBarTop - 4f
                val textY = if (h >= 18f) insideY else aboveY
                val clampedY = textY.coerceIn(safeTopTextY, safeBottomTextY)

                canvas.drawText(vs, xCenter - tw / 2f, clampedY, bold)
            }

            val lab = labelForIndex(i)
            val twLab = text.measureText(lab)
            canvas.drawText(lab, xCenter - twLab / 2f, bottom - 6f, text)
        }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Поделиться PDF"))
    }

    private fun buildDailyCounts(docs: List<PaletteDoc>, days: Int): IntArray {
        val counts = IntArray(days)

        val cal = Calendar.getInstance()
        setToStartOfDay(cal)
        val startToday = cal.timeInMillis

        for (doc in docs) {
            val t = doc.createdAtMillis
            if (t <= 0L) continue
            val diffDays = ((startToday - startOfDayMillis(t)) / DAY_MS).toInt()
            if (diffDays in 0 until days) {
                val index = (days - 1) - diffDays
                counts[index] = counts[index] + 1
            }
        }
        return counts
    }

    private fun startOfDayMillis(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        setToStartOfDay(cal)
        return cal.timeInMillis
    }

    private fun setToStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    override fun onDestroyView() {
        emailTimer?.cancel()
        passwordTimer?.cancel()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val TAG = "SETTINGS_DELETE"
    }
}
