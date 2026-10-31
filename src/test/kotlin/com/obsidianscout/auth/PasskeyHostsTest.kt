package com.obsidianscout.auth

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.HttpsConfig
import com.obsidianscout.config.MdnsConfig
import com.obsidianscout.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class PasskeyHostsTest {
    @Test
    fun hostsComeFromTheServersOwnConfiguration() {
        val config = AppConfig(
            server = ServerConfig(
                https = HttpsConfig(additionalSans = listOf(" Scout.Example.org ", "")),
                mdns = MdnsConfig(enabled = true, hostname = "Pits")
            )
        )
        assertEquals(setOf("scout.example.org", "pits.local"), PasskeyService.configuredHosts(config))
    }

    @Test
    fun aDisabledMdnsNameIsNotTrusted() {
        val config = AppConfig(server = ServerConfig(mdns = MdnsConfig(enabled = false, hostname = "pits")))
        assertEquals(emptySet(), PasskeyService.configuredHosts(config))
    }
}
