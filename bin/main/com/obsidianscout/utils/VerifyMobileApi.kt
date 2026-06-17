package com.obsidianscout.utils

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.routes.JwtHelper
import com.obsidianscout.routes.parseMatchScores
import java.util.Date

/**
 * Mobile API Verification Utility
 *
 * Verifies key parts of the Mobile API implementation (JWT generation/verification, score parsing).
 *
 * Run it using:
 *   .\gradlew.bat run -PmainClass=com.obsidianscout.utils.VerifyMobileApiKt
 *   or run the test task.
 */
fun main() {
    println("========================================================================")
    println("  ObsidianScout – Mobile API Verification Utility")
    println("========================================================================")
    println()


    // Test 1: JWT generation and verification
    println("Test 1: Verifying JWT Token functionality...")
    val secret = "test_super_secret_session_secret_key_1234567890"
    val session = UserSession(
        userId = 42,
        username = "scouter_test",
        teamNumber = 862,
        role = UserRole.SCOUT,
        email = "scouter@test.com"
    )
    val expiresAt = Date(System.currentTimeMillis() + 3600 * 1000) // 1 hour expiration

    val token = JwtHelper.generateToken(session, secret, expiresAt)
    println("  Generated Token: $token")

    val verifiedSession = JwtHelper.verifyToken(token, secret)
    if (verifiedSession != null) {
        println("  SUCCESS: JWT verified successfully!")
        println("    userId: ${verifiedSession.userId} (Expected: 42)")
        println("    username: ${verifiedSession.username} (Expected: scouter_test)")
        println("    teamNumber: ${verifiedSession.teamNumber} (Expected: 862)")
        println("    role: ${verifiedSession.role} (Expected: SCOUT)")
    } else {
        println("  FAILED: JWT verification failed!")
    }

    // Test 2: parseMatchScores functionality
    println("\nTest 2: Verifying parseMatchScores JSON parser...")
    val mockTbaDataJson = """
        {
            "alliances": {
                "red": { "score": 142 },
                "blue": { "score": 150 }
            },
            "winning_alliance": "blue"
        }
    """.trimIndent()

    val (redScore, blueScore, winner) = parseMatchScores(mockTbaDataJson)
    println("  Parsed Scores:")
    println("    Red Score: $redScore (Expected: 142)")
    println("    Blue Score: $blueScore (Expected: 150)")
    println("    Winner: $winner (Expected: blue)")

    if (redScore == 142 && blueScore == 150 && winner == "blue") {
        println("  SUCCESS: parseMatchScores parsed the scores and winner successfully!")
    } else {
        println("  FAILED: parseMatchScores output mismatch!")
    }

    // Test 3: Edit User function compilation check
    println("\nTest 3: Checking edit user (updateUser) compilation...")
    // Reference updateUser function to ensure the compiler resolves it correctly with default/new args
    val checkRef: Any = com.obsidianscout.auth.AuthService::updateUser
    println("  SUCCESS: AuthService.updateUser compiles and is resolvable! Ref: $checkRef")

    println("\n========================================================================")
}
