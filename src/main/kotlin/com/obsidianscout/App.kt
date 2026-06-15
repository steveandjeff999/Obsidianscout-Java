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
import com.obsidianscout.routes.configureMobileRoutes
import com.obsidianscout.routes.MobileApiException
import com.obsidianscout.routes.MobileErrorResponse
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
import io.ktor.server.sessions.SessionProvider
import com.obsidianscout.auth.KeepMeLoggedInSessionTransport
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.websocket.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
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

    embeddedServer(Netty, environment) {
        channelPipelineConfig = {
            addLast("ssl-connection-closer", object : ChannelInboundHandlerAdapter() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    if (cause.isIgnorableException()) {
                        ctx.close()
                        return
                    }
                    ctx.fireExceptionCaught(cause)
                }
            })
        }
    }.start(wait = true)
}

fun Application.module(appConfig: AppConfig) {
    install(com.obsidianscout.utils.ServerTimingPlugin)
    install(DefaultHeaders)
    install(WebSockets) {
        pingPeriod = java.time.Duration.ofSeconds(15)
        timeout = java.time.Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
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
            cookie.extensions["SameSite"] = "Lax"
            cookie.secure = appConfig.server.cookieSecure
            transform(SessionTransportTransformerMessageAuthentication(appConfig.server.sessionSecret.toByteArray()))
        }

        // Wrap the registered provider's transport to dynamically support Keep Me Logged In
        @Suppress("UNCHECKED_CAST")
        val originalProvider = providers.firstOrNull { it.name == "obsidian_session" } as? SessionProvider<UserSession>
        if (originalProvider != null) {
            val originalTransport = originalProvider.transport as io.ktor.server.sessions.SessionTransportCookie
            val wrappedTransport = KeepMeLoggedInSessionTransport(originalTransport)
            val newProvider = SessionProvider(
                name = originalProvider.name,
                type = originalProvider.type,
                transport = wrappedTransport,
                tracker = originalProvider.tracker
            )

            val clazz = io.ktor.server.sessions.SessionsConfig::class.java
            val listField = runCatching { clazz.getDeclaredField("registered") }
                .recoverCatching { clazz.getDeclaredField("_providers") }
                .recoverCatching { clazz.getDeclaredField("providers") }
                .getOrNull()
            if (listField != null) {
                listField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = listField.get(this) as? MutableList<SessionProvider<UserSession>>
                if (list != null) {
                    val index = list.indexOfFirst { it.name == "obsidian_session" }
                    if (index != -1) {
                        list[index] = newProvider
                    }
                }
            }
        }
    }
    install(StatusPages) {
        exception<MobileApiException> { call, cause ->
            call.respond(cause.status, MobileErrorResponse(success = false, error = cause.message, errorCode = cause.errorCode))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message))
        }
        exception<Throwable> { call, cause ->
            if (cause is io.ktor.util.cio.ChannelWriteException ||
                cause is java.nio.channels.ClosedChannelException ||
                cause is kotlinx.coroutines.CancellationException ||
                cause.isIgnorableException()
            ) {
                return@exception
            }
            call.application.environment.log.error("Unhandled error", cause)
            try {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Server error"))
            } catch (_: Throwable) {
                // Ignore subsequent writes if channel is closed
            }
        }
    }

    configureRoutes()
    configureMobileRoutes(appConfig)

    SyncScheduler.start()
    com.obsidianscout.scouting.DeduplicationScheduler.start()
    environment.monitor.subscribe(ApplicationStopped) {
        SyncScheduler.stop()
        com.obsidianscout.scouting.DeduplicationScheduler.stop()
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

private fun Throwable.isIgnorableException(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SSLHandshakeException || current is SSLException) {
            return true
        }
        if (current is java.io.IOException) {
            val msg = current.message?.lowercase() ?: ""
            if (msg.contains("connection reset") ||
                msg.contains("broken pipe") ||
                msg.contains("aborted") ||
                msg.contains("connection aborted")
            ) {
                return true
            }
        }
        current = current.cause
    }
    return false
}
