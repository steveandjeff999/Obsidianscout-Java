package com.obsidianscout.auth

import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.SmtpSettings
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

object EmailService {

    fun sendForgotPasswordEmail(to: String, username: String, teamNumber: Int, token: String, baseUrl: String) {
        val resetUrl = "${baseUrl}/reset-password?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val subject = "Reset your ObsidianScout Password"
        val greeting = if (teamNumber == -1) {
            "Hello <strong>$username</strong>,"
        } else {
            "Hello <strong>$username</strong> (Team $teamNumber),"
        }
        val body = """
            <html>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 8px;">
                    <h2 style="color: #4a5568; border-bottom: 2px solid #edf2f7; padding-bottom: 10px;">ObsidianScout Password Reset</h2>
                    <p>$greeting</p>
                    <p>We received a request to reset the password for your account. You can reset your password by clicking the link below:</p>
                    <p style="text-align: center; margin: 30px 0;">
                        <a href="$resetUrl" style="background-color: #4f46e5; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block;">Reset Password</a>
                    </p>
                    <p>This password reset link will expire in 1 hour.</p>
                    <p style="color: #718096; font-size: 14px; margin-top: 30px; border-top: 1px solid #edf2f7; padding-top: 15px;">
                        If you did not request a password reset, please ignore this email or contact your administrator.
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
        sendEmail(to, subject, body)
    }

    fun sendEmail(to: String, subject: String, body: String) {
        val settings = SettingsService.getSmtpSettings()
        sendEmailWithSettings(to, subject, body, settings)
    }

    fun sendEmailWithSettings(to: String, subject: String, body: String, settings: SmtpSettings) {
        if (settings.host.isBlank()) {
            throw IllegalStateException("SMTP host is not configured.")
        }

        val props = Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.connectiontimeout", "5000")
            put("mail.smtp.timeout", "5000")

            when (settings.encryption.uppercase()) {
                "SSL_TLS" -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", settings.port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                }
                "STARTTLS" -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
                else -> {
                    // Plain
                }
            }

            if (settings.username.isNotBlank()) {
                put("mail.smtp.auth", "true")
            }
        }

        val session = if (settings.username.isNotBlank()) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(settings.username, settings.passwordPlain)
                }
            })
        } else {
            Session.getInstance(props)
        }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.fromAddress.ifBlank { settings.username }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
            setContent(body, "text/html; charset=utf-8")
        }

        Transport.send(message)
    }
}
