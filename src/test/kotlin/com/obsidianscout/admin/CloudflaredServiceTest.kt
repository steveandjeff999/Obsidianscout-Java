package com.obsidianscout.admin

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflaredServiceTest {
    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    private fun release(tag: String, published: String, digest: String?, notesSha: String?, draft: Boolean = false, prerelease: Boolean = false) = """
        {
          "tag_name": "$tag", "published_at": "$published", "draft": $draft, "prerelease": $prerelease,
          "body": "### Checksums\n```\n${if (notesSha != null) "cloudflared-linux-amd64: $notesSha" else ""}\n```",
          "assets": [{
            "name": "cloudflared-linux-amd64",
            "browser_download_url": "https://github.com/cloudflare/cloudflared/releases/download/$tag/cloudflared-linux-amd64"
            ${if (digest != null) ", \"digest\": \"sha256:$digest\"" else ""}
          }]
        }
    """.trimIndent()

    @Test
    fun `every OS and CPU maps to a file Cloudflare publishes`() {
        assertEquals("cloudflared-windows-amd64.exe", CloudflaredService.assetNameFor("Windows 11", "amd64"))
        assertEquals("cloudflared-windows-386.exe", CloudflaredService.assetNameFor("Windows 10", "x86"))
        // No Windows ARM build exists; the amd64 one runs under emulation.
        assertEquals("cloudflared-windows-amd64.exe", CloudflaredService.assetNameFor("Windows 11", "aarch64"))
        assertEquals("cloudflared-linux-amd64", CloudflaredService.assetNameFor("Linux", "amd64"))
        assertEquals("cloudflared-linux-arm64", CloudflaredService.assetNameFor("Linux", "aarch64"))
        assertEquals("cloudflared-linux-arm", CloudflaredService.assetNameFor("Linux", "arm"))
        assertEquals("cloudflared-linux-386", CloudflaredService.assetNameFor("Linux", "i686"))
        assertEquals("cloudflared-darwin-arm64.tgz", CloudflaredService.assetNameFor("Mac OS X", "aarch64"))
        assertEquals("cloudflared-darwin-amd64.tgz", CloudflaredService.assetNameFor("Mac OS X", "x86_64"))
    }

    @Test
    fun `releases are read with checksums from both GitHub and the notes, skipping drafts and pre-releases`() {
        val json = "[" + listOf(
            release("2026.10.7", "2026-10-30T17:11:03Z", sha, sha),
            release("2026.10.8", "2026-10-31T00:00:00Z", sha, sha, draft = true),
            release("2026.11.0-rc1", "2026-11-01T00:00:00Z", sha, sha, prerelease = true),
        ).joinToString(",") + "]"
        val releases = CloudflaredService.parseReleases(json)
        assertEquals(listOf("2026.10.7"), releases.map { it.version })
        val asset = releases.single().assets.getValue("cloudflared-linux-amd64")
        assertEquals(sha, asset.githubSha256)
        assertEquals(sha, asset.notesSha256)
    }

    @Test
    fun `a disagreement between GitHub and the notes is visible to the installer`() {
        val r = CloudflaredService.parseReleases("[" + release("2026.10.7", "2026-10-30T17:11:03Z", sha, otherSha) + "]").single()
        val asset = r.assets.getValue("cloudflared-linux-amd64")
        assertTrue(asset.githubSha256 != asset.notesSha256)
    }

    @Test
    fun `a file with no published checksum has neither source`() {
        val r = CloudflaredService.parseReleases("[" + release("2026.10.7", "2026-10-30T17:11:03Z", null, null) + "]").single()
        val asset = r.assets.getValue("cloudflared-linux-amd64")
        assertNull(asset.githubSha256)
        assertNull(asset.notesSha256)
    }

    @Test
    fun `the recommended release is the newest one old enough`() {
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val releases = CloudflaredService.parseReleases("[" + listOf(
            release("2026.11.1", "2026-11-06T00:00:00Z", sha, sha),   // 1 day old
            release("2026.10.9", "2026-11-03T00:00:00Z", sha, sha),   // 4 days old
            release("2026.10.7", "2026-10-30T00:00:00Z", sha, sha),
        ).joinToString(",") + "]")
        assertTrue(CloudflaredService.MIN_RELEASE_AGE >= Duration.ofDays(1))
        assertEquals("2026.10.9", CloudflaredService.pickRecommended(releases, now)?.version)
    }

    @Test
    fun `when every release is brand new the oldest listed one is recommended`() {
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val releases = CloudflaredService.parseReleases("[" + listOf(
            release("2026.11.2", "2026-11-06T12:00:00Z", sha, sha),
            release("2026.11.1", "2026-11-06T00:00:00Z", sha, sha),
        ).joinToString(",") + "]")
        assertEquals("2026.11.1", CloudflaredService.pickRecommended(releases, now)?.version)
    }

    @Test
    fun `versions compare numerically`() {
        assertTrue(CloudflaredService.compareVersions("2026.10.10", "2026.10.9") > 0)
        assertTrue(CloudflaredService.compareVersions("2025.12.0", "2026.1.0") < 0)
        assertEquals(0, CloudflaredService.compareVersions("2026.10.7", "2026.10.7"))
    }

    @Test
    fun `only exact versions are accepted`() {
        assertTrue(CloudflaredService.isValidVersion("2026.10.7"))
        assertFalse(CloudflaredService.isValidVersion("latest"))
        assertFalse(CloudflaredService.isValidVersion("2026.10.7/../../x"))
        assertFalse(CloudflaredService.isValidVersion("2026.10"))
    }

    @Test
    fun `the tunnel counts as connected once cloudflared registers a connection`() {
        assertFalse(CloudflaredService.logShowsConnected("2026-11-07T00:00:00Z INF Starting tunnel tunnelID=abc\n"))
        assertTrue(CloudflaredService.logShowsConnected("2026-11-07T00:00:01Z INF Registered tunnel connection connIndex=0 location=sea01\n"))
    }

    @Test
    fun `upgrade states classify outcomes`() {
        assertTrue(CloudflaredService.isUpgradeBusy("Upgrading to 2026.10.7..."))
        assertTrue(CloudflaredService.isUpgradeSuccess("Upgraded to 2026.10.7"))
        assertTrue(CloudflaredService.isUpgradeSuccess("Already on 2026.10.7"))
        assertFalse(CloudflaredService.isUpgradeSuccess("Rolled back to 2026.10.5: 2026.10.7 did not connect within 90s"))
        assertFalse(CloudflaredService.isUpgradeSuccess("Upgrade failed: checksum mismatch"))
    }

    /**
     * Downloads, verifies and installs two real releases on this machine, going up and then back down. Needs network
     * access to GitHub, so it only runs with CLOUDFLARED_LIVE_TEST=1. With no database the tunnel reads as disabled,
     * so no tunnel is started or stopped.
     */
    @Test
    fun `live install up and back down a version`() {
        if (System.getenv("CLOUDFLARED_LIVE_TEST") != "1") return
        val recommended = CloudflaredService.getRecommendedVersion() ?: error("GitHub unreachable")
        val older = CloudflaredService.parseReleases(
            java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.github.com/repos/cloudflare/cloudflared/releases?per_page=40"))
                    .header("User-Agent", "ObsidianScout-CloudflaredInstaller").build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            ).body()
        ).map { it.version }.filter { CloudflaredService.compareVersions(it, recommended) < 0 }
            .maxWithOrNull { a, b -> CloudflaredService.compareVersions(a, b) } ?: error("no older release")

        assertEquals("Upgraded to $older", CloudflaredService.installVersion(older).also { println("install $older: $it") })
        assertEquals(older, CloudflaredService.getInstalledVersion())
        assertEquals("Upgraded to $recommended", CloudflaredService.installVersion(recommended).also { println("install $recommended: $it") })
        assertEquals(recommended, CloudflaredService.getInstalledVersion())
        assertEquals("Already on $recommended", CloudflaredService.installVersion(recommended))
        assertEquals("Upgraded to $older", CloudflaredService.installVersion(older).also { println("roll back to $older: $it") })
        assertEquals(older, CloudflaredService.getInstalledVersion())
    }
}
