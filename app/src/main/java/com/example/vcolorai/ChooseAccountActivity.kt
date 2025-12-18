package com.example.vcolorai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.vcolorai.databinding.ActivityChooseAccountBinding
import com.google.firebase.auth.FirebaseAuth

class ChooseAccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChooseAccountBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        val prefs = getSharedPreferences("local_users", Context.MODE_PRIVATE)
        val allUsers = prefs.all  // Map<String, Any>
        val userList = allUsers.values.map { it.toString() }
        val uidList = allUsers.keys.toList()

        if (userList.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, userList)
        binding.listViewAccounts.adapter = adapter

        binding.listViewAccounts.setOnItemClickListener { _, _, position, _ ->
            val selectedUid = uidList[position]
            val selectedEmail = userList[position]

            val currentUser = auth.currentUser

            // 🔍 Проверяем, если этот пользователь уже авторизован
            if (currentUser != null && currentUser.uid == selectedUid) {
                // Уже вошёл — просто идём в MainActivity
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                // Пользователь не авторизован — передаём email в LoginActivity
                val intent = Intent(this, LoginActivity::class.java)
                intent.putExtra("selectedEmail", selectedEmail)
                startActivity(intent)
                finish()
            }
        }

        binding.btnAddNewAccount.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
