package com.example.vcolorai.ui.publicfeed

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vcolorai.databinding.FragmentPublicFeedBinding
import com.example.vcolorai.ui.common.BaseFragment
import com.example.vcolorai.ui.feed.PublicFeedAdapter
import com.example.vcolorai.ui.feed.PublicFeedItem
import com.example.vcolorai.ui.profile.PublicUserProfileBottomSheet
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PublicFeedFragment : BaseFragment() {

    private var _binding: FragmentPublicFeedBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var adapter: PublicFeedAdapter

    /** “сырой” список ленты (после загрузки и заполнения голосов) */
    private var fullList: List<PublicFeedItem> = emptyList()

    /** cache uid -> username (из public_users) */
    private val authorUsernameCache = mutableMapOf<String, String>()

    // -------- @mention bottom sheet debounce --------
    private val uiHandler = Handler(Looper.getMainLooper())
    private var mentionRunnable: Runnable? = null
    private var lastMentionKey: String? = null
    private var lastShownUserId: String? = null

    // -------- Sort / Filters --------
    private enum class SortMode {
        DATE_NEW, DATE_OLD,
        LIKES_DESC,
        SCORE_DESC,      // likes - dislikes
        DISLIKES_DESC
    }

    private data class FeedFilters(
        var onlyWithImage: Boolean = false,
        var onlyText: Boolean = false,
        var onlyWithTags: Boolean = false,
        var onlyWithoutTags: Boolean = false
    )

    private var sortMode: SortMode = SortMode.DATE_NEW
    private var filters = FeedFilters()

    // ✅ фильтр по тегу отдельно
    private var tagQueryFilter: String = ""

    // ---------------- Offline cache ----------------
    private val gson = Gson()
    private val prefs by lazy {
        requireContext().getSharedPreferences("feed_cache", Context.MODE_PRIVATE)
    }
    private val FEED_CACHE_KEY = "public_feed_v1"

    // ---------------- Network callback ----------------
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastInternetState: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPublicFeedBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupRecycler()
        setupSearch()
        setupFilterMenu()

        // стартовая загрузка: если оффлайн — покажем кэш
        val online = hasInternet()
        showOfflineBanner(!online)
        if (online) loadPublicPalettes() else loadFromCacheOrEmpty()

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        observeNetwork()
    }

    override fun onStop() {
        super.onStop()
        unregisterNetwork()
    }

    // ---------------- Recycler ----------------

    private fun setupRecycler() {
        adapter = PublicFeedAdapter(
            items = emptyList(),
            onSaveToMyPalettes = { item -> saveToMyPalettes(item) },
            onSharePalette = { item -> sharePalette(item) },
            onLike = { item -> handleVote(item, isLike = true) },
            onDislike = { item -> handleVote(item, isLike = false) },
            onAuthorClick = { authorId ->
                if (authorId.isNotBlank()) {
                    PublicUserProfileBottomSheet
                        .newInstance(authorId)
                        .show(parentFragmentManager, "public_user_sheet")
                }
            }
        )

        binding.rvFeed.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFeed.adapter = adapter
    }

    // ---------------- Search (+ @mention) ----------------

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty()
                handleAtMention(raw)
                applyFilters()
            }
        })
    }

    private fun handleAtMention(raw: String) {
        val t = raw.trim()
        if (!t.startsWith("@") || t.length < 2) {
            lastMentionKey = null
            lastShownUserId = null
            mentionRunnable?.let { uiHandler.removeCallbacks(it) }
            mentionRunnable = null
            return
        }

        val key = t.drop(1).trim().lowercase().replace(" ", "")
        if (key.isBlank()) return

        if (key == lastMentionKey) return
        lastMentionKey = key

        mentionRunnable?.let { uiHandler.removeCallbacks(it) }
        mentionRunnable = Runnable { resolveMentionAndShow(key) }.also {
            uiHandler.postDelayed(it, 350)
        }
    }

    /**
     * usernames/{usernameKey} -> { uid }
     */
    private fun resolveMentionAndShow(usernameKey: String) {
        db.collection("usernames").document(usernameKey)
            .get()
            .addOnSuccessListener { doc ->
                val uid = doc.getString("uid")?.trim().orEmpty()
                if (uid.isBlank()) return@addOnSuccessListener

                if (uid == lastShownUserId) return@addOnSuccessListener
                lastShownUserId = uid

                PublicUserProfileBottomSheet
                    .newInstance(uid)
                    .show(parentFragmentManager, "public_user_sheet")
            }
            .addOnFailureListener { e ->
                Log.e("FEED", "mention resolve error", e)
            }
    }

    // ---------------- Filter / Sort menu (⋮) ----------------

    private fun setupFilterMenu() {
        binding.btnFilter.setOnClickListener { showFilterSortMenu() }
    }

    private fun showFilterSortMenu() {
        val popup = PopupMenu(requireContext(), binding.btnFilter)
        val menu = popup.menu

        // ---- Sort ----
        menu.add("Сортировка: новые").setOnMenuItemClickListener {
            sortMode = SortMode.DATE_NEW
            applyFilters()
            true
        }
        menu.add("Сортировка: старые").setOnMenuItemClickListener {
            sortMode = SortMode.DATE_OLD
            applyFilters()
            true
        }
        menu.add("Сортировка: по лайкам").setOnMenuItemClickListener {
            sortMode = SortMode.LIKES_DESC
            applyFilters()
            true
        }
        menu.add("Сортировка: рейтинг (лайки-дизлайки)").setOnMenuItemClickListener {
            sortMode = SortMode.SCORE_DESC
            applyFilters()
            true
        }
        menu.add("Сортировка: по дизлайкам").setOnMenuItemClickListener {
            sortMode = SortMode.DISLIKES_DESC
            applyFilters()
            true
        }

        menu.add("—")

        // ---- Filters ----
        menu.add(toggleTitle(filters.onlyWithImage, "Только с фото")).setOnMenuItemClickListener {
            filters.onlyWithImage = !filters.onlyWithImage
            if (filters.onlyWithImage) filters.onlyText = false
            applyFilters()
            true
        }

        menu.add(toggleTitle(filters.onlyText, "Только текст")).setOnMenuItemClickListener {
            filters.onlyText = !filters.onlyText
            if (filters.onlyText) filters.onlyWithImage = false
            applyFilters()
            true
        }

        menu.add(toggleTitle(filters.onlyWithTags, "Только с тегами")).setOnMenuItemClickListener {
            filters.onlyWithTags = !filters.onlyWithTags
            if (filters.onlyWithTags) filters.onlyWithoutTags = false
            applyFilters()
            true
        }

        menu.add(toggleTitle(filters.onlyWithoutTags, "Только без тегов")).setOnMenuItemClickListener {
            filters.onlyWithoutTags = !filters.onlyWithoutTags
            if (filters.onlyWithoutTags) filters.onlyWithTags = false
            applyFilters()
            true
        }

        menu.add("Фильтр по тегу…").setOnMenuItemClickListener {
            showTagQueryDialog()
            true
        }

        menu.add("Сбросить фильтры").setOnMenuItemClickListener {
            sortMode = SortMode.DATE_NEW
            filters = FeedFilters()
            tagQueryFilter = ""
            applyFilters()
            true
        }

        popup.show()
    }

    private fun toggleTitle(enabled: Boolean, title: String): String {
        return if (enabled) "✓ $title" else title
    }

    private fun showTagQueryDialog() {
        val input = EditText(requireContext()).apply {
            hint = "например: pastel"
            setText(tagQueryFilter)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Фильтр по тегу")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                tagQueryFilter = input.text.toString().trim()
                applyFilters()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Очистить") { _, _ ->
                tagQueryFilter = ""
                applyFilters()
            }
            .show()
    }

    // ---------------- Load feed ----------------

    private fun loadPublicPalettes() {
        db.collection("color_palettes")
            .whereEqualTo("isPublic", true)
            .get()
            .addOnSuccessListener { snap ->
                val raw = snap.documents.mapNotNull { doc ->
                    val colors = (doc.get("colors") as? List<*>)?.map { it.toString() } ?: emptyList()
                    if (colors.isEmpty()) return@mapNotNull null

                    val tags = (doc.get("tags") as? List<*>)?.map { it.toString() } ?: emptyList()

                    PublicFeedItem(
                        id = doc.id,
                        paletteName = doc.getString("paletteName") ?: "",
                        colors = colors,
                        authorId = doc.getString("userId") ?: "",
                        authorName = doc.getString("authorName"),
                        description = doc.getString("promptText"),
                        tags = tags,
                        likesCount = doc.getLong("likesCount") ?: 0L,
                        dislikesCount = doc.getLong("dislikesCount") ?: 0L,
                        imageUri = doc.getString("imageUri"),
                        currentUserVote = 0,
                        createdAt = doc.getLong("creationDate") ?: 0L
                    )
                }

                preloadAuthorUsernames(raw)

                fillVotesForCurrentUser(raw) { withVotes ->
                    fullList = withVotes
                    saveFeedCache(fullList)      // ✅ кэшируем успешную загрузку
                    applyFilters()
                }
            }
            .addOnFailureListener { e ->
                Log.e("FEED", "load feed error", e)

                // ✅ если не загрузилось — показываем кэш
                if (!hasInternet()) {
                    showOfflineBanner(true)
                    loadFromCacheOrEmpty()
                } else {
                    Toast.makeText(requireContext(), "Ошибка загрузки ленты: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun preloadAuthorUsernames(items: List<PublicFeedItem>) {
        val ids = items.map { it.authorId.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { authorUsernameCache.containsKey(it) }

        if (ids.isEmpty()) return

        val chunks = ids.chunked(10)
        val idPath = FieldPath.documentId()

        chunks.forEach { chunk ->
            db.collection("public_users")
                .whereIn(idPath, chunk)
                .get()
                .addOnSuccessListener { snap ->
                    for (doc in snap.documents) {
                        val uid = doc.id
                        val username = doc.getString("username")?.trim().orEmpty()
                        if (uid.isNotBlank() && username.isNotBlank()) {
                            authorUsernameCache[uid] = username
                        }
                    }
                    applyFilters()
                }
        }
    }

    // ---------------- Offline cache helpers ----------------

    private fun saveFeedCache(list: List<PublicFeedItem>) {
        try {
            val json = gson.toJson(list)
            prefs.edit().putString(FEED_CACHE_KEY, json).apply()
        } catch (e: Exception) {
            Log.e("FEED", "cache save error", e)
        }
    }

    private fun loadFeedCache(): List<PublicFeedItem> {
        val json = prefs.getString(FEED_CACHE_KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PublicFeedItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("FEED", "cache parse error", e)
            emptyList()
        }
    }

    private fun loadFromCacheOrEmpty() {
        val cached = loadFeedCache()
        if (cached.isEmpty()) {
            fullList = emptyList()
            adapter.submitList(emptyList())
            Toast.makeText(requireContext(), "Нет интернета и нет сохранённой ленты", Toast.LENGTH_SHORT).show()
        } else {
            fullList = cached
            applyFilters()
        }
    }

    // ---------------- Filtering & sorting ----------------

    private fun applyFilters() {
        val queryRaw = binding.etSearch.text?.toString().orEmpty().trim()
        val query = queryRaw.lowercase()

        var list = fullList

        // --- text search: name/desc/tags/author username ---
        if (query.isNotEmpty()) {
            list = list.filter { item ->
                val nameHit = item.paletteName.lowercase().contains(query)
                val descHit = item.description?.lowercase()?.contains(query) == true
                val tagsHit = item.tags.any { it.lowercase().contains(query) }

                val authorUsername = authorUsernameCache[item.authorId.trim()]
                    ?: item.authorName
                    ?: ""

                val authorHit = authorUsername.lowercase().contains(query)

                nameHit || descHit || tagsHit || authorHit
            }
        }

        // --- filters: image/text ---
        if (filters.onlyWithImage) {
            list = list.filter { !it.imageUri.isNullOrBlank() }
        }
        if (filters.onlyText) {
            list = list.filter { it.imageUri.isNullOrBlank() }
        }

        // --- filters: tags ---
        if (filters.onlyWithTags) {
            list = list.filter { it.tags.isNotEmpty() }
        }
        if (filters.onlyWithoutTags) {
            list = list.filter { it.tags.isEmpty() }
        }

        // --- filter: tag contains ---
        val tagQ = tagQueryFilter.trim().lowercase()
        if (tagQ.isNotEmpty()) {
            list = list.filter { item ->
                item.tags.any { it.lowercase().contains(tagQ) }
            }
        }

        // --- sorting ---
        list = when (sortMode) {
            SortMode.DATE_NEW -> list.sortedByDescending { it.createdAt }
            SortMode.DATE_OLD -> list.sortedBy { it.createdAt }
            SortMode.LIKES_DESC -> list.sortedByDescending { it.likesCount }
            SortMode.SCORE_DESC -> list.sortedByDescending { (it.likesCount - it.dislikesCount) }
            SortMode.DISLIKES_DESC -> list.sortedByDescending { it.dislikesCount }
        }

        adapter.submitList(list)
    }

    // ---------------- Votes (likes/dislikes + liked_palettes) ----------------

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

    private data class VoteResult(
        val likes: Long,
        val dislikes: Long,
        val userVote: Int,
        val ownerId: String
    )

    private fun handleVote(item: PublicFeedItem, isLike: Boolean) {
        val user = auth.currentUser ?: run {
            Toast.makeText(requireContext(), "Войдите, чтобы голосовать", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = user.uid
        val now = System.currentTimeMillis()

        val paletteRef = db.collection("color_palettes").document(item.id)
        val voteRef = paletteRef.collection("votes").document(uid)

        val likedRef = db.collection("users")
            .document(uid)
            .collection("liked_palettes")
            .document(item.id)

        db.runTransaction { tx ->
            val paletteSnap = tx.get(paletteRef)
            val ownerId = paletteSnap.getString("userId").orEmpty()

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

            // liked_palettes index: только если лайк
            if (newVote == 1) tx.set(likedRef, mapOf("createdAt" to now)) else tx.delete(likedRef)

            VoteResult(newLikes, newDislikes, newVote, ownerId)
        }.addOnSuccessListener { res ->
            fullList = fullList.map { old ->
                if (old.id == item.id) old.copy(
                    likesCount = res.likes,
                    dislikesCount = res.dislikes,
                    currentUserVote = res.userVote
                ) else old
            }
            applyFilters()

            // ✅ кэш обновляем после голоса тоже (чтобы оффлайн увидел актуальные цифры)
            saveFeedCache(fullList)
        }.addOnFailureListener { e ->
            Log.e("FEED", "vote error", e)
            Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Save & Share ----------------

    private fun saveToMyPalettes(item: PublicFeedItem) {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Войдите, чтобы сохранять", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()

        val data = hashMapOf<String, Any?>(
            "paletteName" to item.paletteName,
            "colors" to item.colors,
            "tags" to item.tags,
            "imageUri" to item.imageUri,
            "promptText" to item.description,
            "creationDate" to now,
            "userId" to uid,
            "isPublic" to false,
            "likesCount" to 0L,
            "dislikesCount" to 0L
        )

        db.collection("color_palettes")
            .add(data)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Сохранено в мои палитры", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

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

    // ---------------- Offline banner + Network ----------------

    private fun showOfflineBanner(show: Boolean) {
        // В layout должен быть offlineBanner (например TextView/MaterialCardView)
        try {
            binding.offlineBanner.visibility = if (show) View.VISIBLE else View.GONE
        } catch (_: Exception) {
            // если ты ещё не добавила offlineBanner в xml — просто игнорируем
        }
    }

    private fun hasInternet(): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val nc = cm.getNetworkCapabilities(nw) ?: return false
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun observeNetwork() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requireActivity().runOnUiThread {
                    if (lastInternetState == true) return@runOnUiThread
                    lastInternetState = true
                    showOfflineBanner(false)
                    loadPublicPalettes()
                }
            }

            override fun onLost(network: Network) {
                requireActivity().runOnUiThread {
                    if (lastInternetState == false) return@runOnUiThread
                    lastInternetState = false
                    showOfflineBanner(true)
                    if (fullList.isEmpty()) loadFromCacheOrEmpty()
                }
            }
        }

        try {
            cm.registerNetworkCallback(req, networkCallback!!)
        } catch (e: Exception) {
            Log.e("FEED", "registerNetworkCallback error", e)
        }
    }

    private fun unregisterNetwork() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        } finally {
            networkCallback = null
        }
    }

    override fun onDestroyView() {
        mentionRunnable?.let { uiHandler.removeCallbacks(it) }
        mentionRunnable = null
        super.onDestroyView()
        _binding = null
    }
}
