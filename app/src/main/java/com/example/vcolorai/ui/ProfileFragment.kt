package com.example.vcolorai.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.vcolorai.R
import com.example.vcolorai.databinding.FragmentProfileBinding
import com.example.vcolorai.ui.common.BaseFragment
import com.example.vcolorai.ui.feed.PublicFeedAdapter
import com.example.vcolorai.ui.feed.PublicFeedItem
import com.example.vcolorai.ui.notifications.NotificationItem
import com.example.vcolorai.ui.notifications.NotificationsAdapter
import com.example.vcolorai.ui.profile.EditProfileDialogFragment
import com.example.vcolorai.ui.profile.PublicUserProfileBottomSheet
import com.example.vcolorai.ui.settings.SettingsFragment
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class ProfileFragment : BaseFragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var currentAvatarUrl: String? = null
    private var currentUsername: String = "User"

    // ---------------- Notifications ----------------
    private lateinit var notificationsAdapter: NotificationsAdapter
    private var notifications: List<NotificationItem> = emptyList()
    private var notificationsListener: ListenerRegistration? = null

    // ---------------- Palettes (as Feed) ----------------
    private lateinit var feedAdapter: PublicFeedAdapter
    private var myPublicFeedItems: List<PublicFeedItem> = emptyList()
    private var myPalettesListener: ListenerRegistration? = null

    // ---------------- Likes tab ----------------
    private var likedFeedItems: List<PublicFeedItem> = emptyList()
    private var likedListener: ListenerRegistration? = null

    // ---------------- Counters listeners ----------------
    private var followersCountListener: ListenerRegistration? = null
    private var followingCountListener: ListenerRegistration? = null

    // ✅ NEW: лайки в шапке — realtime listener (чтобы не инвертировалось)
    private var likesSumListener: ListenerRegistration? = null

    // 0-notifs, 1-my palettes, 2-likes
    private var selectedTab = 0

    private enum class FollowListMode { FOLLOWERS, FOLLOWING }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)

        setupResultListener()
        setupActions()

        setupNotificationsList()
        setupFeedList()
        setupTabs()
        setupFollowersFollowingClicks()

        loadProfileHeader()
        loadCountersRealtime()

        loadNotificationsRealtime()
        loadMyPublicPalettesRealtimeAsFeed()
        loadLikedPalettesRealtime()

        return binding.root
    }

    override fun applyInsets(root: View) {
        val baseTop = binding.rootProfile.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootProfile) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootProfile.updatePadding(top = baseTop + sys.top)
            binding.bottomProfileContainer.updatePadding(bottom = 0)
            insets
        }
    }

    // -------------------- Header --------------------

    private fun loadProfileHeader() {
        val user = auth.currentUser ?: run {
            binding.tvUsername.text = "Гость"
            binding.tvEmail.text = ""
            binding.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            return
        }

        binding.tvEmail.text = user.email ?: ""

        db.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username")?.trim().orEmpty()
                val avatarUrl = doc.getString("avatarUrl")

                currentUsername = if (username.isNotBlank()) username else "User"
                currentAvatarUrl = avatarUrl

                binding.tvUsername.text = currentUsername

                if (!avatarUrl.isNullOrBlank()) {
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
            .addOnFailureListener {
                binding.tvUsername.text = currentUsername
                binding.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
    }

    // -------------------- Counters (REALTIME) --------------------

    private fun loadCountersRealtime() {
        val user = auth.currentUser ?: return
        val uid = user.uid

        followersCountListener?.remove()
        followersCountListener = db.collection("users").document(uid)
            .collection("followers")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.e("PROFILE", "followers count error", e)
                    return@addSnapshotListener
                }
                binding.tvFollowersCount.text = (snap?.size() ?: 0).toString()
            }

        followingCountListener?.remove()
        followingCountListener = db.collection("users").document(uid)
            .collection("following")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.e("PROFILE", "following count error", e)
                    return@addSnapshotListener
                }
                binding.tvFollowingCount.text = (snap?.size() ?: 0).toString()
            }

        // ✅ FIX: лайки в шапке = realtime сумма likesCount твоих публичных палитр
        likesSumListener?.remove()
        likesSumListener = db.collection("color_palettes")
            .whereEqualTo("userId", uid)
            .whereEqualTo("isPublic", true)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.e("PROFILE", "likes sum listener error", e)
                    return@addSnapshotListener
                }
                val sum = snap?.documents?.sumOf { it.getLong("likesCount") ?: 0L } ?: 0L
                binding.tvLikesCount.text = sum.toString()
            }
    }

    // -------------------- Followers / Following (dialogs) --------------------

    private fun setupFollowersFollowingClicks() {
        binding.tvFollowersCount.setOnClickListener { showFollowersListDialog() }
        binding.tvFollowingCount.setOnClickListener { showFollowingListDialog() }
    }

    private fun showFollowersListDialog() {
        val me = auth.currentUser ?: return
        loadUsersListDialog(
            title = "Подписчики",
            mode = FollowListMode.FOLLOWERS,
            idsQuery = db.collection("users").document(me.uid).collection("followers")
        )
    }

    private fun showFollowingListDialog() {
        val me = auth.currentUser ?: return
        loadUsersListDialog(
            title = "Подписки",
            mode = FollowListMode.FOLLOWING,
            idsQuery = db.collection("users").document(me.uid).collection("following")
        )
    }

    private fun loadUsersListDialog(
        title: String,
        mode: FollowListMode,
        idsQuery: CollectionReference
    ) {
        val me = auth.currentUser ?: return

        idsQuery.get()
            .addOnSuccessListener { snap ->
                val ids = snap.documents.map { it.id }.filter { it.isNotBlank() }

                if (ids.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setMessage("Пока пусто")
                        .setPositiveButton("Ок", null)
                        .show()
                    return@addOnSuccessListener
                }

                val needMyFollowing = (mode == FollowListMode.FOLLOWERS)

                fun continueWithMyFollowing(myFollowingIds: Set<String>) {
                    val tasks = ids.map { uid ->
                        db.collection("public_users").document(uid).get()
                            .continueWith { t ->
                                val doc = t.result
                                val usernameRaw = doc?.getString("username")?.trim().orEmpty()
                                val username = if (usernameRaw.isNotBlank()) usernameRaw else "@${uid.take(6)}"

                                val label = when (mode) {
                                    FollowListMode.FOLLOWING -> "✓ Вы подписаны"
                                    FollowListMode.FOLLOWERS ->
                                        if (myFollowingIds.contains(uid)) "↔ Взаимно" else "+ Подписаться"
                                }

                                Triple(uid, username, label)
                            }
                    }

                    Tasks.whenAllSuccess<Triple<String, String, String>>(tasks)
                        .addOnSuccessListener { triples ->
                            val list = triples.toList()
                                .filter { it.first.isNotBlank() }
                                .sortedBy { it.second.lowercase() }

                            val rows = list.map { (_, name, label) ->
                                "$name  •  $label"
                            }.toTypedArray()

                            AlertDialog.Builder(requireContext())
                                .setTitle(title)
                                .setItems(rows) { _, which ->
                                    val clickedId = list[which].first
                                    if (mode == FollowListMode.FOLLOWING) {
                                        showFollowingItemActions(clickedId)
                                    } else {
                                        PublicUserProfileBottomSheet
                                            .newInstance(clickedId)
                                            .show(parentFragmentManager, "public_user_sheet")
                                    }
                                }
                                .setNegativeButton("Закрыть", null)
                                .show()
                        }
                        .addOnFailureListener { e ->
                            Log.e("PROFILE", "load $title users error", e)
                            AlertDialog.Builder(requireContext())
                                .setTitle(title)
                                .setMessage("Не удалось загрузить список: ${e.message}")
                                .setPositiveButton("Ок", null)
                                .show()
                        }
                }

                if (!needMyFollowing) {
                    continueWithMyFollowing(emptySet())
                } else {
                    db.collection("users").document(me.uid).collection("following")
                        .get()
                        .addOnSuccessListener { myFollowingSnap ->
                            val myFollowingIds = myFollowingSnap.documents.map { it.id }.toSet()
                            continueWithMyFollowing(myFollowingIds)
                        }
                        .addOnFailureListener {
                            continueWithMyFollowing(emptySet())
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("PROFILE", "idsQuery error", e)
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMessage("Не удалось загрузить список: ${e.message}")
                    .setPositiveButton("Ок", null)
                    .show()
            }
    }

    private fun showFollowingItemActions(userId: String) {
        val options = arrayOf("Открыть профиль", "Отписаться")

        AlertDialog.Builder(requireContext())
            .setTitle("Действия")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> PublicUserProfileBottomSheet
                        .newInstance(userId)
                        .show(parentFragmentManager, "public_user_sheet")
                    1 -> unfollow(userId) { ok ->
                        if (ok) showFollowingListDialog()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun unfollow(targetId: String, onDone: (Boolean) -> Unit) {
        val me = auth.currentUser?.uid ?: run { onDone(false); return }
        if (targetId.isBlank() || targetId == me) { onDone(false); return }

        val followingRef = db.collection("users").document(me)
            .collection("following").document(targetId)

        val followerRef = db.collection("users").document(targetId)
            .collection("followers").document(me)

        val batch = db.batch()
        batch.delete(followingRef)
        batch.delete(followerRef)

        batch.commit()
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { e ->
                Log.e("PROFILE", "unfollow error", e)
                onDone(false)
            }
    }

    // -------------------- Buttons --------------------

    private fun setupActions() {
        binding.btnEditProfile.setOnClickListener {
            EditProfileDialogFragment.newInstance(
                username = currentUsername,
                avatarUrl = currentAvatarUrl
            ).show(parentFragmentManager, "edit_profile")
        }

        binding.btnShareProfile.setOnClickListener {
            val text = "Мой профиль в VColorAI: @$currentUsername"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "Поделиться профилем"))
        }

        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment())
                .addToBackStack("settings")
                .commit()
        }
    }

    private fun setupResultListener() {
        parentFragmentManager.setFragmentResultListener(
            "profile_updated",
            viewLifecycleOwner
        ) { _, bundle ->
            val username = bundle.getString("username") ?: return@setFragmentResultListener
            val avatarUrl = bundle.getString("avatarUrl")

            currentUsername = username
            currentAvatarUrl = avatarUrl

            binding.tvUsername.text = username
            if (!avatarUrl.isNullOrBlank()) {
                Glide.with(this).load(avatarUrl).circleCrop().into(binding.ivAvatar)
            } else {
                binding.ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
        }
    }

    // -------------------- Notifications --------------------

    private fun setupNotificationsList() {
        notificationsAdapter = NotificationsAdapter { notif -> markNotificationAsRead(notif) }
        binding.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotifications.adapter = notificationsAdapter
    }

    private fun loadNotificationsRealtime() {
        val user = auth.currentUser ?: run {
            notifications = emptyList()
            renderTabContent()
            return
        }

        notificationsListener?.remove()
        notificationsListener = db.collection("users")
            .document(user.uid)
            .collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    Log.e("PROFILE", "Notifications load error", e)
                    notifications = emptyList()
                    renderTabContent()
                    return@addSnapshotListener
                }

                notifications = snap.documents.map { doc ->
                    NotificationItem(
                        id = doc.id,
                        type = doc.getString("type") ?: "system",
                        fromUserId = doc.getString("fromUserId"),
                        title = doc.getString("title") ?: "",
                        message = doc.getString("message") ?: "",
                        paletteId = doc.getString("paletteId"),
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        isRead = doc.getBoolean("isRead") ?: false
                    )
                }

                renderTabContent()
            }
    }

    private fun markNotificationAsRead(item: NotificationItem) {
        val user = auth.currentUser ?: return
        if (item.isRead) return

        db.collection("users")
            .document(user.uid)
            .collection("notifications")
            .document(item.id)
            .update("isRead", true)
    }

    // -------------------- Feed list (My palettes + Likes use same RV) --------------------

    private fun setupFeedList() {
        feedAdapter = PublicFeedAdapter(
            items = emptyList(),
            onSaveToMyPalettes = { /* в профиле не используем */ },
            onSharePalette = { item -> sharePalette(item) },
            onLike = { item -> handleVote(item, isLike = true) },
            onDislike = { item -> handleVote(item, isLike = false) },
            onAuthorClick = { authorId ->
                // ✅ в Лайках автор = другой пользователь
                if (!authorId.isNullOrBlank()) {
                    PublicUserProfileBottomSheet.newInstance(authorId)
                        .show(parentFragmentManager, "public_user_sheet")
                }
            }
        )

        binding.rvMyPublicPalettes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMyPublicPalettes.adapter = feedAdapter
    }

    // -------------------- My public palettes --------------------

    private fun loadMyPublicPalettesRealtimeAsFeed() {
        val user = auth.currentUser ?: run {
            myPublicFeedItems = emptyList()
            renderTabContent()
            return
        }

        myPalettesListener?.remove()
        myPalettesListener = db.collection("color_palettes")
            .whereEqualTo("userId", user.uid)
            .whereEqualTo("isPublic", true)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    Log.e("PROFILE", "My public palettes load error", e)
                    myPublicFeedItems = emptyList()
                    renderTabContent()
                    return@addSnapshotListener
                }

                val raw = snap.documents.mapNotNull { doc ->
                    val colors = (doc.get("colors") as? List<*>)?.map { it.toString() } ?: emptyList()
                    if (colors.isEmpty()) return@mapNotNull null

                    val tags = (doc.get("tags") as? List<*>)?.map { it.toString() } ?: emptyList()

                    PublicFeedItem(
                        id = doc.id,
                        paletteName = doc.getString("paletteName") ?: "",
                        colors = colors,
                        authorId = user.uid,
                        authorName = currentUsername,
                        description = doc.getString("promptText") ?: "",
                        tags = tags,
                        likesCount = doc.getLong("likesCount") ?: 0L,
                        dislikesCount = doc.getLong("dislikesCount") ?: 0L,
                        imageUri = doc.getString("imageUri"),
                        currentUserVote = 0,
                        createdAt = doc.getLong("creationDate") ?: 0L
                    )
                }

                fillVotesForCurrentUser(raw) { withVotes ->
                    myPublicFeedItems = withVotes
                    renderTabContent()
                }
            }
    }

    // -------------------- Likes tab (users/{me}/liked_palettes index) --------------------

    private fun loadLikedPalettesRealtime() {
        val me = auth.currentUser ?: run {
            likedFeedItems = emptyList()
            renderTabContent()
            return
        }

        likedListener?.remove()
        likedListener = db.collection("users")
            .document(me.uid)
            .collection("liked_palettes")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    Log.e("PROFILE", "liked_palettes load error", e)
                    likedFeedItems = emptyList()
                    renderTabContent()
                    return@addSnapshotListener
                }

                val paletteIds = snap.documents.map { it.id }.filter { it.isNotBlank() }

                if (paletteIds.isEmpty()) {
                    likedFeedItems = emptyList()
                    renderTabContent()
                    return@addSnapshotListener
                }

                // 1) грузим палитры по id
                val paletteTasks = paletteIds.map { pid ->
                    db.collection("color_palettes").document(pid).get()
                        .continueWith { t ->
                            val doc = t.result
                            if (doc == null || !doc.exists()) return@continueWith null

                            val colors = (doc.get("colors") as? List<*>)?.map { it.toString() } ?: emptyList()
                            if (colors.isEmpty()) return@continueWith null

                            val tags = (doc.get("tags") as? List<*>)?.map { it.toString() } ?: emptyList()
                            val authorId = doc.getString("userId") ?: ""

                            PublicFeedItem(
                                id = doc.id,
                                paletteName = doc.getString("paletteName") ?: "",
                                colors = colors,
                                authorId = authorId,
                                authorName = "", // заполним ниже из public_users
                                description = doc.getString("promptText") ?: "",
                                tags = tags,
                                likesCount = doc.getLong("likesCount") ?: 0L,
                                dislikesCount = doc.getLong("dislikesCount") ?: 0L,
                                imageUri = doc.getString("imageUri"),
                                currentUserVote = 0,
                                createdAt = doc.getLong("creationDate") ?: 0L
                            )
                        }
                }

                Tasks.whenAllSuccess<PublicFeedItem?>(paletteTasks)
                    .addOnSuccessListener { maybeItems ->
                        val raw = maybeItems.filterNotNull()

                        // 2) подтягиваем голоса текущего пользователя (для состояния кнопок)
                        fillVotesForCurrentUser(raw) { withVotes ->
                            // 3) подтягиваем имена авторов из public_users
                            fillAuthors(withVotes) { withAuthors ->
                                // сохраняем порядок как в liked_palettes (createdAt DESC)
                                val byId = withAuthors.associateBy { it.id }
                                likedFeedItems = paletteIds.mapNotNull { byId[it] }
                                renderTabContent()
                            }
                        }
                    }
                    .addOnFailureListener { err ->
                        Log.e("PROFILE", "liked palettes fetch error", err)
                        likedFeedItems = emptyList()
                        renderTabContent()
                    }
            }
    }

    private fun fillAuthors(
        items: List<PublicFeedItem>,
        onDone: (List<PublicFeedItem>) -> Unit
    ) {
        val uniqueAuthorIds = items.map { it.authorId }.filter { it.isNotBlank() }.distinct()
        if (uniqueAuthorIds.isEmpty()) {
            onDone(items); return
        }

        val tasks = uniqueAuthorIds.map { uid ->
            db.collection("public_users").document(uid).get()
                .continueWith { t ->
                    val doc = t.result
                    val name = doc?.getString("username")?.trim().orEmpty()
                    uid to (if (name.isNotBlank()) name else "@${uid.take(6)}")
                }
        }

        Tasks.whenAllSuccess<Pair<String, String>>(tasks)
            .addOnSuccessListener { pairs ->
                val map = pairs.toMap()
                onDone(items.map { it.copy(authorName = map[it.authorId] ?: it.authorName) })
            }
            .addOnFailureListener {
                onDone(items)
            }
    }

    // -------------------- Votes (AND liked_palettes index) --------------------

    private fun fillVotesForCurrentUser(
        items: List<PublicFeedItem>,
        onDone: (List<PublicFeedItem>) -> Unit
    ) {
        val uid = auth.currentUser?.uid ?: run {
            onDone(items); return
        }

        val tasks = items.map { item ->
            db.collection("color_palettes").document(item.id)
                .collection("votes").document(uid)
                .get()
                .continueWith { t ->
                    val vote = t.result?.getLong("value")?.toInt() ?: 0
                    item.copy(currentUserVote = vote)
                }
        }

        Tasks.whenAllSuccess<PublicFeedItem>(tasks)
            .addOnSuccessListener { res -> onDone(res) }
            .addOnFailureListener { onDone(items) }
    }

    private fun handleVote(item: PublicFeedItem, isLike: Boolean) {
        val user = auth.currentUser ?: return
        val uid = user.uid
        val now = System.currentTimeMillis()

        val paletteRef = db.collection("color_palettes").document(item.id)
        val voteRef = paletteRef.collection("votes").document(uid)

        // ✅ индекс лайков (для вкладки Лайки)
        val likedRef = db.collection("users")
            .document(uid)
            .collection("liked_palettes")
            .document(item.id)

        db.runTransaction { tx ->
            val paletteSnap = tx.get(paletteRef)

            val likes = paletteSnap.getLong("likesCount") ?: 0L
            val dislikes = paletteSnap.getLong("dislikesCount") ?: 0L

            val prevVote = tx.get(voteRef).getLong("value")?.toInt() ?: 0
            val target = if (isLike) 1 else -1

            val (newLikes, newDislikes, newVote) = when (prevVote) {
                target -> if (target == 1) Triple(likes - 1, dislikes, 0) else Triple(likes, dislikes - 1, 0)
                1, -1 -> if (target == 1) Triple(likes + 1, dislikes - 1, 1) else Triple(likes - 1, dislikes + 1, -1)
                else -> if (target == 1) Triple(likes + 1, dislikes, 1) else Triple(likes, dislikes + 1, -1)
            }

            tx.update(paletteRef, mapOf("likesCount" to newLikes, "dislikesCount" to newDislikes))
            if (newVote == 0) tx.delete(voteRef) else tx.set(voteRef, mapOf("value" to newVote))

            // ✅ синхронизируем индекс liked_palettes
            if (newVote == 1) {
                tx.set(likedRef, mapOf("createdAt" to now))
            } else {
                tx.delete(likedRef)
            }

            VoteResult(newLikes, newDislikes, newVote)
        }.addOnSuccessListener { result ->
            // обновляем список "Мои палитры" и "Лайки" локально (чтобы UI реагировал без задержек)
            myPublicFeedItems = myPublicFeedItems.map { old ->
                if (old.id == item.id) old.copy(
                    likesCount = result.likes,
                    dislikesCount = result.dislikes,
                    currentUserVote = result.userVote
                ) else old
            }
            likedFeedItems = likedFeedItems.map { old ->
                if (old.id == item.id) old.copy(
                    likesCount = result.likes,
                    dislikesCount = result.dislikes,
                    currentUserVote = result.userVote
                ) else old
            }

            renderTabContent()

            // ✅ FIX: НЕ пересчитываем лайки вручную.
            // tvLikesCount обновляется realtime listener'ом (likesSumListener)
        }.addOnFailureListener { e ->
            Log.e("PROFILE", "Vote error", e)
        }
    }

    private data class VoteResult(
        val likes: Long,
        val dislikes: Long,
        val userVote: Int
    )

    // -------------------- Sharing --------------------

    private fun sharePalette(item: PublicFeedItem) {
        val text = buildString {
            appendLine(item.paletteName.ifBlank { "Палитра" })
            if (!item.description.isNullOrBlank()) {
                appendLine(item.description)
                appendLine()
            }
            appendLine("Цвета:")
            item.colors.forEach { hex -> appendLine(hex) }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Поделиться палитрой"))
    }

    // -------------------- Tabs --------------------

    private fun setupTabs() {
        fun select(tab: Int) {
            selectedTab = tab
            renderTabContent()
        }

        binding.tvTabNotifications.setOnClickListener { select(0) }
        binding.tvTabMyPalettes.setOnClickListener { select(1) }
        binding.tvTabLikes.setOnClickListener { select(2) }

        select(0)
    }

    private fun renderTabContent() {
        binding.rvNotifications.visibility = View.GONE
        binding.tvTabNotificationsEmpty.visibility = View.GONE

        binding.rvMyPublicPalettes.visibility = View.GONE
        binding.tvMyPublicPalettesEmpty.visibility = View.GONE

        binding.tvTabLikesStub.visibility = View.GONE

        when (selectedTab) {
            0 -> {
                val empty = notifications.isEmpty()
                binding.rvNotifications.visibility = if (!empty) View.VISIBLE else View.GONE
                binding.tvTabNotificationsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                notificationsAdapter.submitList(notifications)
            }
            1 -> {
                val empty = myPublicFeedItems.isEmpty()
                binding.rvMyPublicPalettes.visibility = if (!empty) View.VISIBLE else View.GONE
                binding.tvMyPublicPalettesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.tvMyPublicPalettesEmpty.text = "У вас пока нет публичных палитр"
                feedAdapter.submitList(myPublicFeedItems)
            }
            2 -> {
                val empty = likedFeedItems.isEmpty()
                binding.rvMyPublicPalettes.visibility = if (!empty) View.VISIBLE else View.GONE
                binding.tvMyPublicPalettesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.tvMyPublicPalettesEmpty.text = "Вы пока не лайкнули ни одной палитры"
                feedAdapter.submitList(likedFeedItems)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        notificationsListener?.remove()
        myPalettesListener?.remove()
        likedListener?.remove()
        followersCountListener?.remove()
        followingCountListener?.remove()
        likesSumListener?.remove()

        notificationsListener = null
        myPalettesListener = null
        likedListener = null
        followersCountListener = null
        followingCountListener = null
        likesSumListener = null

        _binding = null
    }
}
