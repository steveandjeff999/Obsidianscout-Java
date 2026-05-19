package com.obsidianscout

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.SyncScheduler
import com.obsidianscout.routes.ErrorResponse
import com.obsidianscout.routes.configureRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.generateCertificate
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.security.KeyStore

fun main() {
    val appConfig = AppConfigLoader.load()
    DatabaseFactory.init(appConfig.database)
    ConfigService.ensureDefaultConfig()
    SettingsService.ensureDefaultSettings()
    AuthService.ensureSeedSuperAdmin(appConfig.seed)

    val environment = applicationEngineEnvironment {
        module { module(appConfig) }
        connector {
            host = appConfig.server.host
            port = appConfig.server.port
        }
        if (appConfig.server.https.enabled) {
            val keyStore = loadOrCreateKeyStore(appConfig)
            sslConnector(
                keyStore = keyStore,
                keyAlias = appConfig.server.https.keyAlias,
                keyStorePassword = { appConfig.server.https.keystorePassword.toCharArray() },
                privateKeyPassword = { appConfig.server.https.keystorePassword.toCharArray() }
            ) {
                host = appConfig.server.host
                port = appConfig.server.https.port
            }
        }
    }

    embeddedServer(Netty, environment).start(wait = true)
}

fun Application.module(appConfig: AppConfig) {
    install(DefaultHeaders)
    install(Compression)
    install(CallLogging) {
        filter { call -> call.request.path().startsWith("/api") }
    }
    install(ContentNegotiation) {
        json(JsonSupport.json)
    }
    install(Sessions) {
        cookie<UserSession>("obsidian_session") {
            cookie.httpOnly = true
            cookie.path = "/"
            cookie.maxAgeInSeconds = 60 * 60 * 12
            cookie.extensions["SameSite"] = "Strict"
            cookie.secure = appConfig.server.cookieSecure || appConfig.server.https.enabled
            transform(SessionTransportTransformerMessageAuthentication(appConfig.server.sessionSecret.toByteArray()))
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Server error"))
        }
    }

    configureRoutes()

    SyncScheduler.start()
    environment.monitor.subscribe(ApplicationStopped) {
        SyncScheduler.stop()
    }
}

private fun loadOrCreateKeyStore(appConfig: AppConfig): KeyStore {
    val httpsConfig = appConfig.server.https
    val keystoreFile = File(httpsConfig.keystorePath)
    if (!keystoreFile.exists()) {
        keystoreFile.parentFile?.mkdirs()
        generateCertificate(
            file = keystoreFile,
            keyAlias = httpsConfig.keyAlias,
            keyPassword = httpsConfig.keystorePassword,
            jksPassword = httpsConfig.keystorePassword
        )
    }
    val keyStore = KeyStore.getInstance("JKS")
    keystoreFile.inputStream().use { input ->
        keyStore.load(input, httpsConfig.keystorePassword.toCharArray())
    }
    return keyStore
}
