package com.obsidianscout.routes

import com.obsidianscout.auth.UserRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileGroupAccessTest {
    private val members = listOf("alex", "sam")

    @Test
    fun membersCanAccessAGroup() {
        assertTrue(canAccessMobileGroup(members, "alex", UserRole.SCOUT))
    }

    @Test
    fun otherScoutsCannot() {
        assertFalse(canAccessMobileGroup(members, "jordan", UserRole.SCOUT))
        assertFalse(canAccessMobileGroup(emptyList(), "jordan", UserRole.SCOUT))
    }

    @Test
    fun teamAdminsCan() {
        assertTrue(canAccessMobileGroup(members, "jordan", UserRole.ADMIN))
    }

    @Test
    fun aGroupWithNoMemberListIsOpenToTheTeam() {
        assertTrue(canAccessMobileGroup(null, "jordan", UserRole.SCOUT))
    }
}
