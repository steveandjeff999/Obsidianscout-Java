package com.obsidianscout.routes

import com.obsidianscout.config.AppConfigLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRoutes() {
    routing {
        route("/api") {
            localAiApiRoutes()

            get("/version") {
                val appConfig = AppConfigLoader.load()
                call.respond(VersionResponse(appConfig.current_version, com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode()))
            }
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            adminRoutes()
            authRoutes()
            docRoutes()
            configRoutes()
            scoutingRoutes()
            analyticsRoutes()
            eventAndMatchRoutes()
            chatRoutes()
            notificationRoutes()
            allianceRoutes()
            bannerRoutes()
            contactRoutes()
        }

        localAiModelFileRoutes()
        staticWebRoutes()
    }
}