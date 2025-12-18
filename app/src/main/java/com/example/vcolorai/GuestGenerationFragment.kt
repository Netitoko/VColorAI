package com.example.vcolorai

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.example.vcolorai.databinding.FragmentGuestGenerationBinding
import com.example.vcolorai.generation.PaletteGenerator
import com.example.vcolorai.ui.common.BaseFragment
import com.example.vcolorai.ui.common.FullscreenImageDialog
import com.example.vcolorai.ui.dialogs.LoadingDialog
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class GuestGenerationFragment : BaseFragment() {

    private var _binding: FragmentGuestGenerationBinding? = null
    private val binding get() = _binding!!

    private lateinit var loadingDialog: LoadingDialog

    private var colorKeywords: Map<String, String> = emptyMap()
    private var currentPaletteIndex = 0

    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null

    // -------------------- GALLERY --------------------

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                selectedImageUri = uri
                showPreviewChip(uri)
            }
        }

    // -------------------- CAMERA --------------------

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openCameraInternal()
            } else {
                Toast.makeText(requireContext(), "Нет доступа к камере", Toast.LENGTH_SHORT).show()
            }
        }

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) {
                selectedImageUri = cameraImageUri
                showPreviewChip(cameraImageUri!!)
            }
        }

    // -------------------- LIFECYCLE --------------------

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGuestGenerationBinding.inflate(inflater, container, false)

        loadingDialog = LoadingDialog(requireContext())
        loadColorKeywords()

        // ✅ кнопка "Войти" (в правом верхнем углу)
        binding.btnLogin.visibility = View.VISIBLE
        binding.btnLogin.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish() // чтобы не возвращаться назад в гостевой режим кнопкой Back
        }

        // 📂 галерея
        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        // 📸 камера
        binding.btnTakePhoto.setOnClickListener {
            openCamera()
        }

        // очистка выбранной картинки
        binding.btnClearImage.setOnClickListener {
            selectedImageUri = null
            cameraImageUri = null
            binding.previewChipContainer.visibility = View.GONE
        }

        // генерация
        binding.btnGenerate.setOnClickListener {
            generate()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ✅ добавляем нижний inset ТОЛЬКО для inputContainer, чтобы он не залезал под жестовую панель
    override fun applyInsets(root: View) {
        val initialTop = root.paddingTop
        val initialInputBottom = binding.inputContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            v.updatePadding(top = initialTop + statusBars.top)

            binding.inputContainer.updatePadding(
                bottom = initialInputBottom + navBars.bottom
            )

            insets
        }
    }

    // -------------------- CAMERA LOGIC --------------------

    private fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            openCameraInternal()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraInternal() {
        try {
            val dir = File(requireContext().externalCacheDir, "camera")
            if (!dir.exists()) dir.mkdirs()

            val file = File.createTempFile("camera_", ".jpg", dir)

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            cameraImageUri = uri
            takePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Камера: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------- GENERATION --------------------

    private fun generate() {
        val text = binding.etPrompt.text.toString().trim()
        val hasText = text.isNotEmpty()
        val imageUriSnapshot = selectedImageUri
        val hasImage = imageUriSnapshot != null

        if (!hasText && !hasImage) {
            Toast.makeText(requireContext(), "Введите описание или выберите изображение", Toast.LENGTH_SHORT).show()
            return
        }

        clearAllResults()
        loadingDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when {
                    // ---------- TEXT MODE ----------
                    !hasImage && hasText -> {
                        val promptNormalized = text.lowercase()

                        if (!PaletteGenerator.hasInternet(requireContext()) &&
                            promptNormalized.matches(Regex(".*[А-Яа-я].*"))
                        ) {
                            Toast.makeText(
                                requireContext(),
                                "Оффлайн режим поддерживает только английский язык",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }

                        val palettes = mutableListOf<List<String>>()
                        repeat(3) {
                            val onlinePalette =
                                if (PaletteGenerator.hasInternet(requireContext())) {
                                    PaletteGenerator.generateOnlinePalette(promptNormalized)
                                } else null

                            val finalPalette =
                                if (!onlinePalette.isNullOrEmpty()) onlinePalette.take(6)
                                else generateSmartPalette(promptNormalized)

                            palettes.add(finalPalette)
                        }

                        enterTextMode()
                        showMultiplePalettes(palettes)
                        binding.etPrompt.text?.clear()
                    }

                    // ---------- IMAGE MODE ----------
                    hasImage && !hasText -> {
                        // прячем чип, чтобы не путать
                        selectedImageUri = null
                        binding.previewChipContainer.visibility = View.GONE

                        val uri = imageUriSnapshot!!
                        enterImageMode(uri)

                        val colors = generatePaletteFromImage(uri)
                        showImagePalette(colors)

                        binding.etPrompt.text?.clear()
                    }

                    // ---------- COMBINED MODE ----------
                    hasImage && hasText -> {
                        selectedImageUri = null
                        binding.previewChipContainer.visibility = View.GONE

                        val uri = imageUriSnapshot!!
                        enterImageMode(uri)

                        val base = generatePaletteFromImage(uri)
                        val modified = applyTextModifiersToPalette(base, text)

                        showImagePalette(modified)
                        binding.etPrompt.text?.clear()
                    }
                }
            } finally {
                loadingDialog.dismiss()
            }
        }
    }

    // --------- Тема (dark / light) ---------

    private fun isDarkTheme(): Boolean {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    // --------- Загрузка словаря ---------

    private fun loadColorKeywords() {
        try {
            val inputStream = requireContext().assets.open("color_keywords.json")
            val jsonString = BufferedReader(inputStream.reader()).use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            val map = mutableMapOf<String, String>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                map[obj.getString("keyword")] = obj.getString("hex")
            }
            colorKeywords = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --------- Генерация палитры по тексту (offline) ---------

    private fun generateSmartPalette(text: String): List<String> {
        val words = text.split(" ", "-", "_")
        val baseColors = mutableListOf<Int>()
        val modifiers = mutableListOf<String>()
        val moods = mutableListOf<String>()

        for (word in words) {
            when {
                colorKeywords.containsKey(word) -> baseColors.add(Color.parseColor(colorKeywords[word]))
                word in listOf("dark", "light", "bright", "dim", "night", "morning", "sunset", "dawn") -> modifiers.add(word)
                word in listOf("warm", "cool", "cold", "soft", "vivid", "calm", "deep", "muted") -> moods.add(word)
            }
        }

        if (baseColors.isEmpty()) return generateVariations(Color.parseColor("#808080"))

        val avgColor = averageColor(baseColors)
        var modified = applyLightModifier(avgColor, modifiers)
        modified = applyMoodModifier(modified, moods)

        return generateVariations(modified)
    }

    private fun applyLightModifier(color: Int, mods: List<String>): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        for (m in mods) when (m) {
            "light", "morning", "dawn" -> hsv[2] = min(1f, hsv[2] + 0.25f)
            "dark", "night" -> hsv[2] = max(0f, hsv[2] - 0.25f)
            "bright" -> hsv[1] = min(1f, hsv[1] + 0.25f)
            "dim", "sunset" -> hsv[1] = max(0f, hsv[1] - 0.2f)
        }
        return Color.HSVToColor(hsv)
    }

    private fun applyMoodModifier(color: Int, moods: List<String>): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        for (m in moods) when (m) {
            "warm" -> hsv[0] = (hsv[0] + 15) % 360
            "cool", "cold" -> hsv[0] = (hsv[0] - 15 + 360) % 360
            "soft", "muted" -> hsv[1] = max(0f, hsv[1] - 0.2f)
            "vivid", "deep" -> hsv[1] = min(1f, hsv[1] + 0.2f)
            "calm" -> {
                hsv[1] = max(0f, hsv[1] - 0.15f)
                hsv[2] = min(1f, hsv[2] + 0.1f)
            }
        }
        return Color.HSVToColor(hsv)
    }

    private fun averageColor(colors: List<Int>): Int {
        val r = colors.sumOf { Color.red(it) }
        val g = colors.sumOf { Color.green(it) }
        val b = colors.sumOf { Color.blue(it) }
        val n = colors.size
        return Color.rgb(r / n, g / n, b / n)
    }

    private fun generateVariations(baseColor: Int, count: Int = 6): List<String> {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)

        val result = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = count * 6

        while (result.size < count && attempts < maxAttempts) {
            attempts++

            val newH = (hsv[0] + Random.nextInt(-15, 16)).mod(360f)
            val newS = (hsv[1] + Random.nextFloat() * 0.25f - 0.12f).coerceIn(0f, 1f)
            val newV = (hsv[2] + Random.nextFloat() * 0.25f - 0.12f).coerceIn(0f, 1f)

            val color = Color.HSVToColor(floatArrayOf(newH, newS, newV))
            val hex = String.format("#%06X", 0xFFFFFF and color)
            result.add(hex)
        }

        if (result.size < count) {
            val baseHue = hsv[0]
            val s = hsv[1].coerceIn(0.3f, 0.9f)
            val v = hsv[2].coerceIn(0.3f, 0.9f)

            for (i in 0 until count) {
                if (result.size >= count) break
                val h = (baseHue + i * (360f / count)).mod(360f)
                val c = Color.HSVToColor(floatArrayOf(h, s, v))
                val hex = String.format("#%06X", 0xFFFFFF and c)
                result.add(hex)
            }
        }

        return result.toList()
    }

    // --------- UI (text mode) ---------

    private fun showMultiplePalettes(palettes: List<List<String>>) {
        val container = binding.paletteContainerTabs
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val padding = (8 * density).toInt()
        val colorHeight = (50 * density).toInt()
        val textColor = if (isDarkTheme()) Color.WHITE else Color.BLACK

        palettes.forEachIndexed { index, palette ->
            val paletteLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(padding, padding, padding, padding) }
                alpha = 0f
            }

            palette.forEach { hex ->
                val colorInt = try { Color.parseColor(hex) } catch (_: Exception) { Color.GRAY }

                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        colorHeight
                    ).apply { setMargins(0, padding / 2, 0, padding / 2) }
                }

                val colorView = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (60 * density).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * density
                        setColor(colorInt)
                    }
                }

                val codeView = TextView(requireContext()).apply {
                    text = hex
                    setTextColor(textColor)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#66000000"))
                        cornerRadius = 8 * density
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply { leftMargin = (8 * density).toInt() }

                    setOnClickListener { copyToClipboard(hex) }
                }

                row.addView(colorView)
                row.addView(codeView)
                paletteLayout.addView(row)
            }

            container.addView(paletteLayout)

            ObjectAnimator.ofFloat(paletteLayout, "alpha", 0f, 1f).apply {
                duration = 400
                startDelay = (index * 120).toLong()
                interpolator = DecelerateInterpolator()
                start()
            }
        }

        currentPaletteIndex = 0
        setupSwitchers(palettes.size)
        scrollToPalette(currentPaletteIndex)
    }

    private fun setupSwitchers(paletteCount: Int) {
        binding.btnPrevPalette.setOnClickListener {
            if (currentPaletteIndex > 0) currentPaletteIndex--
            scrollToPalette(currentPaletteIndex)
        }
        binding.btnNextPalette.setOnClickListener {
            if (currentPaletteIndex < paletteCount - 1) currentPaletteIndex++
            scrollToPalette(currentPaletteIndex)
        }
    }

    private fun scrollToPalette(index: Int) {
        val container = binding.paletteContainerTabs
        if (container.childCount <= index) return
        val child = container.getChildAt(index)
        binding.paletteScroll.smoothScrollTo(child.left, 0)
    }

    // --------- UI helpers ---------

    private fun showPreviewChip(uri: Uri) {
        binding.previewChipContainer.visibility = View.VISIBLE
        binding.ivPreviewChip.setImageURI(uri)
        if (binding.ivPreviewChip.drawable == null) {
            binding.ivPreviewChip.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    private fun clearAllResults() {
        binding.paletteContainer.removeAllViews()
        binding.paletteContainerTabs.removeAllViews()
        binding.imagePaletteContainer.removeAllViews()

        binding.ivPreview.setImageDrawable(null)
        binding.ivPreview.visibility = View.GONE

        binding.scrollPalette.visibility = View.GONE
        binding.paletteSwitcherContainer.visibility = View.GONE
        binding.imagePaletteScroll.visibility = View.GONE
    }

    private fun enterTextMode() {
        binding.scrollPalette.visibility = View.VISIBLE
        binding.paletteSwitcherContainer.visibility = View.VISIBLE
        binding.imagePaletteScroll.visibility = View.GONE
        binding.ivPreview.visibility = View.GONE
    }

    private fun enterImageMode(resultImageUri: Uri) {
        binding.scrollPalette.visibility = View.GONE
        binding.paletteSwitcherContainer.visibility = View.GONE

        binding.imagePaletteScroll.visibility = View.VISIBLE

        binding.ivPreview.visibility = View.VISIBLE
        binding.ivPreview.alpha = 0f
        binding.ivPreview.scaleX = 0.85f
        binding.ivPreview.scaleY = 0.85f

        binding.ivPreview.setImageURI(resultImageUri)
        if (binding.ivPreview.drawable == null) {
            binding.ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        binding.ivPreview.setOnClickListener {
            FullscreenImageDialog(requireContext(), resultImageUri).show()
        }

        binding.ivPreview.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun showImagePalette(colors: List<String>) {
        val container = binding.imagePaletteContainer
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val colorSize = (64 * density).toInt()
        val textTopMargin = (4 * density).toInt()
        val itemMargin = (8 * density).toInt()
        val textColor = if (isDarkTheme()) Color.WHITE else Color.BLACK

        colors.forEach { hex ->
            val colorInt = try { Color.parseColor(hex) } catch (_: Exception) { Color.GRAY }

            val itemLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(itemMargin, itemMargin, itemMargin, itemMargin) }
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val colorView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(colorSize, colorSize)
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(colorInt)
                }
            }

            val codeView = TextView(requireContext()).apply {
                text = hex
                setTextColor(textColor)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), textTopMargin, (8 * density).toInt(), 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#66000000"))
                    cornerRadius = 8 * density
                }
                setOnClickListener { copyToClipboard(hex) }
            }

            itemLayout.addView(colorView)
            itemLayout.addView(codeView)
            container.addView(itemLayout)
        }
    }

    // --------- Palette from image ---------

    private fun generatePaletteFromImage(uri: Uri): List<String> {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return generateVariations(Color.parseColor("#808080"))

            val targetWidth = 200
            val ratio = originalBitmap.height.toFloat() / originalBitmap.width.toFloat()
            val targetHeight = (targetWidth * ratio).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)

            val palette = Palette.from(bitmap)
                .maximumColorCount(16)
                .generate()

            val allSwatches = palette.swatches.sortedByDescending { it.population }
            if (allSwatches.isEmpty()) return generateVariations(Color.parseColor("#808080"))

            val baseHex = allSwatches
                .map { swatch -> String.format("#%06X", 0xFFFFFF and swatch.rgb) }
                .distinct()
                .toMutableList()

            if (baseHex.size >= 6) return baseHex.take(6)

            val dominantColor = allSwatches.first().rgb
            val extra = generateVariations(dominantColor)

            for (hex in extra) {
                if (!baseHex.contains(hex)) baseHex.add(hex)
                if (baseHex.size >= 6) break
            }

            if (baseHex.size >= 6) baseHex.take(6) else baseHex
        } catch (e: Exception) {
            e.printStackTrace()
            generateVariations(Color.parseColor("#808080"))
        }
    }

    // --------- Combined mode modifier ---------

    private fun applyTextModifiersToPalette(baseColorsHex: List<String>, text: String): List<String> {
        if (text.isBlank()) return baseColorsHex

        val words = text.lowercase().split(" ", "-", "_")
        val modifiers = mutableListOf<String>()
        val moods = mutableListOf<String>()

        for (word in words) {
            when {
                word in listOf("dark", "light", "bright", "dim", "night", "morning", "sunset", "dawn") -> modifiers.add(word)
                word in listOf("warm", "cool", "cold", "soft", "vivid", "calm", "deep", "muted") -> moods.add(word)
            }
        }

        if (modifiers.isEmpty() && moods.isEmpty()) return baseColorsHex

        val result = mutableListOf<String>()
        for (hex in baseColorsHex) {
            try {
                val baseColor = Color.parseColor(hex)
                var modified = applyLightModifier(baseColor, modifiers)
                modified = applyMoodModifier(modified, moods)
                result.add(String.format("#%06X", 0xFFFFFF and modified))
            } catch (_: Exception) {
            }
        }

        val unique = result.distinct()
        return if (unique.isNotEmpty()) unique else baseColorsHex
    }

    private fun copyToClipboard(hex: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("color", hex))
        Toast.makeText(requireContext(), "$hex скопирован", Toast.LENGTH_SHORT).show()
    }
}
