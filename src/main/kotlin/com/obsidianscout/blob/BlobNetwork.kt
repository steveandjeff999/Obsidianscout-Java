package com.obsidianscout.blob

import com.obsidianscout.admin.ClusterManagementService
import com.obsidianscout.admin.ClusterNodeInfo
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.Socket

@Serializable
data class BlobPortCheck(val targetIp: String, val port: Int, val ok: Boolean, val error: String? = null)

/** One server's view: which SeaweedFS ports it listens on, and which ports on the other servers it can reach. */
@Serializable
data class BlobProbeDto(
    val nodeIp: String,
    /** "windows", "linux", "mac" or "other". */
    val os: String,
    val runsMaster: Boolean,
    val runsVolume: Boolean,
    /** Port number (as text) -> something on this server accepts connections there. */
    val listening: Map<String, Boolean>,
    val checks: List<BlobPortCheck>
)

@Serializable
data class BlobPortStatus(
    val port: Int,
    val use: String,
    /** ok, blocked, partial, unreachable, not_listening, untested */
    val state: String,
    val detail: String
)

@Serializable
data class BlobFirewallFix(val label: String, val command: String)

@Serializable
data class BlobNodeNetwork(
    val ip: String,
    val os: String? = null,
    val online: Boolean,
    val ports: List<BlobPortStatus>,
    /** Ports other servers can't reach although something listens there: almost always this server's firewall. */
    val blockedPorts: List<Int>,
    val fixes: List<BlobFirewallFix>
)

@Serializable
data class BlobNetworkStatus(
    /** False for a single server (everything on 127.0.0.1), where no firewall change is needed. */
    val applicable: Boolean,
    val summary: String,
    val problems: Int,
    val requiredPorts: List<Int>,
    val nodes: List<BlobNodeNetwork>,
    val checkedAt: String
)

/**
 * Works out whether firewalls stop the servers' SeaweedFS processes from reaching each other. Every server tests
 * every other server's ports; a port that is listening but that no other server can reach is reported as blocked,
 * with the command that opens it on that server's OS.
 */
object BlobNetworkService {
    private const val CACHE_MS = 15_000L
    private const val CONNECT_TIMEOUT_MS = 1500
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    @Volatile private var cached: BlobNetworkStatus? = null
    @Volatile private var cachedAtMs = 0L

    private fun ports(): List<Pair<Int, String>> {
        val c = AppConfigLoader.load().blob_storage
        return listOf(
            c.master_port to "Master (HTTP)",
            c.master_port + 10000 to "Master (gRPC)",
            c.volume_port to "Volume server (HTTP)",
            c.volume_port + 10000 to "Volume server (gRPC)"
        )
    }

