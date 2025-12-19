package com.example.vcolorai.ui.settings

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.vcolorai.databinding.DialogUserStatsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import android.view.ViewTreeObserver

class UserStatsDialogFragment : DialogFragment() {

    private var _binding: DialogUserStatsBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // Вспомогательная структура для хранения данных палитры
    private data class PaletteDoc(
        val isPublic: Boolean,
        val likes: Int,
        val dislikes: Int,
        val createdAtMillis: Long
    )

    private var cachedDocs: List<PaletteDoc> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogUserStatsBinding.inflate(LayoutInflater.from(requireContext()))

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Моя активность")
            .setView(binding.root)
            .setPositiveButton("Закрыть", null)
            .create()

        loadStats()
        return dialog
    }

    // Загрузка статистики пользователя из Firestore
    private fun loadStats() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "Нет авторизации", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
            return
        }

        binding.progress.visibility = View.VISIBLE
        binding.contentGroup.visibility = View.GONE

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

                cachedDocs = docs

                val total = docs.size
                val publicCount = docs.count { it.isPublic }
                val privateCount = total - publicCount
                val likesTotal = docs.sumOf { it.likes }
                val dislikesTotal = docs.sumOf { it.dislikes }

                binding.tvTotalPalettes.text = total.toString()
                binding.tvLikesTotal.text = likesTotal.toString()

                binding.tvPublicBarValue.text = publicCount.toString()
                binding.tvPrivateBarValue.text = privateCount.toString()
                binding.tvLikesBarValue.text = likesTotal.toString()
                binding.tvDislikesBarValue.text = dislikesTotal.toString()

                animateHorizontalBars(
                    publicCount = publicCount,
                    privateCount = privateCount,
                    likesTotal = likesTotal,
                    dislikesTotal = dislikesTotal
                )

                // Визуализация графика активности за последние 7 дней
                renderDailyChart7()

                binding.progress.visibility = View.GONE
                binding.contentGroup.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                binding.progress.visibility = View.GONE
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Анимация горизонтальных индикаторов статистики
    private fun animateHorizontalBars(
        publicCount: Int,
        privateCount: Int,
        likesTotal: Int,
        dislikesTotal: Int
    ) {
        val maxPubPriv = max(1, max(publicCount, privateCount))
        val maxLikeDislike = max(1, max(likesTotal, dislikesTotal))

        animateScaleX(binding.barPublic, publicCount.toFloat() / maxPubPriv)
        animateScaleX(binding.barPrivate, privateCount.toFloat() / maxPubPriv)

        animateScaleX(binding.barLikes, likesTotal.toFloat() / maxLikeDislike)
        animateScaleX(binding.barDislikes, dislikesTotal.toFloat() / maxLikeDislike)
    }

    // Анимация изменения масштаба по оси X
    private fun animateScaleX(view: View, ratio: Float) {
        val target = max(0.02f, ratio)
        view.post {
            view.pivotX = 0f
            view.scaleX = 0.02f
            view.animate()
                .scaleX(target)
                .setDuration(650)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // Создание и отображение гистограммы за 7 дней
    private fun renderDailyChart7() {
        val days = 7
        val counts = buildDailyCounts(days)
        val maxValue = max(1, counts.maxOrNull() ?: 1)

        binding.daysBarsContainer.removeAllViews()

        val barWidthDp = 34
        val itemPaddingDp = 8
        val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())

        // Ожидание полной отрисовки контейнера для расчета размеров
        val vto = binding.hsDays.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.hsDays.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val containerH = binding.hsDays.height
                if (containerH <= 0) {
                    Toast.makeText(requireContext(), "Высота графика = 0, проверь layout", Toast.LENGTH_SHORT).show()
                    return
                }

                val topReserve = dpToPx(28)
                val bottomReserve = dpToPx(24)
                val extraSafety = dpToPx(10)

                val maxBarHeightPx = max(1, containerH - topReserve - bottomReserve - extraSafety)

                for (i in 0 until days) {
                    val value = counts[i]
                    val padPx = dpToPx(itemPaddingDp)

                    val item = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        setPadding(padPx, padPx, padPx, padPx)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val tvTop = TextView(requireContext()).apply {
                        text = value.toString()
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        visibility = View.INVISIBLE
                    }

                    val bar = View(requireContext()).apply {
                        layoutParams = ViewGroup.LayoutParams(dpToPx(barWidthDp), maxBarHeightPx)
                        setBackgroundColor(0xFF90CAF9.toInt())
                        scaleY = 0.05f
                    }

                    val dayDate = dayStartMillisToDate(days, i)
                    val tvBottom = TextView(requireContext()).apply {
                        text = sdf.format(dayDate)
                        textSize = 10f
                        setTextColor(0xFF777777.toInt())
                        setPadding(0, dpToPx(6), 0, 0)
                    }

                    item.addView(tvTop)
                    item.addView(bar)
                    item.addView(tvBottom)

                    binding.daysBarsContainer.addView(item)

                    bar.post {
                        bar.pivotY = bar.height.toFloat()
                        val target = max(0.05f, value.toFloat() / maxValue)

                        bar.scaleY = 0.05f
                        bar.animate()
                            .scaleY(target)
                            .setDuration(650)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction { tvTop.visibility = View.VISIBLE }
                            .start()
                    }
                }
            }
        })
    }

    // Подсчет количества палитр по дням
    private fun buildDailyCounts(days: Int): IntArray {
        val counts = IntArray(days)

        val cal = Calendar.getInstance()
        setToStartOfDay(cal)
        val startToday = cal.timeInMillis

        for (doc in cachedDocs) {
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

    // Приведение времени к началу дня
    private fun startOfDayMillis(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        setToStartOfDay(cal)
        return cal.timeInMillis
    }

    // Сброс времени в календаре до начала дня
    private fun setToStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    // Получение даты для определенного индекса в гистограмме
    private fun dayStartMillisToDate(days: Int, index: Int): Date {
        val cal = Calendar.getInstance()
        setToStartOfDay(cal)
        val diff = (days - 1) - index
        cal.add(Calendar.DAY_OF_YEAR, -diff)
        return cal.time
    }

    // Конвертация dp в пиксели
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}