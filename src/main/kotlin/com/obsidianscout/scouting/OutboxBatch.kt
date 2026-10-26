package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.routes.ScoutingEntryRequest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class OutboxBatchItem(
    /** The device's outbox id; used as the upload's clientId when the payload doesn't carry one. */
    val id: String,
    val kind: String,
    val payload: JsonObject
)

@Serializable
data class OutboxBatchRequest(val items: List<OutboxBatchItem> = emptyList())

@Serializable
data class OutboxBatchResult(val id: String, val status: Int, val error: String? = null)

@Serializable
data class OutboxBatchResponse(val results: List<OutboxBatchResult>)

/**
 * `POST /api/sync/outbox`: a device's saved submissions in one request instead of one round trip each, which is most
 * of the upload time on a slow link.
 *
 * Every item goes through the same service call as its single-entry route (`/api/scouting`, `/api/prescout/...`,
 * `/api/qual-scouting/batch`, `/api/assignments/{id}/status`), with the session's own program and team, so a batch
 * behaves exactly like the requests it replaces for FRC and FTC alike. Items run in order; after the first one that
 * fails with an error the device retries (5xx, 401, 403, 408, 429), the rest are answered 503 untouched, so the
 * device keeps them queued in order.
 */
object OutboxBatch {
    const val MAX_ITEMS = 50

    private val retryAfterFailure = OutboxBatchResult("", HttpStatusCode.ServiceUnavailable.value, "Not attempted: an earlier item failed")

    /** Statuses the device keeps the item for and retries later, so nothing after one is attempted. */
    private val retryableClientErrors = setOf(401, 403, 408, 429)

    /** The session's own form config for `match`, `pit` or `qual`, as the single-entry routes load it. */
    fun sessionConfigs(session: UserSession): (String) -> ScoutingConfig = { form ->
        when (form) {
            "pit" -> ConfigService.getPitConfig(session.teamNumber, session.program)
            "qual" -> ConfigService.getQualitativeConfig(session.teamNumber, session.program)
            else -> ConfigService.getConfig(session.teamNumber, session.program)
        }
    }

    fun process(
        session: UserSession,
        request: OutboxBatchRequest,
        loadConfig: (String) -> ScoutingConfig = sessionConfigs(session)
    ): OutboxBatchResponse {
        if (request.items.size > MAX_ITEMS) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "At most $MAX_ITEMS items per batch")
        }
        val configs = HashMap<String, ScoutingConfig>()
        fun config(form: String) = configs.getOrPut(form) { loadConfig(form) }

        var stopped = false
        val results = request.items.map { item ->
            if (stopped) return@map retryAfterFailure.copy(id = item.id)
            val result = try {
                apply(session, item, ::config)
                OutboxBatchResult(item.id, HttpStatusCode.OK.value)
            } catch (e: ApiException) {
                OutboxBatchResult(item.id, e.status.value, e.message)
            } catch (e: IllegalArgumentException) {
                // Malformed payload (bad JSON shape): the device can never upload it as-is.
                OutboxBatchResult(item.id, HttpStatusCode.BadRequest.value, e.message ?: "Invalid payload")
            } catch (e: Exception) {
                OutboxBatchResult(item.id, HttpStatusCode.InternalServerError.value, e.message ?: "Server error")
            }
            if (result.status >= 500 || result.status in retryableClientErrors) stopped = true
            result
        }
        return OutboxBatchResponse(results)
    }

    private fun entryRequest(item: OutboxBatchItem): ScoutingEntryRequest {
        val req = JsonSupport.json.decodeFromJsonElement(ScoutingEntryRequest.serializer(), item.payload)
        return if (req.clientId.isNullOrBlank()) req.copy(clientId = item.id) else req
    }

    /** Entries of a qualitative batch, in the same shapes `/api/qual-scouting/batch` accepts. */
    private fun qualEntries(payload: JsonObject): List<JsonObject> {
        val arr = payload["entries"] as? JsonArray ?: (payload["data"] as? JsonArray)
            ?: throw ApiException(HttpStatusCode.BadRequest, "Missing 'entries' array")
        return arr.map { val obj = it.jsonObject; obj["data"]?.jsonObject ?: obj }
    }

    private fun apply(session: UserSession, item: OutboxBatchItem, config: (String) -> ScoutingConfig) {
        when (item.kind) {
            "match" -> ScoutingService.createEntry(session, entryRequest(item), config("match"))
            "prescout_match" -> ScoutingService.createEntry(session, entryRequest(item), config("match"), isPrescout = true)
            "pit" -> PitScoutingService.createEntry(session, entryRequest(item), config("pit"))
            "prescout_pit" -> PitScoutingService.createEntry(session, entryRequest(item), config("pit"), isPrescout = true)
            "qual" -> {
                if (item.payload["entries"] is JsonArray) {
                    qualEntries(item.payload).forEach { QualitativeScoutingService.createEntry(session, ScoutingEntryRequest(it), config("qual")) }
                } else {
                    QualitativeScoutingService.createEntry(session, entryRequest(item), config("qual"))
                }
            }
            "qual_batch" -> qualEntries(item.payload).forEach {
                QualitativeScoutingService.createEntry(session, ScoutingEntryRequest(it), config("qual"))
            }
            "prescout_qual" -> QualitativeScoutingService.createEntry(session, entryRequest(item), config("qual"), isPrescout = true)
            "assignment_status" -> {
                val id = item.payload["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                val status = (item.payload["status"] as? JsonPrimitive)?.contentOrNull
                    ?: throw ApiException(HttpStatusCode.BadRequest, "Missing status")
                ScoutingAssignmentService.updateStatus(session, id, status)
            }
            else -> throw ApiException(HttpStatusCode.BadRequest, "Unknown outbox kind '${item.kind}'")
        }
    }
}
