package com.obsidianscout.admin

import com.obsidianscout.config.AppConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.admin.MdnsService")

/**
 * Advertises this server on the local network over multicast DNS, so devices can reach it
 * at `http://<hostname>.local:<port>` (default `obsidianscout.local`) without any DNS or
 * router setup. Also registers `_http._tcp` / `_https._tcp` services named "ObsidianScout"
 * so apps can discover the server by type.
 *
 * One JmDNS responder runs per LAN IPv4 address. Interfaces are re-checked every 30 s so a
 * laptop that changes networks keeps advertising on the new one.
 */
object MdnsService {
    private const val SERVICE_NAME = "ObsidianScout"
    private const val REFRESH_SECONDS = 30L

    /** Adapter names that are never the venue LAN (VPNs, containers, VMs). */
    private val IGNORED_INTERFACE_HINTS = listOf(
        "tailscale", "zerotier", "docker", "br-", "veth", "vethernet", "vmnet", "vmware",
        "virtualbox", "vbox", "hyper-v", "wsl", "utun", "tun", "tap", "wireguard", "wg"
    )

    private val responders = ConcurrentHashMap<InetAddress, JmDNS>()
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var lastConfig: AppConfig? = null

    /** The `.local` names currently being answered, e.g. `obsidianscout.local`. */
    fun advertisedHostnames(): List<String> =
        responders.values.mapNotNull { runCatching { it.hostName.trimEnd('.') }.getOrNull() }.distinct()

    fun start(appConfig: AppConfig) {
        val mdns = appConfig.server.mdns
        if (!mdns.enabled) {
            consoleLog.info("[mDNS] Disabled in config (server.mdns.enabled=false).")
            return
        }
        val bindHost = appConfig.server.host
        if (bindHost == "127.0.0.1" || bindHost == "localhost" || bindHost == "::1") {
            consoleLog.info("[mDNS] Server is bound to loopback only; not advertising ${mdns.hostname}.local on the network.")
            return
        }
        lastConfig = appConfig
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mdns-refresh").apply { isDaemon = true }
        }
        scheduler = exec
        exec.scheduleWithFixedDelay({
            try {
                refresh()
            } catch (e: Exception) {
                consoleLog.warn("[mDNS] Refresh failed: ${e.message}")
            }
        }, 0, REFRESH_SECONDS, TimeUnit.SECONDS)
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        responders.keys.toList().forEach { closeResponder(it) }
    }

    private fun refresh() {
        val config = lastConfig ?: return
        val wanted = candidateAddresses(config.server.host).toSet()

        responders.keys.filter { it !in wanted }.forEach { addr ->
            consoleLog.info("[mDNS] ${addr.hostAddress} is no longer available; stopping its responder.")
            closeResponder(addr)
        }
        wanted.filter { it !in responders.keys }.forEach { addr -> openResponder(addr, config) }
    }

    private fun openResponder(addr: InetAddress, config: AppConfig) {
        val mdns = config.server.mdns
        try {
            val jmdns = JmDNS.create(addr, mdns.hostname)
            responders[addr] = jmdns

            val txt = mapOf("path" to "/", "version" to config.current_version)
            jmdns.registerService(ServiceInfo.create("_http._tcp.local.", SERVICE_NAME, config.server.port, 0, 0, txt))
            if (config.server.https.enabled) {
                jmdns.registerService(ServiceInfo.create("_https._tcp.local.", SERVICE_NAME, config.server.https.port, 0, 0, txt))
            }

            // JmDNS renames on conflict (another server already owns the name), so report what was actually claimed.
            val claimed = jmdns.hostName.trimEnd('.')
            if (!claimed.equals("${mdns.hostname}.local", ignoreCase = true)) {
                consoleLog.warn("[mDNS] ${mdns.hostname}.local is already taken on this network; advertising as $claimed instead.")
            }
            val httpsNote = if (config.server.https.enabled) " and https://$claimed:${config.server.https.port}" else ""
            consoleLog.info("[mDNS] Reachable at http://$claimed:${config.server.port}$httpsNote (via ${addr.hostAddress})")
        } catch (e: Exception) {
            responders.remove(addr)?.let { runCatching { it.close() } }
            consoleLog.warn("[mDNS] Could not advertise on ${addr.hostAddress}: ${e.message}")
        }
    }

    private fun closeResponder(addr: InetAddress) {
        val jmdns = responders.remove(addr) ?: return
        runCatching {
            jmdns.unregisterAllServices()
            jmdns.close()
        }
    }

    /** Site-local IPv4 addresses on real, up, multicast-capable LAN adapters. */
    private fun candidateAddresses(bindHost: String): List<InetAddress> {
        val specific = bindHost.takeIf { it != "0.0.0.0" && it != "::" && it.isNotBlank() }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        if (specific != null && !specific.isLoopbackAddress) return listOf(specific)

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return emptyList()
        return interfaces
            .filter { nic ->
                runCatching { nic.isUp && !nic.isLoopback && !nic.isVirtual && !nic.isPointToPoint && nic.supportsMulticast() }
                    .getOrDefault(false)
            }
            .filter { nic ->
                val name = "${nic.name} ${nic.displayName ?: ""}".lowercase()
                IGNORED_INTERFACE_HINTS.none { hint -> name.contains(hint) }
            }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
    }
}
