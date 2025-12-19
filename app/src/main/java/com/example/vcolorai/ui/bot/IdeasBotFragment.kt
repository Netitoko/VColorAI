package com.example.vcolorai.ui.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vcolorai.databinding.FragmentIdeasBotBinding
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import com.example.vcolorai.ui.common.BaseFragment

class IdeasBotFragment : BaseFragment() {

    private var _binding: FragmentIdeasBotBinding? = null
    private val binding get() = _binding!!

    private enum class RequestType {
        TEXT_PROMPT,
        DESCRIPTION,
        PHOTO_IDEA,
        MIX_STYLES
    }

    private var currentType: RequestType? = null

    companion object {
        private const val YANDEX_API_KEY = ""
        private const val YANDEX_FOLDER_ID = ""
    }

    private val yandexGptApi: YandexGptApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder().addInterceptor(logging).build()

        Retrofit.Builder()
            .baseUrl("https://llm.api.cloud.yandex.net/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YandexGptApi::class.java)
    }

    // Чат
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIdeasBotBinding.inflate(inflater, container, false)

        setupChat()
        setupUi()

        return binding.root
    }

    override fun applyInsets(root: View) {
        val baseTop = binding.rootLayout.paddingTop
        val density = resources.displayMetrics.density

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.rootLayout.updatePadding(
                top = baseTop + systemBars.top + (8 * density).toInt()
            )

            insets
        }
    }

    // Чат

    private fun setupChat() {
        chatAdapter = ChatAdapter(messages) { text ->
            copyToClipboard(text)
            Toast.makeText(requireContext(), "Текст скопирован", Toast.LENGTH_SHORT).show()
        }

        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = chatAdapter
        }

        loadChatHistory()
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
        chatAdapter.notifyItemInserted(messages.size - 1)
        scrollChatToBottom()
        saveChatHistory()
    }

    private fun scrollChatToBottom() {
        binding.rvChat.post {
            if (messages.isNotEmpty())
                binding.rvChat.scrollToPosition(messages.size - 1)
        }
    }

    private fun saveChatHistory() {
        val prefs = requireContext().getSharedPreferences("ideas_bot_chat", Context.MODE_PRIVATE)
        val builder = StringBuilder()

        messages.forEach { msg ->
            builder.append(msg.isUser)
                .append("|||")
                .append(msg.text.replace("\n", "\\n"))
                .append("\n")
        }

        prefs.edit().putString("history_v1", builder.toString()).apply()
    }

    private fun loadChatHistory() {
        val prefs = requireContext().getSharedPreferences("ideas_bot_chat", Context.MODE_PRIVATE)
        val raw = prefs.getString("history_v1", null) ?: return

        messages.clear()

        raw.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split("|||")
            if (parts.size == 2) {
                val isUser = parts[0].toBoolean()
                val text = parts[1].replace("\\n", "\n")
                messages.add(ChatMessage(text, isUser))
            }
        }

        chatAdapter.notifyDataSetChanged()
        scrollChatToBottom()
    }

    // UI и отправка

    private fun setupUi() {
        binding.btnTypePrompt.setOnClickListener {
            currentType = RequestType.TEXT_PROMPT
            binding.tvModeHint.text =
                "Режим: идеи текстовых промптов.\nОтвет: EN + RU в чате."
        }

        binding.btnTypePhoto.setOnClickListener {
            currentType = RequestType.PHOTO_IDEA
            binding.tvModeHint.text =
                "Режим: идеи фотографий для палитр."
        }

        binding.btnTypeMix.setOnClickListener {
            currentType = RequestType.MIX_STYLES
            binding.tvModeHint.text =
                "Режим: смешивание стилей."
        }

        binding.btnSend.setOnClickListener { sendMessage() }
    }

    // Отправка сообщения

    private fun sendMessage() {
        val type = currentType ?: run {
            Toast.makeText(requireContext(), "Выберите тип запроса", Toast.LENGTH_SHORT).show()
            return
        }

        val userText = binding.etMessage.text.toString().trim()
        if (userText.isEmpty()) {
            Toast.makeText(requireContext(), "Введите сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        addMessage(userText, true)
        binding.etMessage.text?.clear()

        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.5f
        binding.tvModeHint.text = "Генерация ответа…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prompt = buildPrompt(type, userText)

                val answer = withContext(Dispatchers.IO) {
                    callYandexGpt(prompt)
                }

                when (type) {
                    RequestType.TEXT_PROMPT,
                    RequestType.DESCRIPTION -> {
                        val pairs = parsePromptPairs(answer)
                        if (pairs.isEmpty()) {
                            addMessage("Бот: не удалось обработать ответ", false)
                        } else {
                            addMessage("Бот: варианты:", false)
                            pairs.forEachIndexed { i, p ->
                                addMessage(
                                    "Вариант ${i + 1}\nEN: ${p.en}\nRU: ${p.ru}",
                                    false
                                )
                            }
                        }
                    }

                    RequestType.PHOTO_IDEA,
                    RequestType.MIX_STYLES -> {
                        addMessage("Бот: $answer", false)
                    }
                }

            } catch (e: Exception) {
                addMessage("Бот: ошибка — ${e.message}", false)
            } finally {
                binding.btnSend.isEnabled = true
                binding.btnSend.alpha = 1f
                binding.tvModeHint.text = "Можете задать новый вопрос"
                scrollChatToBottom()
            }
        }
    }

    // Построение промпта

    private fun buildPrompt(type: RequestType, userText: String): String =
        when (type) {
            RequestType.TEXT_PROMPT -> """
                Сгенерируй 3–5 промптов в формате:

                Prompt (EN): ...
                Translate (RU): ...

                Тема:
                $userText
            """.trimIndent()

            RequestType.DESCRIPTION -> """
                Сгенерируй 2–4 варианта:
                Prompt (EN): ...
                Translate (RU): ...

                Палитра:
                $userText
            """.trimIndent()

            RequestType.PHOTO_IDEA -> """
                Дай 3–5 идей фотографий:
                $userText
            """.trimIndent()

            RequestType.MIX_STYLES -> """
                Опиши 3 варианта палитры, смешивающей:
                $userText
            """.trimIndent()
        }

    // Вызов Yandex GPT

    private suspend fun callYandexGpt(prompt: String): String {
        val request = ChatCompletionRequest(
            model = "gpt://$YANDEX_FOLDER_ID/yandexgpt-lite/latest",
            messages = listOf(
                ChatMessageApi("system", "Ты помощник по палитрам."),
                ChatMessageApi("user", prompt)
            ),
            temperature = 0.3,
            maxTokens = 700
        )

        val response = yandexGptApi.getChatCompletion(
            authHeader = "Api-Key $YANDEX_API_KEY",
            project = YANDEX_FOLDER_ID,
            body = request
        )

        return response.choices.firstOrNull()?.message?.content?.trim()
            ?: "Пустой ответ"
    }

    // Парсер EN / RU

    data class PromptPair(val en: String, val ru: String)

    private fun parsePromptPairs(raw: String): List<PromptPair> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val list = mutableListOf<PromptPair>()
        var en: String? = null
        var ru: String? = null

        for (line in lines) {
            when {
                line.startsWith("Prompt (EN):", true) ->
                    en = line.substringAfter(":").trim()

                line.startsWith("Translate (RU):", true) ->
                    ru = line.substringAfter(":").trim()
            }

            if (en != null && ru != null) {
                list.add(PromptPair(en!!, ru!!))
                en = null
                ru = null
            }
        }

        return list
    }

    // Копирование текста
    private fun copyToClipboard(text: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("bot_text", text))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// API модели

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageApi>,
    val temperature: Double?,
    @SerializedName("max_tokens")
    val maxTokens: Int?
)

data class ChatMessageApi(
    val role: String,
    val content: String
)

data class ChatCompletionResponse(
    val choices: List<ChatChoice>
)

data class ChatChoice(
    val index: Int?,
    val message: ChatMessageApi
)

// Retrofit API
interface YandexGptApi {
    @POST("chat/completions")
    suspend fun getChatCompletion(
        @Header("Authorization") authHeader: String,
        @Header("OpenAI-Project") project: String,
        @Body body: ChatCompletionRequest
    ): ChatCompletionResponse
}
