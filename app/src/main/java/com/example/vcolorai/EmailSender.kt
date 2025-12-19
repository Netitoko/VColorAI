package com.example.vcolorai

import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailSender {

    fun sendEmail(
        to: String,
        subject: String,
        message: String,
        callback: (Boolean, String?) -> Unit
    ) {
        Thread {
            try {
                val props = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "587")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(
                            "vincentcolorai@gmail.com", // Учетные данные Gmail аккаунта
                            "qqoz tpkf eoam xceg"      // Пароль приложения (App Password)
                        )
                    }
                })

                val msg = MimeMessage(session).apply {
                    setFrom(InternetAddress("vincentcolorai@gmail.com"))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    setSubject(subject, "UTF-8")
                    setText(message, "UTF-8")
                }

                Transport.send(msg)
                callback(true, null)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(false, e.message)
            }
        }.start()
    }
}