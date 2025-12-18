package com.example.vcolorai

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.vcolorai.databinding.ActivityMainBinding
import com.example.vcolorai.GenerationFragment
import com.example.vcolorai.ui.ProfileFragment
import com.example.vcolorai.ui.bot.IdeasBotFragment
import com.example.vcolorai.ui.palettes.PalettesFragment
import com.example.vcolorai.ui.publicfeed.PublicFeedFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    companion object {
        private const val KEY_SELECTED_NAV = "selected_nav_item"
        private const val TAG = "MAIN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ ВКЛЮЧАЕМ логирование Firestore (для поиска PERMISSION_DENIED)
        FirebaseFirestore.setLoggingEnabled(true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ МИГРАЦИЯ/ПОДДЕРЖКА УНИКАЛЬНОСТИ НИКОВ ДЛЯ СТАРЫХ АККАУНТОВ
        ensureUsernameIndex()

        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_generation -> openFragment(GenerationFragment())
                R.id.nav_feed -> openFragment(PublicFeedFragment())
                R.id.nav_palettes -> openFragment(PalettesFragment())
                R.id.nav_ideas_bot -> openFragment(IdeasBotFragment())
                R.id.nav_profile -> openFragment(ProfileFragment())
            }
            true
        }

        if (savedInstanceState != null) {
            val restoredId = savedInstanceState.getInt(KEY_SELECTED_NAV, R.id.nav_generation)
            binding.bottomNavigationView.menu.findItem(restoredId)?.isChecked = true
            return
        }

        binding.bottomNavigationView.selectedItemId = R.id.nav_generation
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED_NAV, binding.bottomNavigationView.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun openFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // ---------------- Username unique index migration ----------------

    private fun usernameKey(username: String): String = username.trim().lowercase()

    private fun isUsernameValidForIndex(username: String): Boolean {
        if (username.isBlank()) return false
        // раз мы запрещаем пробелы в нике — индексируем только корректные
        if (username.any { it.isWhitespace() }) return false
        return true
    }

    private fun ensureUsernameIndex() {
        val uid = auth.currentUser?.uid ?: return

        fun tryIndex(username: String) {
            val u = username.trim()
            if (!isUsernameValidForIndex(u)) return

            val key = usernameKey(u)
            val ref = db.collection("usernames").document(key)

            ref.get()
                .addOnSuccessListener { snap ->
                    if (!snap.exists()) {
                        // индекса нет -> создаём (занимаем ник)
                        ref.set(mapOf("uid" to uid, "createdAt" to System.currentTimeMillis()))
                            .addOnFailureListener { e ->
                                Log.e(TAG, "ensureUsernameIndex set error", e)
                            }
                    } else {
                        val owner = snap.getString("uid").orEmpty()
                        if (owner.isNotBlank() && owner != uid) {
                            // конфликт: ник уже принадлежит другому пользователю
                            Toast.makeText(
                                this,
                                "Внимание: ваш ник уже занят другим пользователем. Поменяйте ник в профиле.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ensureUsernameIndex get error", e)
                }
        }

        // 1) пробуем взять username из users/{uid}
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username")?.trim().orEmpty()
                if (username.isNotBlank()) {
                    tryIndex(username)
                } else {
                    // 2) fallback: public_users/{uid}
                    db.collection("public_users").document(uid).get()
                        .addOnSuccessListener { pdoc ->
                            val uname = pdoc.getString("username")?.trim().orEmpty()
                            if (uname.isNotBlank()) tryIndex(uname)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "ensureUsernameIndex public_users error", e)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ensureUsernameIndex users error", e)
            }
    }
}
