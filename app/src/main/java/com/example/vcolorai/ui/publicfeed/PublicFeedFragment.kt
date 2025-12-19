package com.example.vcolorai.ui.publicfeed

import com.example.vcolorai.ui.common.VoteManager
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

    // Список всех загруженных палитр для фильтрации
    private var fullList: List<PublicFeedItem> = emptyList()

    // Кэш соответствия ID пользователя и его имени
    private val authorUsernameCache = mutableMapOf<String, String>()

    // Обработчики для отложенного поиска упоминаний
    private val uiHandler = Handler(Looper.getMainLooper())
    private var mentionRunnable: Runnable? = null
    private var lastMentionKey: String? = null
    private var lastShownUserId: String? = null

    // Режимы сортировки ленты
    private enum class SortMode {
        DATE_NEW, DATE_OLD,
        LIKES_DESC,
        SCORE_DESC,
        DISLIKES_DESC
    }

    // Параметры фильтрации контента
    private data class FeedFilters(
        var onlyWithImage: Boolean = false,
        var onlyText: Boolean = false,
        var onlyWithTags: Boolean = false,
        var onlyWithoutTags: Boolean = false
    )

    private var sortMode: SortMode = SortMode.DATE_NEW
    private var filters = FeedFilters()

    // Фильтр по конкретному тегу
    private var tagQueryFilter: String = ""

    // Кэширование данных для оффлайн-режима
    private val gson = Gson()
    private val prefs by lazy {
        requireContext().getSharedPreferences("feed_cache", Context.MODE_PRIVATE)
    }
    private val FEED_CACHE_KEY = "public_feed_v1"

    // Мониторинг состояния сети
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

        // Инициализация данных с учетом сетевого соединения
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

    // Настройка RecyclerView для отображения ленты
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

    // Настройка поиска с обработкой упоминаний через @
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

    // Обработка ввода @ для поиска пользователей
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

    // Поиск пользователя по имени и открытие его профиля
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

    // Настройка кнопки фильтров и сортировки
    private fun setupFilterMenu() {
        binding.btnFilter.setOnClickListener { showFilterSortMenu() }
    }

    // Отображение меню с опциями сортировки и фильтрации
    private fun showFilterSortMenu() {
        val popup = PopupMenu(requireContext(), binding.btnFilter)
        val menu = popup.menu

        // Опции сортировки
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

        // Опции фильтрации
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

    // Форматирование названия пункта меню с галочкой
    private fun toggleTitle(enabled: Boolean, title: String): String {
        return if (enabled) "✓ $title" else title
    }

    // Диалог для ввода тега фильтрации
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

    // Загрузка публичных палитр из Firestore
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
                    saveFeedCache(fullList)
                    applyFilters()
                }
            }
            .addOnFailureListener { e ->
                Log.e("FEED", "load feed error", e)

                if (!hasInternet()) {
                    showOfflineBanner(true)
                    loadFromCacheOrEmpty()
                } else {
                    Toast.makeText(requireContext(), "Ошибка загрузки ленты: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // Предзагрузка имен авторов для отображения в ленте
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

    // Сохранение ленты в локальное хранилище
    private fun saveFeedCache(list: List<PublicFeedItem>) {
        try {
            val json = gson.toJson(list)
            prefs.edit().putString(FEED_CACHE_KEY, json).apply()
        } catch (e: Exception) {
            Log.e("FEED", "cache save error", e)
        }
    }

    // Загрузка ленты из локального хранилища
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

    // Загрузка данных из кэша при отсутствии сети
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

    // Применение фильтров и сортировки к данным
    private fun applyFilters() {
        val queryRaw = binding.etSearch.text?.toString().orEmpty().trim()
        val query = queryRaw.lowercase()

        var list = fullList

        // Поиск по тексту
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

        // Фильтры по типу контента
        if (filters.onlyWithImage) {
            list = list.filter { !it.imageUri.isNullOrBlank() }
        }
        if (filters.onlyText) {
            list = list.filter { it.imageUri.isNullOrBlank() }
        }

        // Фильтры по тегам
        if (filters.onlyWithTags) {
            list = list.filter { it.tags.isNotEmpty() }
        }
        if (filters.onlyWithoutTags) {
            list = list.filter { it.tags.isEmpty() }
        }

        // Фильтр по конкретному тегу
        val tagQ = tagQueryFilter.trim().lowercase()
        if (tagQ.isNotEmpty()) {
            list = list.filter { item ->
                item.tags.any { it.lowercase().contains(tagQ) }
            }
        }

        // Применение сортировки
        list = when (sortMode) {
            SortMode.DATE_NEW -> list.sortedByDescending { it.createdAt }
            SortMode.DATE_OLD -> list.sortedBy { it.createdAt }
            SortMode.LIKES_DESC -> list.sortedByDescending { it.likesCount }
            SortMode.SCORE_DESC -> list.sortedByDescending { (it.likesCount - it.dislikesCount) }
            SortMode.DISLIKES_DESC -> list.sortedByDescending { it.dislikesCount }
        }

        adapter.submitList(list)
    }

    // Заполнение информации о голосах текущего пользователя
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

    // Обработка лайков и дизлайков
    private fun handleVote(item: PublicFeedItem, isLike: Boolean) {
        val user = auth.currentUser ?: run {
            Toast.makeText(requireContext(), "Войдите, чтобы голосовать", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = user.uid
        val now = System.currentTimeMillis()

        val likedRef = db.collection("users")
            .document(uid)
            .collection("liked_palettes")
            .document(item.id)

        VoteManager.vote(
            db = db,
            auth = auth,
            paletteId = item.id,
            paletteName = item.paletteName,
            isLike = isLike,
            onSuccess = { res ->
                fullList = fullList.map {
                    if (it.id == item.id) it.copy(
                        likesCount = res.likes,
                        dislikesCount = res.dislikes,
                        currentUserVote = res.userVote
                    ) else it
                }
                applyFilters()

                if (res.userVote == 1) {
                    likedRef.set(mapOf("createdAt" to now))
                } else {
                    likedRef.delete()
                }
            },
            onError = { e ->
                Log.e("FEED", "vote error", e)
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Сохранение палитры в коллекцию пользователя
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

    // Поделиться палитрой через другие приложения
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

    // Отображение/скрытие баннера оффлайн-режима
    private fun showOfflineBanner(show: Boolean) {
        try {
            binding.offlineBanner.visibility = if (show) View.VISIBLE else View.GONE
        } catch (_: Exception) {
        }
    }

    // Проверка наличия интернет-соединения
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

    // Мониторинг изменений состояния сети
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

    // Отмена регистрации сетевого коллбэка
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