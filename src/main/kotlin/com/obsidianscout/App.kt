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
import com.obsidianscout.utils.GistUpdateService
import com.obsidianscout.routes.ErrorResponse
import com.obsidianscout.routes.configureRoutes
import com.obsidianscout.routes.configureMobileRoutes
import com.obsidianscout.routes.MobileApiException
import com.obsidianscout.routes.MobileErrorResponse
import com.obsidianscout.routes.respondStaticHtml
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.http.content.CachingOptions
import io.ktor.http.CacheControl
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.SessionProvider
import com.obsidianscout.auth.KeepMeLoggedInSessionTransport
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.websocket.*
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import com.obsidianscout.db.orchestration.CockroachOrchestrator

private var cockroachOrchestrator: CockroachOrchestrator? = null

@Serializable
data class DbInitResponse(val status: String, val message: String)


fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        var isOom = false
        var curr: Throwable? = throwable
        while (curr != null) {
            if (curr is OutOfMemoryError) {
                isOom = true
                break
            }
            curr = curr.cause
        }
        if (isOom) {
            System.err.println("[OOM-Guard] CRITICAL: OutOfMemoryError caught in thread '${thread.name}'. Triggering automatic process exit with heap escalation...")
            try {
                File(".oom_occurred").writeText("1")
            } catch (e: Exception) { /* ignore */ }
            Runtime.getRuntime().halt(137)
        } else {
            System.err.println("[UncaughtException] Thread '${thread.name}' threw exception: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    Security.addProvider(BouncyCastleProvider())
    val appConfig = AppConfigLoader.load()
    
    val environment = applicationEngineEnvironment {
        module { module(appConfig) }
        connector {
            host = if (appConfig.server.host == "127.0.0.1") "0.0.0.0" else appConfig.server.host
            port = appConfig.server.port
        }
    }

    if (appConfig.server.https.enabled) {
        val keyStore = loadOrCreateKeyStore(appConfig)
        com.obsidianscout.utils.ProxyServer.start(appConfig, keyStore)
    }

    embeddedServer(Netty, environment) {
        connectionGroupSize = 4
        workerGroupSize = 32
        callGroupSize = 64
        requestReadTimeoutSeconds = 60
        responseWriteTimeoutSeconds = 60
    }.start(wait = true)
}

fun Application.module(appConfig: AppConfig) {

    com.obsidianscout.auth.ClusterSecretService.initFromConfig(appConfig)

    install(com.obsidianscout.utils.ServerTimingPlugin)
    install(DefaultHeaders) {
        header("X-Frame-Options", "DENY")
        header("X-Content-Type-Options", "nosniff")
        header("X-XSS-Protection", "1; mode=block")
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'self'; form-action 'self';")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Permissions-Policy", "geolocation=(), microphone=(), camera=(self)")
    }
    install(WebSockets) {
        pingPeriod = java.time.Duration.ofSeconds(15)
        timeout = java.time.Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(Compression) {
        gzip {
            condition {
                !request.path().startsWith("/api")
            }
        }
        deflate {
            condition {
                !request.path().startsWith("/api")
            }
        }
    }
    install(CachingHeaders) {
        options { call, _ ->
            val path = call.request.path()
            if (path.contains("/vendor/") || path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".woff2")) {
                CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 3600 * 24 * 30, visibility = CacheControl.Visibility.Public))
            } else {
                CachingOptions(CacheControl.NoStore(visibility = CacheControl.Visibility.Private))
            }
        }
    }
    install(CallLogging) {
        filter { call -> 
            if (appConfig.server.logging) true 
            else call.request.path().startsWith("/api") 
        }
    }

    // Pipeline Interceptor for Security Headers, Anti-CSRF Token, and Cache Controls
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val method = call.request.local.method.value.uppercase()
        val isAsset = path.contains("/vendor/") || path.contains("/css/") || path.contains("/js/") ||
                path.contains("/assets/") || path.endsWith(".js") || path.endsWith(".css") ||
                path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".woff2")

        if (!isAsset) {
            call.response.headers.append("Cache-Control", "no-store, no-cache, must-revalidate, private")
            call.response.headers.append("Pragma", "no-cache")
            call.response.headers.append("Expires", "0")
        }

        if (call.request.cookies["XSRF-TOKEN"] == null) {
            val csrfToken = java.util.UUID.randomUUID().toString()
            call.response.cookies.append(
                io.ktor.http.Cookie(
                    name = "XSRF-TOKEN",
                    value = csrfToken,
                    path = "/",
                    httpOnly = false,
                    extensions = mapOf("SameSite" to "Lax"),
                    secure = appConfig.server.cookieSecure
                )
            )
        }

        if (method in listOf("POST", "PUT", "DELETE", "PATCH")) {
            val isExcludedPath = path.startsWith("/api/push") ||
                    path.startsWith("/api/mobile") ||
                    path.startsWith("/api/cluster") ||
                    path.startsWith("/api/admin/cluster") ||
                    call.request.headers["X-Cluster-Signature"] != null
            if (!isExcludedPath) {
                val origin = call.request.headers["Origin"]
                val referer = call.request.headers["Referer"]
                val host = call.request.headers["Host"]
                val csrfHeader = call.request.headers["X-CSRF-Token"] ?: call.request.headers["X-XSRF-TOKEN"]
                val requestedWith = call.request.headers["X-Requested-With"]
                val cookieToken = call.request.cookies["XSRF-TOKEN"]

                var valid = false
                if (!csrfHeader.isNullOrBlank() && cookieToken != null && csrfHeader == cookieToken) {
                    valid = true
                } else if (requestedWith.equals("XMLHttpRequest", ignoreCase = true)) {
                    valid = true
                } else if (host != null && ((origin != null && origin.contains(host)) || (referer != null && referer.contains(host)))) {
                    valid = true
                }

                if (!valid) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("CSRF validation failed")
                    )
                    finish()
                    return@intercept
                }
            }
        }
    }
    
    // Intercept requests to serve 503 if database is not ready
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        // Exclude cluster status endpoints and static/vendor assets from the blocking interceptor
        val isExcluded = path.startsWith("/api/cluster/") ||
                path.startsWith("/api/admin/cluster/") ||
                path.startsWith("/api/banners") ||
                path.contains("/vendor/") ||
                path.contains("/css/") ||
                path.contains("/js/") ||
                path.contains("/assets/") ||
                path.endsWith(".js") ||
                path.endsWith(".css") ||
                path.endsWith(".png") ||
                path.endsWith(".ico") ||
                path.endsWith(".woff2") ||
                path.endsWith(".json")

        if (!isExcluded && !DatabaseFactory.isReady) {
            if (path.startsWith("/api")) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    DbInitResponse(
                        status = "initializing",
                        message = "Database cluster is currently booting up. Please retry in a few moments."
                    )
                )
            } else {
                call.respondStaticHtml("503.html", HttpStatusCode.ServiceUnavailable)
            }
            finish() // terminate processing of this call
        }
    }
    if (appConfig.server.logging) {
        intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
            val path = call.request.path()
            val method = call.request.local.method.value
            val id = java.util.UUID.randomUUID().toString().take(8)
            println("[Request Start] [$id] $method $path")
            try {
                proceed()
            } finally {
                val status = call.response.status()
                println("[Request End] [$id] $method $path -> ${status?.value ?: "Aborted/Connection Reset"}")
            }
        }
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
            transform(com.obsidianscout.auth.ClusterSessionTransformer { com.obsidianscout.auth.ClusterSecretService.getSessionSecret() })
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
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.request.path().startsWith("/api")) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            } else {
                call.respondStaticHtml("404.html", HttpStatusCode.NotFound)
            }
        }
        status(HttpStatusCode.InternalServerError) { call, _ ->
            if (call.request.path().startsWith("/api")) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
            } else {
                call.respondStaticHtml("500.html", HttpStatusCode.InternalServerError)
            }
        }
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

            val isQuorumLoss = CockroachOrchestrator.isQuorumLossException(cause) ||
                    (CockroachOrchestrator.isQuorumLost && cause.toString().lowercase().let { 
                        it.contains("sql") || it.contains("exposed") || it.contains("transaction") || it.contains("connection")
                    })

            if (isQuorumLoss) {
                CockroachOrchestrator.isQuorumLost = true
                if (CockroachOrchestrator.quorumLossDetails.isNullOrBlank()) {
                    CockroachOrchestrator.quorumLossDetails = cause.message ?: "CockroachDB cluster quorum lost."
                }
                call.application.environment.log.warn("Database quorum loss encountered on request ${call.request.path()}: ${cause.message}")
                try {
                    if (call.request.path().startsWith("/api")) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            DbInitResponse(
                                status = "quorum_lost",
                                message = "Database quorum lost: CockroachDB cluster majority is offline. Operations requiring database access are temporarily suspended."
                            )
                        )
                    } else {
                        call.respondStaticHtml("503.html", HttpStatusCode.ServiceUnavailable)
                    }
                } catch (_: Throwable) {}
                return@exception
            }

            call.application.environment.log.error("Unhandled error", cause)
            try {
                if (call.request.path().startsWith("/api")) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Server error"))
                } else {
                    call.respondStaticHtml("500.html", HttpStatusCode.InternalServerError)
                }
            } catch (_: Throwable) {
                // Ignore subsequent writes if channel is closed
            }
        }
    }

    configureRoutes()
    configureMobileRoutes(appConfig)

    // Start Gist update checking immediately so that faulty updates can be resolved even if the database fails to initialize
    GistUpdateService.start(appConfig)

    // Mark update boot successful as soon as web engine & routes start
    com.obsidianscout.utils.UpdateRecoveryManager.markBootSuccessful()

    // Run database orchestration and initialization in a background coroutine
    launch(Dispatchers.IO) {
        var initialized = false
        var attempts = 0
        var dbConfig: com.obsidianscout.config.DatabaseConfig? = null
        while (!initialized) {
            try {
                attempts++
                if (attempts > 1) {
                    println("[Database] Retrying database orchestration and pool startup in 10 seconds (attempt #$attempts)...")
                    delay(10000)
                }

                if (appConfig.database_type.lowercase() == "cockroach") {
                    val orchestrator = cockroachOrchestrator ?: run {
                        val newOrch = CockroachOrchestrator(appConfig)
                        cockroachOrchestrator = newOrch
                        newOrch
                    }
                    if (dbConfig == null || !orchestrator.isProcessAlive()) {
                        dbConfig = orchestrator.orchestrate()
                    }
                } else {
                    dbConfig = appConfig.database
                }

                DatabaseFactory.orchestrator = cockroachOrchestrator
                DatabaseFactory.init(
                    config = dbConfig!!,
                    runMigration = true,
                    isCockroach = (appConfig.database_type.lowercase() == "cockroach")
                )
                ConfigService.ensureDefaultConfig()
                SettingsService.ensureDefaultSettings()
                AuthService.ensureSeedSuperAdmin(appConfig.seed)

                com.obsidianscout.auth.ClusterSecretService.syncSecrets(appConfig)
                com.obsidianscout.auth.ClusterSecretService.startBackgroundSync(appConfig)

                SyncScheduler.start()
                com.obsidianscout.scouting.DeduplicationScheduler.start()
                com.obsidianscout.admin.CloudflaredService.initOnStartup()
                com.obsidianscout.admin.NodeMonitoringService.start()
                println("[Database] Background database initialization completed successfully.")
                
                // Mark update boot successful once startup completes
                com.obsidianscout.utils.UpdateRecoveryManager.markBootSuccessful()

                // Start replication monitoring only after initial setup is fully complete and successful
                cockroachOrchestrator?.startReplicationMonitor()
                initialized = true
            } catch (e: Exception) {
                environment.log.error("Database orchestration failed (attempt #$attempts)", e)
                try {
                    DatabaseFactory.close()
                } catch (closeEx: Exception) {
                    // Ignore
                }
            }
        }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        com.obsidianscout.admin.NodeMonitoringService.stop()
        com.obsidianscout.auth.ClusterSecretService.stopBackgroundSync()
        SyncScheduler.stop()
        GistUpdateService.stop()
        com.obsidianscout.scouting.DeduplicationScheduler.stop()
        com.obsidianscout.admin.CloudflaredService.stopTunnel()
        try {
            DatabaseFactory.close()
        } catch (closeEx: Exception) {
            // Ignore
        }
        cockroachOrchestrator?.stop()
    }
}

private fun loadOrCreateKeyStore(appConfig: AppConfig): KeyStore {
    val httpsConfig = appConfig.server.https
    val keystoreFile = File(httpsConfig.keystorePath)
    if (!keystoreFile.exists()) {
        keystoreFile.parentFile?.mkdirs()
        val keyStore = buildKeyStore {
            certificate(httpsConfig.keyAlias) {
                password = httpsConfig.keystorePassword
                domains = listOf("localhost", "127.0.0.1", "192.168.1.130")
            }
        }
        keyStore.saveToFile(keystoreFile, httpsConfig.keystorePassword)
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

private fun Throwable.isSslException(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SSLHandshakeException || current is SSLException) {
            return true
        }
        current = current.cause
    }
    return false
}
