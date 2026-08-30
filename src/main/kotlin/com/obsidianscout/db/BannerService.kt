package com.obsidianscout.db

import com.obsidianscout.routes.BannerCreateRequest
import com.obsidianscout.routes.BannerDto
import com.obsidianscout.routes.BannerUpdateRequest
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

object BannerService {

    fun getAll(teamNumber: Int? = null): List<BannerDto> = try {
        readTransaction {
            val query = if (teamNumber != null) {
                Banners.selectAll().where { (Banners.teamNumber eq teamNumber) or (Banners.teamNumber eq 0) }
            } else {
                Banners.selectAll()
            }
            query.map { it.toDto() }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun getActive(teamNumber: Int): List<BannerDto> = try {
        readTransaction {
            Banners.selectAll().where { 
                (Banners.isActive eq true) and ((Banners.teamNumber eq teamNumber) or (Banners.teamNumber eq 0))
            }.map { it.toDto() }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun getLoginBanners(): List<BannerDto> = try {
        readTransaction {
            Banners.selectAll().where { 
                (Banners.isActive eq true) and (Banners.showOnLogin eq true)
            }.map { it.toDto() }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun getById(id: String): BannerDto? = try {
        readTransaction {
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@readTransaction null
            Banners.selectAll().where { Banners.id eq uuid }.firstOrNull()?.toDto()
        }
    } catch (_: Throwable) {
        null
    }

    fun create(dto: BannerCreateRequest): BannerDto = transaction {
        val id = Banners.insertAndGetId {
            it[teamNumber] = dto.teamNumber ?: 0
            it[message] = dto.message
            it[bannerType] = dto.bannerType ?: "info"
            it[isDismissible] = dto.isDismissible ?: true
            it[isExpandable] = dto.isExpandable ?: false
            it[expandableMessage] = dto.expandableMessage ?: ""
            it[showOnLogin] = dto.showOnLogin ?: false
            it[isActive] = dto.isActive ?: true
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }
        Banners.selectAll().where { Banners.id eq id.value }.first().toDto()
    }

    fun update(id: String, dto: BannerUpdateRequest): BannerDto? = transaction {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        val count = Banners.update({ Banners.id eq uuid }) {
            dto.teamNumber?.let { v -> it[teamNumber] = v }
            dto.message?.let { v -> it[message] = v }
            dto.bannerType?.let { v -> it[bannerType] = v }
            dto.isDismissible?.let { v -> it[isDismissible] = v }
            dto.isExpandable?.let { v -> it[isExpandable] = v }
            dto.expandableMessage?.let { v -> it[expandableMessage] = v }
            dto.showOnLogin?.let { v -> it[showOnLogin] = v }
            dto.isActive?.let { v -> it[isActive] = v }
            it[updatedAt] = Instant.now()
        }
        if (count > 0) {
            Banners.selectAll().where { Banners.id eq uuid }.firstOrNull()?.toDto()
        } else {
            null
        }
    }

    fun delete(id: String): Boolean = transaction {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction false
        Banners.deleteWhere { Banners.id eq uuid } > 0
    }

    private fun ResultRow.toDto(): BannerDto = BannerDto(
        id = this[Banners.id].value.toString(),
        teamNumber = this[Banners.teamNumber],
        message = this[Banners.message],
        bannerType = this[Banners.bannerType],
        isDismissible = this[Banners.isDismissible],
        isExpandable = this[Banners.isExpandable],
        expandableMessage = this[Banners.expandableMessage],
        showOnLogin = this[Banners.showOnLogin],
        isActive = this[Banners.isActive],
        createdAt = this[Banners.createdAt].toString(),
        updatedAt = this[Banners.updatedAt].toString()
    )
}
