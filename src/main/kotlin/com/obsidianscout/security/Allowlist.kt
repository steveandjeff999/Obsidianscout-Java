package com.obsidianscout.security

import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.IpAllowlistTable
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Networks (schools, venues) that are never blocked automatically. Entries come from app-config.json and the database. */
object Allowlist {
    private class Entry(val id: String, val range: IpRange, val note: String, val createdAt: Long, val createdBy: String, val expiresAt: Long?, val fromConfig: Boolean)

    @Volatile private var dbEntries: List<Entry> = emptyList()
    /** Entries added while the database isn't ready (and in tests). */
    private val memoryEntries = CopyOnWriteArrayList<Entry>()

    private fun configEntries(): List<Entry> = SecuritySettings.config.allowlist.mapNotNull { text ->
        IpRange.parse(text)?.let { Entry("config:${it.text}", it, "app-config.json", 0L, "config", null, true) }
    }

    private fun all(now: Long = System.currentTimeMillis()): List<Entry> =
        (configEntries() + dbEntries + memoryEntries).filter { it.expiresAt == null || it.expiresAt > now }

    fun isAllowed(ip: String): Boolean = all().any { it.range.contains(ip) }

    fun list(): List<AllowlistEntryDto> = all().map {
        AllowlistEntryDto(it.id, it.range.text, it.note, it.createdAt, it.createdBy, it.expiresAt, it.fromConfig)
    }

    fun refresh() {
        if (!DatabaseFactory.isReady) return
        val now = Instant.now()
        dbEntries = readTransaction {
            IpAllowlistTable.selectAll().mapNotNull { row ->
                val expires = row[IpAllowlistTable.expiresAt]
                if (expires != null && expires.isBefore(now)) return@mapNotNull null
                val range = IpRange.parse(row[IpAllowlistTable.ipOrCidr]) ?: return@mapNotNull null
                Entry(
                    row[IpAllowlistTable.id].value.toString(), range, row[IpAllowlistTable.note],
                    row[IpAllowlistTable.createdAt].toEpochMilli(), row[IpAllowlistTable.createdBy], expires?.toEpochMilli(), false
                )
            }
        }
    }

    /** Adds [target]; [expiresAt] null keeps it until removed. Returns null when [target] isn't a valid IP or range. */
    fun add(target: String, note: String, by: String, expiresAt: Long?): AllowlistEntryDto? {
        val range = IpRange.parse(target) ?: return null
        val now = System.currentTimeMillis()
        val cleanNote = SecurityEvents.clean(note, 256)
        val id = UUID.randomUUID()
        val entry = Entry(id.toString(), range, cleanNote, now, by, expiresAt, false)
        if (DatabaseFactory.isReady) {
            writeTransactionWithRetry {
                IpAllowlistTable.insert {
                    it[IpAllowlistTable.id] = id
                    it[ipOrCidr] = range.text
                    it[IpAllowlistTable.note] = cleanNote
                    it[createdAt] = Instant.ofEpochMilli(now)
                    it[createdBy] = by.take(128)
                    it[IpAllowlistTable.expiresAt] = expiresAt?.let { ms -> Instant.ofEpochMilli(ms) }
                }
            }
            runCatching { refresh() }
            SecuritySync.publish(SecuritySync.ALLOWLIST)
        } else {
            memoryEntries.add(entry)
        }
        return AllowlistEntryDto(entry.id, range.text, cleanNote, now, by, expiresAt, false)
    }

    fun remove(id: String): Boolean {
        val removedMemory = memoryEntries.removeIf { it.id == id }
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        var removedDb = false
        if (uuid != null && DatabaseFactory.isReady) {
            removedDb = writeTransactionWithRetry { IpAllowlistTable.deleteWhere { IpAllowlistTable.id eq uuid } } > 0
            runCatching { refresh() }
            SecuritySync.publish(SecuritySync.ALLOWLIST)
        }
        return removedMemory || removedDb
    }

    /** Tests only. */
    internal fun clearForTests() {
        memoryEntries.clear()
        dbEntries = emptyList()
    }
}
