package com.obsidianscout.blob

import com.obsidianscout.admin.ClusterNodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlobNetworkTest {
    private val ports = listOf(9333 to "Master (HTTP)", 19333 to "Master (gRPC)", 9340 to "Volume server (HTTP)", 19340 to "Volume server (gRPC)")
    private val masterPorts = setOf(9333, 19333)
    private val a = "100.64.0.1"
    private val b = "100.64.0.2"
    private val c = "100.64.0.3"

    private fun node(ip: String, online: Boolean = true) =
        ClusterNodeInfo(nodeId = ip, ip = ip, dbPort = 26257, appPort = 8080, isLocal = ip == a, status = if (online) "online" else "offline")

    private fun probe(ip: String, os: String, listening: Set<Int>, reach: Map<String, Set<Int>>, targets: List<String>) = BlobProbeDto(
        nodeIp = ip,
        os = os,
        runsMaster = 9333 in listening,
        runsVolume = 9340 in listening,
        listening = ports.associate { it.first.toString() to (it.first in listening) },
        checks = targets.filter { it != ip }.flatMap { t -> ports.map { (p, _) -> BlobPortCheck(t, p, p in (reach[t] ?: emptySet())) } }
    )

    @Test
    fun `a windows server whose volume ports nobody can reach is flagged with the windows command`() {
        val all = listOf(a, b, c)
        // a is the master; b (Windows) listens on its volume ports but its firewall drops them.
        val probes = mapOf(
            a to probe(a, "linux", setOf(9333, 19333, 9340, 19340), mapOf(b to emptySet(), c to setOf(9340, 19340)), all),
            b to probe(b, "windows", setOf(9340, 19340), mapOf(a to setOf(9333, 19333, 9340, 19340), c to setOf(9340, 19340)), all),
            c to probe(c, "linux", setOf(9340, 19340), mapOf(a to setOf(9333, 19333, 9340, 19340), b to emptySet()), all)
        )
        val result = BlobNetworkService.evaluate(all.map { node(it) }, all.toSet(), probes, ports, masterPorts)

        val nb = result.nodes.single { it.ip == b }
        assertEquals(listOf(9340, 19340), nb.blockedPorts)
        assertEquals("blocked", nb.ports.single { it.port == 9340 }.state)
        assertEquals("not_listening", nb.ports.single { it.port == 9333 }.state)
        assertEquals(1, nb.fixes.size)
        assertTrue(nb.fixes.single().command.startsWith("New-NetFirewallRule"))
        assertTrue(nb.fixes.single().command.contains("9333,19333,9340,19340"))

        assertTrue(result.nodes.single { it.ip == a }.blockedPorts.isEmpty())
        assertTrue(result.nodes.single { it.ip == c }.blockedPorts.isEmpty())
        assertEquals(2, result.problems)
        assertTrue(result.summary.contains(b))
    }

    @Test
    fun `a port only some servers can reach is reported as partly blocked`() {
        val all = listOf(a, b, c)
        val probes = mapOf(
            a to probe(a, "linux", setOf(9333, 19333, 9340, 19340), mapOf(b to setOf(9340, 19340), c to setOf(9340, 19340)), all),
            b to probe(b, "linux", setOf(9340, 19340), mapOf(a to setOf(9333, 19333, 9340, 19340), c to setOf(9340, 19340)), all),
            // c can't reach a's master ports (e.g. a Tailscale ACL), b can.
            c to probe(c, "linux", setOf(9340, 19340), mapOf(a to setOf(9340, 19340), b to setOf(9340, 19340)), all)
        )
        val result = BlobNetworkService.evaluate(all.map { node(it) }, all.toSet(), probes, ports, masterPorts)
        val na = result.nodes.single { it.ip == a }
        assertEquals("partial", na.ports.single { it.port == 9333 }.state)
        assertEquals(listOf(9333, 19333), na.blockedPorts)
    }

    @Test
    fun `an open cluster, an offline server and a lone server report no problems`() {
        val probes = mapOf(
            a to probe(a, "linux", setOf(9333, 19333, 9340, 19340), mapOf(b to setOf(9340, 19340)), listOf(a, b)),
            b to probe(b, "linux", setOf(9340, 19340), mapOf(a to setOf(9333, 19333, 9340, 19340)), listOf(a, b))
        )
        val open = BlobNetworkService.evaluate(listOf(node(a), node(b), node(c, online = false)), setOf(a, b), probes, ports, masterPorts)
        assertEquals(0, open.problems)
        assertEquals("untested", open.nodes.single { it.ip == c }.ports.first().state)

        val alone = BlobNetworkService.evaluate(listOf(node(a)), setOf(a), mapOf(a to probe(a, "windows", setOf(9333, 19333, 9340, 19340), emptyMap(), listOf(a))), ports, masterPorts)
        assertEquals(0, alone.problems)
        assertTrue(alone.summary.startsWith("Only one server is online"))
    }
}