    private fun localOs(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            os.contains("mac") -> "mac"
            else -> "other"
        }
    }

    private fun canConnect(ip: String, port: Int): Pair<Boolean, String?> = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
        true to null
    } catch (e: java.net.SocketTimeoutException) {
        // No answer at all usually means a firewall dropping the packets; a refusal means nothing listens.
        false to "timed out"
    } catch (e: java.net.ConnectException) {
        false to "refused"
    } catch (e: Exception) {
        false to (e.message ?: e.javaClass.simpleName)
    }

    /** This server's probe of [targets] (other servers' Tailscale IPs). Only plain IPv4 addresses are tested. */
    suspend fun probe(targets: List<String>): BlobProbeDto = withContext(Dispatchers.IO) {
        val ip = ClusterManagementService.getLocalTailscaleIp()
        val state = BlobService.nodeState()
        val portList = ports().map { it.first }
        val clean = targets.map { it.trim() }.filter { t ->
            t != ip && IPV4.matchEntire(t)?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
        }.distinct().take(64)
        coroutineScope {
            val listening = portList.map { p -> async { p.toString() to canConnect(ip, p).first } }
            val checks = clean.flatMap { t ->
                portList.map { p -> async { canConnect(t, p).let { (ok, err) -> BlobPortCheck(t, p, ok, err) } } }
            }
            BlobProbeDto(
                nodeIp = ip,
                os = localOs(),
                runsMaster = state.runsMaster,
                runsVolume = state.runsVolume,
                listening = listening.awaitAll().toMap(),
                checks = checks.awaitAll()
            )
        }
    }

    /** Firewall picture for the whole cluster. [nodes] comes from the caller so the cluster isn't listed twice. */
    suspend fun status(nodes: List<ClusterNodeInfo>, clusterMode: Boolean): BlobNetworkStatus {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < CACHE_MS) return it }
        val required = ports().map { it.first }
        val result = if (!clusterMode) {
            BlobNetworkStatus(
                applicable = false,
                summary = "Single server: SeaweedFS only listens on 127.0.0.1, so no firewall changes are needed.",
                problems = 0,
                requiredPorts = required,
                nodes = emptyList(),
                checkedAt = java.time.Instant.now().toString()
            )
        } else {
            compute(nodes, required)
        }
        cached = result
        cachedAtMs = now
        return result
    }

    private suspend fun compute(nodes: List<ClusterNodeInfo>, required: List<Int>): BlobNetworkStatus {
        val localIp = ClusterManagementService.getLocalTailscaleIp()
        val online = nodes.filter { it.isLocal || it.ip == localIp || it.status.equals("online", ignoreCase = true) }
        val targets = online.map { it.ip }
        val query = "?targets=" + java.net.URLEncoder.encode(targets.joinToString(","), "UTF-8")

        val probes = HashMap<String, BlobProbeDto>()
        probes[localIp] = probe(targets)
        ClusterManagementService.getFromPeers(online, "/api/admin/cluster/nodes/local/blob-storage/probe$query", timeoutSeconds = 8)
            .forEach { (node, body) ->
                body.getOrNull()?.let { runCatching { JsonSupport.json.decodeFromString(BlobProbeDto.serializer(), it) }.getOrNull() }
                    ?.let { probes[node.ip] = it }
            }

        val c = AppConfigLoader.load().blob_storage
        return evaluate(nodes, targets.toSet(), probes, ports(), setOf(c.master_port, c.master_port + 10000))
    }

    /**
     * Turns every server's probe into a verdict per server and port. Pure, so it can be tested without a cluster:
     * [onlineIps] are the servers that were asked, [probes] maps a server's IP to the probe it returned.
     */
    internal fun evaluate(
        nodes: List<ClusterNodeInfo>,
        onlineIps: Set<String>,
        probes: Map<String, BlobProbeDto>,
        ports: List<Pair<Int, String>>,
        masterPorts: Set<Int>
    ): BlobNetworkStatus {
        val uses = ports.toMap()
        val required = ports.map { it.first }
        val nodeResults = nodes.map { node ->
            val isOnline = node.ip in onlineIps
            val own = probes[node.ip]
            val portStatuses = required.map { port ->
                val use = uses[port] ?: "SeaweedFS"
                val fromPeers = probes.values.filter { it.nodeIp != node.ip }.flatMap { p -> p.checks.filter { it.targetIp == node.ip && it.port == port }.map { p.nodeIp to it } }
                val listening = own?.listening?.get(port.toString())
                val okFrom = fromPeers.filter { it.second.ok }.map { it.first }
                val failFrom = fromPeers.filterNot { it.second.ok }.map { it.first }
                when {
                    !isOnline -> BlobPortStatus(port, use, "untested", "Server is offline.")
                    listening == false && port in masterPorts ->
                        BlobPortStatus(port, use, "not_listening", "Only the master listens here. Open it anyway so this server can take over as master.")
                    listening == false ->
                        BlobPortStatus(port, use, "not_listening", "SeaweedFS isn't listening on this server, so it can't be tested yet.")
                    fromPeers.isEmpty() ->
                        BlobPortStatus(port, use, "untested", "No other server could test it.")
                    failFrom.isEmpty() ->
                        BlobPortStatus(port, use, "ok", "Reachable from ${okFrom.joinToString(", ")}.")
                    okFrom.isEmpty() && listening == true ->
                        BlobPortStatus(port, use, "blocked", "Listening, but no other server can connect (${failFrom.joinToString(", ")}). This server's firewall is blocking it.")
                    okFrom.isEmpty() ->
                        BlobPortStatus(port, use, "unreachable", "No other server can connect, and this server didn't answer the check itself.")
                    else ->
                        BlobPortStatus(port, use, "partial", "Reachable from ${okFrom.joinToString(", ")} but not from ${failFrom.joinToString(", ")}. Check firewall rules or Tailscale ACLs that only allow some servers.")
                }
            }
            val blocked = portStatuses.filter { it.state == "blocked" || it.state == "partial" || it.state == "unreachable" }.map { it.port }
            BlobNodeNetwork(
                ip = node.ip,
                os = own?.os,
                online = isOnline,
                ports = portStatuses,
                blockedPorts = blocked,
                fixes = fixesFor(own?.os, required)
            )
        }

        val problems = nodeResults.sumOf { it.blockedPorts.size }
        val blockedNodes = nodeResults.filter { it.blockedPorts.isNotEmpty() }
        val summary = when {
            onlineIps.size < 2 -> "Only one server is online, so ports between servers can't be tested yet. Open the ports below on every server before adding more."
            problems == 0 -> "Every server can reach the SeaweedFS ports it needs on the others."
            else -> "Firewall changes needed on ${blockedNodes.joinToString(", ") { it.ip }}."
        }
        return BlobNetworkStatus(
            applicable = true,
            summary = summary,
            problems = problems,
            requiredPorts = required,
            nodes = nodeResults,
            checkedAt = java.time.Instant.now().toString()
        )
    }

    /** Commands that open all SeaweedFS ports to the Tailscale range only (100.64.0.0/10), for [os]. */
    private fun fixesFor(os: String?, ports: List<Int>): List<BlobFirewallFix> {
        val list = ports.joinToString(",")
        val windows = BlobFirewallFix(
            "Windows (PowerShell as Administrator)",
            "New-NetFirewallRule -DisplayName \"ObsidianScout SeaweedFS\" -Direction Inbound -Action Allow -Protocol TCP -LocalPort $list -RemoteAddress 100.64.0.0/10"
        )
        val ufw = BlobFirewallFix("Linux with ufw", "sudo ufw allow from 100.64.0.0/10 to any port $list proto tcp")
        val firewalld = BlobFirewallFix(
            "Linux with firewalld",
            ports.joinToString(" && ") { p ->
                "sudo firewall-cmd --permanent --add-rich-rule='rule family=\"ipv4\" source address=\"100.64.0.0/10\" port port=\"$p\" protocol=\"tcp\" accept'"
            } + " && sudo firewall-cmd --reload"
        )
        return when (os) {
            "windows" -> listOf(windows)
            "linux" -> listOf(ufw, firewalld)
            else -> listOf(windows, ufw, firewalld)
        }
    }
}
