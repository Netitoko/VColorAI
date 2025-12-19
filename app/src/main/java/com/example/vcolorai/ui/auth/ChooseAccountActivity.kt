package com.example.vcolorai.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.vcolorai.data.MainActivity
import com.example.vcolorai.databinding.ActivityChooseAccountBinding
import com.google.firebase.auth.FirebaseAuth

class ChooseAccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChooseAccountBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: ArrayAdapter<String>
    private var userList = mutableListOf<String>()
    private var uidList = mutableListOf<String>()
    private var isSelectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        loadAccounts()

        binding.btnDeletePalettes.setOnClickListener {
            if (isSelectionMode) {
                deleteSelectedAccounts()
            } else {
                enterSelectionMode()
            }
        }

        binding.btnAddNewAccount.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // Загрузка сохраненных аккаунтов из локального хранилища
    private fun loadAccounts() {
        val prefs = getSharedPreferences("local_users", Context.MODE_PRIVATE)
        val allUsers = prefs.all

        userList.clear()
        uidList.clear()

        userList.addAll(allUsers.values.map { it.toString() })
        uidList.addAll(allUsers.keys.toList())

        if (userList.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, userList)
        binding.listViewAccounts.adapter = adapter
        binding.listViewAccounts.choiceMode = android.widget.AbsListView.CHOICE_MODE_MULTIPLE

        binding.listViewAccounts.setOnItemClickListener { _, _, position, _ ->
            if (isSelectionMode) {
                updateSelectionInfo()
            } else {
                val selectedUid = uidList[position]
                val selectedEmail = userList[position]
                val currentUser = auth.currentUser

                if (currentUser != null && currentUser.uid == selectedUid) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.putExtra("selectedEmail", selectedEmail)
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    // Вход в режим множественного выбора для удаления
    private fun enterSelectionMode() {
        isSelectionMode = true
        binding.tvSelectionInfo.visibility = android.view.View.VISIBLE
        binding.tvSelectionInfo.text = "Выбрано: 0"
        binding.listViewAccounts.choiceMode = android.widget.AbsListView.CHOICE_MODE_MULTIPLE

        // Сброс предыдущих выделений
        for (i in 0 until binding.listViewAccounts.count) {
            binding.listViewAccounts.setItemChecked(i, false)
        }

        Toast.makeText(this, "Режим выбора: нажмите на элементы для выбора", Toast.LENGTH_SHORT).show()
    }

    // Выход из режима выбора
    private fun exitSelectionMode() {
        isSelectionMode = false
        binding.tvSelectionInfo.visibility = android.view.View.GONE
        binding.listViewAccounts.choiceMode = android.widget.AbsListView.CHOICE_MODE_SINGLE

        // Сброс всех выделений
        for (i in 0 until binding.listViewAccounts.count) {
            binding.listViewAccounts.setItemChecked(i, false)
        }
    }

    // Обновление счетчика выбранных элементов
    private fun updateSelectionInfo() {
        val selectedCount = binding.listViewAccounts.checkedItemCount
        binding.tvSelectionInfo.text = "Выбрано: $selectedCount"

        if (selectedCount == 0) {
            exitSelectionMode()
        }
    }

    // Удаление выбранных аккаунтов
    private fun deleteSelectedAccounts() {
        val checkedPositions = mutableListOf<Int>()
        val checkedItems = mutableListOf<String>()

        for (i in 0 until binding.listViewAccounts.count) {
            if (binding.listViewAccounts.isItemChecked(i)) {
                checkedPositions.add(i)
                checkedItems.add(userList[i])
            }
        }

        if (checkedItems.isEmpty()) {
            Toast.makeText(this, "Не выбрано ни одного аккаунта", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            return
        }

        val itemsText = if (checkedItems.size > 1) {
            "${checkedItems.size} аккаунтов"
        } else {
            "аккаунт '${checkedItems.first()}'"
        }

        AlertDialog.Builder(this)
            .setTitle("Подтверждение удаления")
            .setMessage("Вы уверены, что хотите удалить $itemsText?")
            .setPositiveButton("Удалить") { _, _ ->
                performDeletion(checkedPositions)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Фактическое удаление аккаунтов из хранилища
    private fun performDeletion(positions: List<Int>) {
        val prefs = getSharedPreferences("local_users", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Удаление в обратном порядке для сохранения корректности индексов
        val sortedPositions = positions.sortedDescending()

        sortedPositions.forEach { position ->
            if (position < uidList.size) {
                val uidToRemove = uidList[position]
                editor.remove(uidToRemove)

                // Выход из аккаунта, если удаляется текущий пользователь
                val currentUser = auth.currentUser
                if (currentUser?.uid == uidToRemove) {
                    auth.signOut()
                }
            }
        }

        editor.apply()

        Toast.makeText(this, "Удалено аккаунтов: ${positions.size}", Toast.LENGTH_SHORT).show()

        // Обновление списка аккаунтов
        loadAccounts()
        exitSelectionMode()
    }

    override fun onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }
}