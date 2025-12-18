package com.example.vcolorai.ui.profile

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class PublicUserProfileBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_USER_ID = "arg_user_id"

        fun newInstance(userId: String): PublicUserProfileBottomSheet {
            return PublicUserProfileBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_USER_ID, userId) }
            }
        }
    }

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private val userId: String by lazy { arguments?.getString(ARG_USER_ID).orEmpty() }

    private var profileListener: ListenerRegistration? = null
    private var followStateListener: ListenerRegistration? = null

    private var ivAvatar: ImageView? = null
    private var tvUsername: TextView? = null
    private var btnSubscribe: Button? = null
    private var btnGoToProfile: Button? = null
    private var btnClose: ImageButton? = null

    private var isSubscribed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_public_user_profile, container, false)

        ivAvatar = view.findViewById(R.id.ivAvatar)
        tvUsername = view.findViewById(R.id.tvUsername)
        btnSubscribe = view.findViewById(R.id.btnSubscribe)
        btnGoToProfile = view.findViewById(R.id.btnGoToProfile)
        btnClose = view.findViewById(R.id.btnClose)

        tvUsername?.text = "User"
        ivAvatar?.setImageResource(R.mipmap.ic_launcher_round)

        btnClose?.setOnClickListener { dismiss() }

        btnGoToProfile?.setOnClickListener {
            if (userId.isBlank()) return@setOnClickListener
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, PublicUserProfileFragment.newInstance(userId))
                .addToBackStack("public_user_profile")
                .commit()
            dismiss()
        }

        btnSubscribe?.setOnClickListener { toggleSubscribe() }

        bindPublicUser()
        bindSubscribeStateRealtime()

        return view
    }

    private fun bindPublicUser() {
        if (userId.isBlank()) return

        profileListener?.remove()
        profileListener = db.collection("public_users")
            .document(userId)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    Log.e("PUB_SHEET", "public_users load error", e)
                    tvUsername?.text = "User"
                    ivAvatar?.setImageResource(R.mipmap.ic_launcher_round)
                    return@addSnapshotListener
                }

                val username = snap.getString("username")?.trim().orEmpty().ifBlank { "User" }
                val avatarUrl = snap.getString("avatarUrl")?.trim().orEmpty()

                tvUsername?.text = username

                val iv = ivAvatar ?: return@addSnapshotListener
                if (avatarUrl.isNotBlank() && avatarUrl != "default_gray") {
                    Glide.with(this)
                        .load(avatarUrl)
                        .placeholder(R.mipmap.ic_launcher_round)
                        .error(R.mipmap.ic_launcher_round)
                        .circleCrop()
                        .into(iv)
                } else {
                    iv.setImageResource(R.mipmap.ic_launcher_round)
                }
            }
    }

    // ✅ SOURCE OF TRUTH = users/{me}/following/{userId}
    private fun bindSubscribeStateRealtime() {
        val me = auth.currentUser?.uid
        if (me.isNullOrBlank()) {
            isSubscribed = false
            renderSubscribeButton()
            return
        }

        if (userId.isBlank() || userId == me) {
            btnSubscribe?.visibility = View.GONE
            return
        }

        followStateListener?.remove()
        followStateListener = db.collection("users")
            .document(me)
            .collection("following")
            .document(userId)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.e("PUB_SHEET", "follow state error", e)
                    return@addSnapshotListener
                }
                isSubscribed = snap?.exists() == true
                renderSubscribeButton()
            }
    }

    private fun renderSubscribeButton() {
        val me = auth.currentUser?.uid

        if (me.isNullOrBlank()) {
            btnSubscribe?.visibility = View.VISIBLE
            btnSubscribe?.text = "Войти, чтобы подписаться"
            btnSubscribe?.isEnabled = true
            btnSubscribe?.alpha = 1f
            btnSubscribe?.setOnClickListener {
                Toast.makeText(requireContext(), "Войдите в аккаунт, чтобы подписаться", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (userId.isBlank() || userId == me) {
            btnSubscribe?.visibility = View.GONE
            return
        }

        btnSubscribe?.visibility = View.VISIBLE
        btnSubscribe?.isEnabled = true
        btnSubscribe?.alpha = 1f
        btnSubscribe?.text = if (isSubscribed) "Отписаться" else "Подписаться"
        btnSubscribe?.setOnClickListener { toggleSubscribe() }
    }

    private fun toggleSubscribe() {
        val me = auth.currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Нет авторизации", Toast.LENGTH_SHORT).show()
            return
        }
        if (userId.isBlank() || userId == me) return

        val now = System.currentTimeMillis()

        val followingRef = db.collection("users")
            .document(me)
            .collection("following")
            .document(userId)

        val followerRef = db.collection("users")
            .document(userId)
            .collection("followers")
            .document(me)

        val batch = db.batch()
        if (isSubscribed) {
            batch.delete(followingRef)
            batch.delete(followerRef)
        } else {
            batch.set(followingRef, mapOf("createdAt" to now))
            batch.set(followerRef, mapOf("createdAt" to now))
        }

        btnSubscribe?.isEnabled = false
        btnSubscribe?.alpha = 0.6f

        batch.commit()
            .addOnSuccessListener {
                // состояние обновит listener
                btnSubscribe?.isEnabled = true
                btnSubscribe?.alpha = 1f
            }
            .addOnFailureListener { e ->
                Log.e("PUB_SHEET", "toggle subscribe error", e)
                btnSubscribe?.isEnabled = true
                btnSubscribe?.alpha = 1f
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        profileListener?.remove()
        followStateListener?.remove()
        profileListener = null
        followStateListener = null

        ivAvatar = null
        tvUsername = null
        btnSubscribe = null
        btnGoToProfile = null
        btnClose = null
    }
}
