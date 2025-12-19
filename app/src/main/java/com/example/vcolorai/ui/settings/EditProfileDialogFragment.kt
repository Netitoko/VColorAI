package com.example.vcolorai.ui.settings

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.example.vcolorai.data.net.ImgBBUploader
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditProfileDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_USERNAME = "arg_username"
        private const val ARG_AVATAR_URL = "arg_avatar_url"

        fun newInstance(
            username: String,
            avatarUrl: String?
        ): EditProfileDialogFragment {
            return EditProfileDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USERNAME, username)
                    putString(ARG_AVATAR_URL, avatarUrl)
                }
            }
        }
    }

    private var pickedAvatarUri: Uri? = null

    // ---------- Image picker ----------

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pickedAvatarUri = uri
            dialog?.findViewById<ImageView>(R.id.ivAvatarPreview)?.let { iv ->
                Glide.with(this)
                    .load(uri)
                    .circleCrop()
                    .into(iv)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view =
            requireActivity().layoutInflater.inflate(
                R.layout.dialog_edit_profile,
                null
            )

        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatarPreview)
        val etUsername: EditText = view.findViewById(R.id.etUsername)
        val btnPick: Button = view.findViewById(R.id.btnPickAvatar)

        val initialUsername =
            arguments?.getString(ARG_USERNAME).orEmpty()
        val initialAvatarUrl =
            arguments?.getString(ARG_AVATAR_URL)

        etUsername.setText(initialUsername)

        if (!initialAvatarUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(initialAvatarUrl)
                .circleCrop()
                .into(ivAvatar)
        } else {
            ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }

        btnPick.setOnClickListener {
            pickImage.launch("image/*")
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Изменить профиль")
            .setView(view)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
            .also { dlg ->
                dlg.setOnShowListener {
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            val newUsername =
                                etUsername.text.toString().trim()
                            saveProfile(
                                newUsername,
                                initialUsername,
                                initialAvatarUrl
                            )
                        }
                }
            }
    }

    // ---------- Username helpers ----------

    private fun usernameKey(username: String): String =
        username.trim().lowercase()

    private fun isUsernameValid(username: String): Boolean {
        if (username.isBlank()) return false
        if (username.any { it.isWhitespace() }) return false
        return true
    }

    // ---------- Save profile ----------

    private fun saveProfile(
        newUsername: String,
        initialUsername: String,
        initialAvatarUrl: String?
    ) {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(
                requireContext(),
                "Нет авторизации",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!isUsernameValid(newUsername)) {
            Toast.makeText(
                requireContext(),
                "Ник не должен содержать пробелы",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val db = FirebaseFirestore.getInstance()
        val uid = user.uid
        val newAvatar = pickedAvatarUri

        val oldKey = usernameKey(initialUsername)
        val newKey = usernameKey(newUsername)

        // ---------- 1) Аватар не меняли ----------

        if (newAvatar == null) {

            // Ник не менялся
            if (oldKey == newKey) {
                val updates = mapOf("username" to newUsername)

                db.collection("users").document(uid)
                    .set(updates, SetOptions.merge())
                    .addOnSuccessListener {
                        db.collection("public_users").document(uid)
                            .set(updates, SetOptions.merge())
                            .addOnSuccessListener {
                                sendResult(
                                    newUsername,
                                    initialAvatarUrl
                                )
                                dismiss()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(
                                    requireContext(),
                                    "Ошибка public профиля: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            requireContext(),
                            "Ошибка: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                return
            }

            // Ник меняется
            runUsernameChangeTransaction(
                db = db,
                uid = uid,
                oldKey = oldKey,
                newKey = newKey,
                newUsername = newUsername,
                avatarUrl = null,
                resultAvatarUrl = initialAvatarUrl
            )
            return
        }

        // ---------- 2) Аватар меняли ----------

        lifecycleScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    ImgBBUploader.uploadImage(
                        requireContext(),
                        newAvatar
                    )
                }

                if (url.isNullOrBlank()) {
                    Toast.makeText(
                        requireContext(),
                        "Не удалось загрузить аватар",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                // Ник не менялся
                if (oldKey == newKey) {
                    val updates = mapOf(
                        "username" to newUsername,
                        "avatarUrl" to url
                    )

                    db.collection("users").document(uid)
                        .set(updates, SetOptions.merge())
                        .addOnSuccessListener {
                            db.collection("public_users").document(uid)
                                .set(updates, SetOptions.merge())
                                .addOnSuccessListener {
                                    sendResult(newUsername, url)
                                    dismiss()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(
                                        requireContext(),
                                        "Ошибка public профиля: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                requireContext(),
                                "Ошибка: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                    return@launch
                }

                // Ник меняется
                runUsernameChangeTransaction(
                    db = db,
                    uid = uid,
                    oldKey = oldKey,
                    newKey = newKey,
                    newUsername = newUsername,
                    avatarUrl = url,
                    resultAvatarUrl = url
                )

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Ошибка загрузки: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- Username transaction ----------

    private fun runUsernameChangeTransaction(
        db: FirebaseFirestore,
        uid: String,
        oldKey: String,
        newKey: String,
        newUsername: String,
        avatarUrl: String?,
        resultAvatarUrl: String?
    ) {
        val newUsernameRef =
            db.collection("usernames").document(newKey)
        val oldUsernameRef =
            db.collection("usernames").document(oldKey)

        val userRef =
            db.collection("users").document(uid)
        val publicRef =
            db.collection("public_users").document(uid)

        val now = System.currentTimeMillis()

        db.runTransaction { tx ->

            val newSnap = tx.get(newUsernameRef)
            val oldSnap = tx.get(oldUsernameRef)

            if (newSnap.exists()) {
                throw IllegalStateException("Ник уже занят")
            }

            tx.set(
                newUsernameRef,
                mapOf("uid" to uid, "createdAt" to now)
            )

            if (oldSnap.exists() &&
                oldSnap.getString("uid") == uid
            ) {
                tx.delete(oldUsernameRef)
            }

            val updates = hashMapOf<String, Any>(
                "username" to newUsername
            )
            if (!avatarUrl.isNullOrBlank()) {
                updates["avatarUrl"] = avatarUrl
            }

            tx.set(userRef, updates, SetOptions.merge())
            tx.set(publicRef, updates, SetOptions.merge())

            true
        }.addOnSuccessListener {
            sendResult(newUsername, resultAvatarUrl)
            dismiss()
        }.addOnFailureListener { e ->
            val msg = when {
                e.message?.contains(
                    "занят",
                    ignoreCase = true
                ) == true ->
                    "Ник уже занят. Выберите другой."
                else ->
                    e.message ?: "Не удалось сохранить изменения"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Result ----------

    private fun sendResult(
        username: String,
        avatarUrl: String?
    ) {
        parentFragmentManager.setFragmentResult(
            "profile_updated",
            Bundle().apply {
                putString("username", username)
                putString("avatarUrl", avatarUrl)
            }
        )
    }
}
