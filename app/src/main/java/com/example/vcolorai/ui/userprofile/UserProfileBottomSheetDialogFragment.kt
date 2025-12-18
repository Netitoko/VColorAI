package com.example.vcolorai.ui.userprofile

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.example.vcolorai.databinding.BottomSheetUserProfileBinding
import com.example.vcolorai.ui.feed.PublicFeedAdapter
import com.example.vcolorai.ui.feed.PublicFeedItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class UserProfileBottomSheetDialogFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_USER_ID = "arg_user_id"

        fun newInstance(userId: String): UserProfileBottomSheetDialogFragment {
            return UserProfileBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USER_ID, userId)
                }
            }
        }
    }

    private var _binding: BottomSheetUserProfileBinding? = null
    private val binding get() = _binding!!

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var adapter: PublicFeedAdapter
    private lateinit var userId: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetUserProfileBinding.inflate(inflater, container, false)

        userId = arguments?.getString(ARG_USER_ID) ?: run {
            dismiss()
            return binding.root
        }

        setupRecycler()
        loadUserHeader()
        loadUserPalettes()
        setupSubscribeButton()

        return binding.root
    }

    // ---------------- Header ----------------

    private fun loadUserHeader() {
        db.collection("public_users")
            .document(userId)
            .get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username") ?: "User"
                val avatarUrl = doc.getString("avatarUrl")

                binding.tvUsername.text = username

                if (!avatarUrl.isNullOrBlank()) {
                    Glide.with(this)
                        .load(avatarUrl)
                        .circleCrop()
                        .into(binding.ivAvatar)
                } else {
                    binding.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
                }
            }
    }

    // ---------------- Subscribe ----------------

    private fun setupSubscribeButton() {
        val currentUid = auth.currentUser?.uid ?: return

        if (currentUid == userId) {
            binding.btnSubscribe.visibility = View.GONE
            return
        }

        val followRef = db.collection("users")
            .document(userId)
            .collection("followers")
            .document(currentUid)

        followRef.get().addOnSuccessListener { doc ->
            updateSubscribeButton(doc.exists())
        }

        binding.btnSubscribe.setOnClickListener {
            followRef.get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    unfollow(followRef)
                } else {
                    follow(followRef)
                }
            }
        }
    }

    private fun follow(ref: com.google.firebase.firestore.DocumentReference) {
        val uid = auth.currentUser?.uid ?: return
        ref.set(mapOf("userId" to uid))
            .addOnSuccessListener {
                updateSubscribeButton(true)
            }
    }

    private fun unfollow(ref: com.google.firebase.firestore.DocumentReference) {
        ref.delete()
            .addOnSuccessListener {
                updateSubscribeButton(false)
            }
    }

    private fun updateSubscribeButton(isFollowing: Boolean) {
        binding.btnSubscribe.text =
            if (isFollowing) "Отписаться" else "Подписаться"
    }

    // ---------------- Palettes ----------------

    private fun setupRecycler() {
        adapter = PublicFeedAdapter(
            items = emptyList(),
            onSaveToMyPalettes = {},
            onSharePalette = {},
            onLike = {},
            onDislike = {},
            onAuthorClick = {} // уже внутри профиля
        )

        binding.rvUserPalettes.adapter = adapter
    }

    private fun loadUserPalettes() {
        db.collection("color_palettes")
            .whereEqualTo("userId", userId)
            .whereEqualTo("isPublic", true)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    val colors =
                        (doc.get("colors") as? List<*>)?.map { it.toString() }
                            ?: return@mapNotNull null

                    val tags =
                        (doc.get("tags") as? List<*>)?.map { it.toString() }
                            ?: emptyList()

                    PublicFeedItem(
                        id = doc.id,
                        paletteName = doc.getString("paletteName") ?: "",
                        colors = colors,
                        authorId = userId,
                        authorName = null,
                        description = doc.getString("promptText"),
                        tags = tags,
                        likesCount = doc.getLong("likesCount") ?: 0L,
                        dislikesCount = doc.getLong("dislikesCount") ?: 0L,
                        imageUri = doc.getString("imageUri"),
                        currentUserVote = 0,
                        createdAt = doc.getLong("creationDate") ?: 0L
                    )
                }

                adapter.submitList(list)
                binding.tvEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener {
                Log.e("USER_PROFILE", "Load palettes error", it)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
