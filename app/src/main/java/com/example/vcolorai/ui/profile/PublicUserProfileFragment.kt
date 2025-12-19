package com.example.vcolorai.ui.profile

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.example.vcolorai.databinding.FragmentPublicUserProfileBinding
import com.example.vcolorai.ui.feed.PublicFeedAdapter
import com.example.vcolorai.ui.feed.PublicFeedItem
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class PublicUserProfileFragment : Fragment() {

    companion object {
        private const val ARG_USER_ID = "arg_user_id"

        fun newInstance(userId: String): PublicUserProfileFragment {
            return PublicUserProfileFragment().apply {
                arguments = Bundle().apply { putString(ARG_USER_ID, userId) }
            }
        }
    }

    private var _binding: FragmentPublicUserProfileBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // Просматриваемый пользователь
    private val viewedUserId: String by lazy {
        arguments?.getString(ARG_USER_ID).orEmpty()
    }

    private lateinit var adapter: PublicFeedAdapter
    private var palettes: List<PublicFeedItem> = emptyList()

    private var isFollowing = false

    // Firestore listeners
    private var profileListener: ListenerRegistration? = null
    private var palettesListener: ListenerRegistration? = null
    private var followStateListener: ListenerRegistration? = null
    private var followersCountListener: ListenerRegistration? = null
    private var followingCountListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPublicUserProfileBinding.inflate(inflater, container, false)

        setupInsets()
        setupRecycler()
        setupTopActions()

        if (viewedUserId.isBlank()) {
            binding.tvUsername.text = "User"
            binding.btnFollow.isEnabled = false
            return binding.root
        }

        loadPublicProfileRealtime()
        loadCountersRealtime()
        loadFollowStateRealtime()
        loadPublicPalettesRealtime()

        return binding.root
    }

    // Insets
    private fun setupInsets() {
        val baseTop = binding.rootPublicUserProfile.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootPublicUserProfile) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = baseTop + sys.top)
            insets
        }
    }

    // Верхние кнопки
    private fun setupTopActions() {
        binding.btnClose.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnFollow.setOnClickListener {
            toggleFollow()
        }
    }

    // Список палитр
    private fun setupRecycler() {
        adapter = PublicFeedAdapter(
            items = emptyList(),
            onSaveToMyPalettes = { },
            onSharePalette = { },
            onLike = { item -> handleVote(item, isLike = true) },
            onDislike = { item -> handleVote(item, isLike = false) },
            onAuthorClick = { }
        )

        binding.rvUserPalettes.layoutManager =
            LinearLayoutManager(requireContext())

        binding.rvUserPalettes.adapter = adapter
    }

    // Публичный профиль
    private fun loadPublicProfileRealtime() {
        profileListener?.remove()
        profileListener = db.collection("public_users")
            .document(viewedUserId)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    Log.e("PUB_PROFILE", "public_users error", e)
                    binding.tvUsername.text = "User"
                    binding.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
                    return@addSnapshotListener
                }

                val username =
                    snap.getString("username")
                        ?.trim()
                        .orEmpty()
                        .ifBlank { "User" }

                val avatarUrl =
                    snap.getString("avatarUrl")
                        ?.trim()
                        .orEmpty()

                binding.tvUsername.text = username

                if (avatarUrl.isNotBlank() && avatarUrl != "default_gray") {
                    Glide.with(this)
                        .load(avatarUrl)
                        .placeholder(R.mipmap.ic_launcher_round)
                        .error(R.mipmap.ic_launcher_round)
                        .circleCrop()
                        .into(binding.ivAvatar)
                } else {
                    binding.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
                }
            }
    }

    // Счётчики
    private fun loadCountersRealtime() {
        val me = auth.currentUser?.uid ?: run {
            binding.tvFollowersCount.text = "0"
            binding.tvFollowingCount.text = "0"
            binding.tvLikesCount.text = "0"
            binding.btnFollow.isEnabled = false
            return
        }

        followersCountListener?.remove()
        followersCountListener =
            db.collection("users")
                .document(viewedUserId)
                .collection("followers")
                .addSnapshotListener { snap, e ->
                    if (e != null) {
                        Log.e("PUB_PROFILE", "followers count error", e)
                        return@addSnapshotListener
                    }
                    binding.tvFollowersCount.text =
                        (snap?.size() ?: 0).toString()
                }

        followingCountListener?.remove()
        followingCountListener =
            db.collection("users")
                .document(viewedUserId)
                .collection("following")
                .addSnapshotListener { snap, e ->
                    if (e != null) {
                        Log.e("PUB_PROFILE", "following count error", e)
                        return@addSnapshotListener
                    }
                    binding.tvFollowingCount.text =
                        (snap?.size() ?: 0).toString()
                }

        if (me == viewedUserId) {
            binding.btnFollow.visibility = View.GONE
        }
    }

    // Сумма лайков
    private fun updateLikesSumFromPalettes() {
        val sum = palettes.sumOf { it.likesCount }
        binding.tvLikesCount.text = sum.toString()
    }

    // Состояние подписки
    private fun loadFollowStateRealtime() {
        val myId = auth.currentUser?.uid ?: return
        if (myId == viewedUserId) return

        followStateListener?.remove()
        followStateListener =
            db.collection("users")
                .document(myId)
                .collection("following")
                .document(viewedUserId)
                .addSnapshotListener { snap, e ->
                    if (e != null) {
                        Log.e("PUB_PROFILE", "follow state error", e)
                        return@addSnapshotListener
                    }
                    isFollowing = snap?.exists() == true
                    binding.btnFollow.text =
                        if (isFollowing) "Отписаться" else "Подписаться"
                }
    }

    // Подписка / отписка
    private fun toggleFollow() {
        val myId = auth.currentUser?.uid ?: return
        if (myId == viewedUserId) return

        val now = System.currentTimeMillis()

        val followingRef =
            db.collection("users")
                .document(myId)
                .collection("following")
                .document(viewedUserId)

        val followerRef =
            db.collection("users")
                .document(viewedUserId)
                .collection("followers")
                .document(myId)

        val batch = db.batch()
        if (isFollowing) {
            batch.delete(followingRef)
            batch.delete(followerRef)
        } else {
            batch.set(followingRef, mapOf("createdAt" to now))
            batch.set(followerRef, mapOf("createdAt" to now))
        }

        binding.btnFollow.isEnabled = false
        batch.commit()
            .addOnSuccessListener {
                binding.btnFollow.isEnabled = true
            }
            .addOnFailureListener { e ->
                Log.e("PUB_PROFILE", "toggle follow error", e)
                binding.btnFollow.isEnabled = true
            }
    }

    // Публичные палитры
    private fun loadPublicPalettesRealtime() {
        palettesListener?.remove()

        palettesListener =
            db.collection("color_palettes")
                .whereEqualTo("userId", viewedUserId)
                .whereEqualTo("isPublic", true)
                .orderBy("creationDate", Query.Direction.DESCENDING)
                .addSnapshotListener { snap, e ->
                    if (e != null || snap == null) {
                        Log.e("PUB_PROFILE", "palettes error", e)
                        palettes = emptyList()
                        renderPalettes()
                        updateLikesSumFromPalettes()
                        return@addSnapshotListener
                    }

                    val raw =
                        snap.documents.mapNotNull { doc ->
                            val colors =
                                (doc.get("colors") as? List<*>)
                                    ?.map { it.toString() }
                                    ?: emptyList()

                            if (colors.isEmpty()) return@mapNotNull null

                            val tags =
                                (doc.get("tags") as? List<*>)
                                    ?.map { it.toString() }
                                    ?: emptyList()

                            PublicFeedItem(
                                id = doc.id,
                                paletteName = doc.getString("paletteName") ?: "",
                                colors = colors,
                                authorId = viewedUserId,
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

                    fillVotesForCurrentUser(raw) { withVotes ->
                        palettes = withVotes
                        renderPalettes()
                        updateLikesSumFromPalettes()
                    }
                }
    }

    private fun renderPalettes() {
        val empty = palettes.isEmpty()
        binding.rvUserPalettes.visibility =
            if (empty) View.GONE else View.VISIBLE
        binding.tvEmpty.visibility =
            if (empty) View.VISIBLE else View.GONE
        adapter.submitList(palettes)
    }

    private fun fillVotesForCurrentUser(
        items: List<PublicFeedItem>,
        onDone: (List<PublicFeedItem>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run {
            onDone(items); return
        }

        val tasks = items.map { item ->
            db.collection("color_palettes")
                .document(item.id)
                .collection("votes")
                .document(uid)
                .get()
                .continueWith { t ->
                    val vote =
                        t.result?.getLong("value")?.toInt() ?: 0
                    item.copy(currentUserVote = vote)
                }
        }

        Tasks.whenAllSuccess<PublicFeedItem>(tasks)
            .addOnSuccessListener { res -> onDone(res) }
            .addOnFailureListener { onDone(items) }
    }

    // Лайк / дизлайк
    private fun handleVote(item: PublicFeedItem, isLike: Boolean) {
        val me = auth.currentUser ?: return
        val myUid = me.uid

        val paletteRef =
            db.collection("color_palettes").document(item.id)
        val voteRef =
            paletteRef.collection("votes").document(myUid)

        db.runTransaction { tx ->
            val paletteSnap = tx.get(paletteRef)
            val ownerId = paletteSnap.getString("userId").orEmpty()

            val likes =
                paletteSnap.getLong("likesCount") ?: 0L
            val dislikes =
                paletteSnap.getLong("dislikesCount") ?: 0L

            val prevVote =
                tx.get(voteRef).getLong("value")?.toInt() ?: 0
            val target = if (isLike) 1 else -1

            val (newLikes, newDislikes, newVote) =
                when (prevVote) {
                    target ->
                        if (target == 1)
                            Triple(likes - 1, dislikes, 0)
                        else
                            Triple(likes, dislikes - 1, 0)
                    1, -1 ->
                        if (target == 1)
                            Triple(likes + 1, dislikes - 1, 1)
                        else
                            Triple(likes - 1, dislikes + 1, -1)
                    else ->
                        if (target == 1)
                            Triple(likes + 1, dislikes, 1)
                        else
                            Triple(likes, dislikes + 1, -1)
                }

            tx.update(
                paletteRef,
                mapOf(
                    "likesCount" to newLikes,
                    "dislikesCount" to newDislikes
                )
            )

            if (newVote == 0)
                tx.delete(voteRef)
            else
                tx.set(voteRef, mapOf("value" to newVote))

            VoteResult(newLikes, newDislikes, newVote, ownerId)
        }.addOnSuccessListener { result ->
            palettes =
                palettes.map { old ->
                    if (old.id == item.id)
                        old.copy(
                            likesCount = result.likes,
                            dislikesCount = result.dislikes,
                            currentUserVote = result.userVote
                        )
                    else old
                }

            renderPalettes()
            updateLikesSumFromPalettes()

            if (
                result.userVote != 0 &&
                result.ownerId.isNotBlank() &&
                result.ownerId != myUid
            ) {
                getMyUsername { myName ->
                    val type =
                        if (result.userVote == 1)
                            "LIKE"
                        else
                            "DISLIKE"

                    createNotification(
                        ownerId = result.ownerId,
                        fromUserId = myUid,
                        fromUsername = myName,
                        paletteId = item.id,
                        paletteName = item.paletteName,
                        type = type
                    )
                }
            }
        }.addOnFailureListener { e ->
            Log.e("PUB_PROFILE", "Vote error", e)
        }
    }

    private fun getMyUsername(cb: (String) -> Unit) {
        val uid = auth.currentUser?.uid ?: run {
            cb("Пользователь"); return
        }

        db.collection("public_users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val u =
                    doc.getString("username")
                        ?.trim()
                        .orEmpty()

                cb(if (u.isNotBlank()) u else "Пользователь")
            }
            .addOnFailureListener {
                cb("Пользователь")
            }
    }

    private fun createNotification(
        ownerId: String,
        fromUserId: String,
        fromUsername: String,
        paletteId: String,
        paletteName: String,
        type: String
    ) {
        val title =
            if (type == "LIKE") "Новый лайк" else "Новый дизлайк"

        val message =
            "$fromUsername ${
                if (type == "LIKE")
                    "поставил(а) лайк"
                else
                    "поставил(а) дизлайк"
            } вашей палитре: $paletteName"

        val data = hashMapOf(
            "type" to type,
            "fromUserId" to fromUserId,
            "fromUsername" to fromUsername,
            "paletteId" to paletteId,
            "title" to title,
            "message" to message,
            "createdAt" to System.currentTimeMillis(),
            "isRead" to false
        )

        db.collection("users")
            .document(ownerId)
            .collection("notifications")
            .add(data)
            .addOnFailureListener { e ->
                Log.e("NOTIFS", "Notification error", e)
            }
    }

    private data class VoteResult(
        val likes: Long,
        val dislikes: Long,
        val userVote: Int,
        val ownerId: String
    )

    override fun onDestroyView() {
        super.onDestroyView()

        profileListener?.remove()
        palettesListener?.remove()
        followStateListener?.remove()
        followersCountListener?.remove()
        followingCountListener?.remove()

        profileListener = null
        palettesListener = null
        followStateListener = null
        followersCountListener = null
        followingCountListener = null

        _binding = null
    }
}
