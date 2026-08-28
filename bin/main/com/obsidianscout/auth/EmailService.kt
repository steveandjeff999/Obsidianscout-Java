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

    fun sendForgotPasswordEmail(to: String, username: String, teamNumber: Int, token: String, baseUrl: String, isApp: Boolean = false) {
        val resetUrl = "${baseUrl}/reset-password?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val subject = "Reset your ObsidianScout Password"
        val greeting = if (teamNumber == -1) {
            "Hello <strong>$username</strong>,"
        } else {
            "Hello <strong>$username</strong> (Team $teamNumber),"
        }
        val appTokenSection = if (isApp) {
            """
            <div style="background: #0f172a; border: 1px solid #38bdf8; border-radius: 8px; padding: 20px; margin: 24px 0; text-align: center;">
                <p style="margin: 0 0 8px 0; font-size: 13px; color: #94a3b8; font-weight: bold; text-transform: uppercase; letter-spacing: 1.5px;">ObsidianScout App Reset Token</p>
                <div style="font-family: Consolas, 'Courier New', monospace; font-size: 22px; font-weight: bold; color: #38bdf8; letter-spacing: 2px; word-break: break-all; user-select: all; padding: 8px; background: rgba(56, 189, 248, 0.1); border-radius: 4px;">$token</div>
                <p style="margin: 10px 0 0 0; font-size: 12px; color: #cbd5e1;">Copy and paste this token into the ObsidianScout mobile app.</p>
            </div>
            """.trimIndent()
        } else {
            """
            <div style="background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 16px; margin: 20px 0; text-align: center;">
                <p style="margin: 0 0 6px 0; font-size: 12px; color: #64748b; font-weight: bold; text-transform: uppercase; letter-spacing: 1px;">Reset Token</p>
                <div style="font-family: monospace; font-size: 16px; font-weight: bold; color: #334155; letter-spacing: 1px; word-break: break-all;">$token</div>
            </div>
            """.trimIndent()
        }
        val body = """
            <html>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; color: #1e293b; background-color: #f1f5f9; padding: 20px 0;">
                <div style="max-width: 600px; margin: 0 auto; padding: 32px; background-color: #ffffff; border: 1px solid #e2e8f0; border-radius: 12px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);">
                    <div style="display: flex; align-items: center; margin-bottom: 20px; border-bottom: 2px solid #f1f5f9; padding-bottom: 16px;">
                        <h2 style="color: #0f172a; margin: 0; font-size: 22px; font-weight: 700;">ObsidianScout Password Reset</h2>
                    </div>
                    <p style="font-size: 15px;">$greeting</p>
                    <p style="font-size: 15px; color: #475569;">We received a request to reset the credentials for your ObsidianScout account.</p>
                    $appTokenSection
                    <p style="font-size: 14px; color: #475569; margin-top: 24px;">You can also reset your password directly in your web browser by clicking the button below:</p>
                    <p style="text-align: center; margin: 24px 0;">
                        <a href="$resetUrl" style="background-color: #0284c7; color: #ffffff; padding: 12px 28px; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 15px; display: inline-block;">Reset Password in Browser</a>
                    </p>
                    <p style="font-size: 13px; color: #64748b;">This password reset token and link will expire in 1 hour.</p>
                    <p style="color: #94a3b8; font-size: 12px; margin-top: 32px; border-top: 1px solid #f1f5f9; padding-top: 16px;">
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
