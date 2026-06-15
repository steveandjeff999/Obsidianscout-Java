package com.obsidianscout.utils

import at.favre.lib.crypto.bcrypt.BCrypt
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.Users
import com.obsidianscout.auth.UserRole
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Reset Superadmin Utility
 *
 * Resets the superadmin account's password in the database.
 *
 * Usage (from the bundle directory):
 *   Windows:  reset-superadmin.bat [newPassword]
 *   Unix:     ./reset-superadmin.sh [newPassword]
 *
 * Or directly with the JAR:
 *   java -cp obsidianscout-server.jar com.obsidianscout.utils.ResetSuperAdminKt [newPassword]
 *
 * If no password argument is supplied, the password is reset to "changeme".
 */
fun main(args: Array<String>) {
    val newPassword = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "changeme"

    println("========================================================================")
    println("  ObsidianScout – Reset Superadmin Utility")
    println("========================================================================")
    println()

    // Load config from the standard location (config/app-config.json relative to cwd)
    println("Loading configuration...")
    val appConfig = try {
        AppConfigLoader.load()
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to load config/app-config.json")
        System.err.println("       Make sure you are running this from the bundle directory.")
        System.err.println("       Details: ${e.message}")
        return
    }

    // Connect to the database
    println("Connecting to database (${appConfig.database.type})...")
    try {
        DatabaseFactory.init(appConfig.database)
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to connect to the database.")
        System.err.println("       Details: ${e.message}")
        return
    }

    // Find all SUPERADMIN accounts
    val superAdmins = transaction {
        Users.selectAll().where { Users.role eq UserRole.SUPERADMIN.name }
            .map { Triple(it[Users.id].value, it[Users.username], it[Users.teamNumber]) }
    }

    if (superAdmins.isEmpty()) {
        System.err.println("ERROR: No superadmin account found in the database.")
        System.err.println("       Start the server at least once to create the seed superadmin.")
        return
    }

    // Hash the new password
    println("Hashing new password...")
    val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())

    // Update all superadmin accounts
    val updatedCount = transaction {
        Users.update({ Users.role eq UserRole.SUPERADMIN.name }) {
            it[passwordHash] = newHash
        }
    }

    println()
    println("SUCCESS: Reset password for $updatedCount superadmin account(s):")
    superAdmins.forEach { (id, username, team) ->
        println("  - ID $id  |  username: $username  |  team: $team")
    }
    println()
    if (newPassword == "changeme") {
        println("Password has been reset to: changeme")
        println("Default username is: superadmin (unless you changed it)")
        println("Log in and change the password as soon as possible.")
    } else {
        println("Password has been reset to the value you provided.")
    }
    println("========================================================================")
}
