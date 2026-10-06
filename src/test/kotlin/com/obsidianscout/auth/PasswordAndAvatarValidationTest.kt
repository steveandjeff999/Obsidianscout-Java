package com.obsidianscout.auth

import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.ChatService
import com.obsidianscout.db.Users
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordAndAvatarValidationTest {

    private val testDbFile = File("build/test_password_avatar_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(Users, AppSettings) }
    }

    @AfterTest
    fun tearDown() {
        testDbFile.delete()
    }

    @Test
    fun passwordsLongerThanBcryptAllowsAreRejectedNotCrashed() {
        val tooLong = "a".repeat(AuthService.MAX_PASSWORD_BYTES + 1)
        val ex = assertFailsWith<ApiException> {
            AuthService.register("longpw", 7100, tooLong, "FRC", UserRole.SCOUT)
        }
        assertEquals(HttpStatusCode.BadRequest, ex.status)

        // Multi-byte characters count by bytes: 25 three-byte characters = 75 bytes.
        assertFailsWith<ApiException> {
            AuthService.register("longpw2", 7100, "€".repeat(25), "FRC", UserRole.SCOUT)
        }

        val maxLength = "b".repeat(AuthService.MAX_PASSWORD_BYTES)
        assertNotNull(AuthService.register("maxpw", 7100, maxLength, "FRC", UserRole.SCOUT))
        assertNotNull(AuthService.login("maxpw", 7100, maxLength, "FRC"))
    }

    @Test
    fun loginWithAnOverlongPasswordFailsNormally() {
        AuthService.register("someone", 7101, "Password123!", "FRC", UserRole.SCOUT)
        assertNull(AuthService.login("someone", 7101, "x".repeat(200), "FRC"))
    }

    @Test
    fun profilePicturesMustBeSmallImageDataUrls() {
        val jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(ByteArray(300) { 1 })
        AuthService.validateProfilePicture(jpeg)
        val decoded = AuthService.decodeProfilePicture(jpeg)
        assertEquals("image/jpeg", decoded?.first)
        assertEquals(300, decoded?.second?.size)

        for (bad in listOf(
            "https://example.com/a.png",
            "data:text/html;base64,PGgxPg==",
            "data:image/svg+xml;base64,PHN2Zz4=",
            "data:image/png;base64,not base64!",
            "data:image/png;base64," + "A".repeat(AuthService.MAX_PROFILE_PICTURE_CHARS)
        )) {
            assertFailsWith<ApiException>("should reject ${bad.take(40)}") { AuthService.validateProfilePicture(bad) }
        }
    }

    @Test
    fun chatReferencesAvatarsByUrlThatChangesWithThePicture() {
        val id = UUID.randomUUID()
        assertNull(ChatService.avatarUrl(id, null))
        assertNull(ChatService.avatarUrl(id, ""))
        val first = ChatService.avatarUrl(id, "data:image/jpeg;base64,AAAA")
        val second = ChatService.avatarUrl(id, "data:image/jpeg;base64,BBBB")
        assertTrue(first!!.startsWith("/api/users/$id/avatar?v="))
        assertTrue(first != second)
    }
}
