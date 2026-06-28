package com.obsidianscout.utils

import com.obsidianscout.config.AppConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.call.body
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.util.pipeline.PipelineContext
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.security.KeyStore
import io.ktor.server.engine.sslConnector
import io.ktor.utils.io.ByteReadChannel

object ProxyServer {
    private val client = HttpClient(CIO) {
        install(ClientWebSockets)
    }

    fun start(appConfig: AppConfig, keyStore: KeyStore) {
        val targetPort = appConfig.server.port
        val proxyPort = appConfig.server.https.port
        val host = appConfig.server.host

        val environment = applicationEngineEnvironment {
            sslConnector(
                keyStore = keyStore,
                keyAlias = appConfig.server.https.keyAlias,
                keyStorePassword = { appConfig.server.https.keystorePassword.toCharArray() },
                privateKeyPassword = { appConfig.server.https.keystorePassword.toCharArray() }
            ) {
                this.host = host
                this.port = proxyPort
            }
            module {
                install(ServerWebSockets)
                routing {
                    // WebSocket proxying - target the specific alliance collaboration path to prevent routing conflicts
                    webSocket("/api/alliances/{id}/collaborate/{kind}") outerWS@ {
                        val path = call.request.uri
                        val incomingHeaders = call.request.headers
                        
                        try {
                            client.webSocket(
                                method = HttpMethod.Get,
                                host = "127.0.0.1",
                                port = targetPort,
                                path = path,
                                request = {
                                    incomingHeaders.forEach { key, values ->
                                        if (!key.equals(HttpHeaders.Host, ignoreCase = true) &&
                                            !key.equals(HttpHeaders.Upgrade, ignoreCase = true) &&
                                            !key.equals(HttpHeaders.Connection, ignoreCase = true) &&
                                            !key.equals("Sec-WebSocket-Key", ignoreCase = true) &&
                                            !key.equals("Sec-WebSocket-Version", ignoreCase = true) &&
                                            !key.equals("Sec-WebSocket-Extensions", ignoreCase = true)
                                        ) {
                                            values.forEach { headers.append(key, it) }
                                        }
                                    }
                                    headers.append("X-Forwarded-Proto", "https")
                                    headers.append("X-Forwarded-For", call.request.local.remoteAddress)
                                    headers.append("X-Forwarded-Host", call.request.headers[HttpHeaders.Host] ?: "")
                                }
                            ) {
                                val clientSession = this
                                val serverSession = this@outerWS
                                
                                val serverToClient = launch {
                                    try {
                                        for (frame in serverSession.incoming) {
                                            clientSession.outgoing.send(frame)
                                        }
                                    } catch (e: Exception) {
                                        // connection closed
                                    }
                                }
                                
                                val clientToServer = launch {
                                    try {
                                        for (frame in clientSession.incoming) {
                                            serverSession.outgoing.send(frame)
                                        }
                                    } catch (e: Exception) {
                                        // connection closed
                                    }
                                }
                                
                                joinAll(serverToClient, clientToServer)
                            }
                        } catch (e: Exception) {
                            // Handle websocket proxy failure
                        }
                    }

                    // HTTP proxying using route("/{...}") as requested, with streaming via prepareRequest
                    route("/{...}") {
                        handle {
                            val call = call
                            val request = call.request
                            val path = request.uri
                            
                            try {
                                client.prepareRequest {
                                    method = request.local.method
                                    url("http://127.0.0.1:$targetPort$path")
                                    
                                    // Copy headers from client
                                    request.headers.forEach { key, values ->
                                        if (!key.equals(HttpHeaders.Host, ignoreCase = true) &&
                                            !key.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                                            !key.equals(HttpHeaders.TransferEncoding, ignoreCase = true)
                                        ) {
                                            values.forEach { headers.append(key, it) }
                                        }
                                    }
                                    
                                    // Append proxy headers
                                    headers.append("X-Forwarded-Proto", "https")
                                    headers.append("X-Forwarded-For", request.local.remoteAddress)
                                    headers.append("X-Forwarded-Host", request.headers[HttpHeaders.Host] ?: "")
                                    
                                    // Stream request body
                                    setBody(request.receiveChannel())
                                }.execute { response ->
                                    // Send back the response while streaming
                                    call.respond(object : OutgoingContent.WriteChannelContent() {
                                        override val status: HttpStatusCode = response.status
                                        override val headers: Headers = Headers.build {
                                            response.headers.forEach { key, values ->
                                                if (!key.equals(HttpHeaders.TransferEncoding, ignoreCase = true) &&
                                                    !key.equals(HttpHeaders.ContentLength, ignoreCase = true)
                                                ) {
                                                    values.forEach { append(key, it) }
                                                }
                                            }
                                        }
                                        override suspend fun writeTo(channel: ByteWriteChannel) {
                                            response.body<ByteReadChannel>().copyTo(channel)
                                        }
                                    })
                                }
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadGateway, "Reverse Proxy Error: ${e.message}")
                            }
                        }
                    }
                }
            }
        }

        embeddedServer(Netty, environment).start(wait = false)
        println("[ProxyServer] Embedded HTTPS reverse proxy started on port $proxyPort (routing to HTTP backend on $targetPort)")
    }
}
