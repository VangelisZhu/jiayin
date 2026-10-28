package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Every operation captures this account; no task resolves a different active account later. */
class OwnershipRepository(private val repo: JiayinRepository) {
    private val client = ManagerCorrections(repo)
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }
    fun personal() = repo.list("ownership-personal", PersonalOwnership::class.java)
    fun shared() = repo.list("ownership-shared", SharedOwnership::class.java)
    fun effective() = ArtistOwnership.resolve(personal(), shared())

    suspend fun save(ids: Set<String>, target: ArtistRef?) = withContext(Dispatchers.IO) {
        locks.getOrPut(repo.account) { Mutex() }.withLock {
            check(repo.currentSession()) { "账户已切换" }
            val known = repo.library().tracks.map { it.id }.toSet()
            require(ids.isNotEmpty() && ids.all { it in known }) { "歌曲信息已变化，请重新选择" }
            require(target == null || target.name.isNotBlank() && target.name.length <= 200 && target.name.none { it.code < 32 }) { "请填写有效歌手名称" }
            val stamp = UUID.randomUUID().toString()
            repo.transaction { ids.forEach { id ->
                if (target == null) repo.dao.delete(repo.account, "ownership-personal", id)
                else repo.put("ownership-personal", id, PersonalOwnership(id, target, stamp))
            } }
        }
    }

    suspend fun preview(ids: Set<String>, target: ArtistRef, requestId: String, note: String): JsonObject {
        val body = JsonObject().apply {
            addProperty("request_id", requestId); addProperty("note", note)
            add("ids", JsonArray().apply { ids.forEach { add(it) } })
            add("target", JsonObject().apply { addProperty("id", target.id); addProperty("name", target.name) })
        }
        return client.request("ownership/preview", body).asJsonObject
    }

    suspend fun submit(id: String, snapshots: List<PersonalOwnership>): JsonObject {
        // Persist exact personal stamps before issuing a request whose response may be lost.
        withContext(Dispatchers.IO) { repo.put("ownership-submitted", id, OwnershipSubmission(id, snapshots)) }
        return client.request("ownership/$id/submit", JsonObject()).asJsonObject
    }

    suspend fun requests(offset: Int = 0) = client.request("ownership/requests?offset=$offset").asJsonArray.map { it.asJsonObject }
    suspend fun detail(id: String) = client.request("ownership/requests/$id").asJsonObject
    suspend fun withdraw(id: String) = client.request("ownership/$id/withdraw", JsonObject())
    suspend fun capabilities(): JsonObject {
        val result = try { client.request("ownership/capabilities").asJsonObject }
        catch (e: IllegalStateException) {
            if (e.message?.contains("HTTP 404") == true || e.message == "Not Found")
                throw UnsupportedOperationException("配套后台尚未支持作品归属，请升级至 preview.14；个人整理仍可使用")
            throw e
        }
        if ((result.get("version")?.asInt ?: 0) < 1 || result.str("library_id").isBlank())
            throw UnsupportedOperationException("配套后台尚未支持作品归属，请升级至 preview.14；个人整理仍可使用")
        return result
    }

    suspend fun refresh() = locks.getOrPut(repo.account) { Mutex() }.withLock {
        val configured = !repo.vault.get("${repo.account}.manager-member").isNullOrBlank()
        if (!configured || repo.offline) return@withLock
        // A 404 is a compatible old backend, not a failed music-library update.
        val capability = try { capabilities() } catch (_: UnsupportedOperationException) { return@withLock }
        val identity = capability.str("library_id")
        check(identity.isNotBlank()) { "后台尚未支持作品归属，请升级配套后台" }
        val previous = withContext(Dispatchers.IO) { repo.get("ownership-meta", "identity", String::class.java) }
        val libraryVersion = withContext(Dispatchers.IO) { repo.library().syncedAt }
        val baseline = withContext(Dispatchers.IO) { repo.get("ownership-meta", "library-version", java.lang.Long::class.java)?.toLong() }
        val reset = identity != previous || baseline != libraryVersion
        var cursor = if (!reset) withContext(Dispatchers.IO) { repo.get("ownership-meta", "cursor", java.lang.Long::class.java)?.toLong() ?: 0L } else 0L
        val updates = mutableListOf<SharedOwnership>()
        do {
            val body = client.request("ownership/catalog?after=$cursor").asJsonObject
            check(body.str("library_id") == identity) { "后台曲库已更换，请重新刷新" }
            val rows = body.objects("tracks").map { row -> SharedOwnership(row.str("track_id"),
                row.get("target")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { ArtistRef(it.str("id"), it.str("name")) },
                row.get("sequence").asLong, row.str("request_id")) }
            val next = body.get("cursor").asLong
            val more = body.get("has_more").asBoolean
            check(next >= cursor && (!more || next > cursor)) { "后台归属分页异常" }
            updates.addAll(rows); cursor = next
        } while (more)
        withContext(Dispatchers.IO) {
            check(repo.currentSession()) { "账户已切换" }
            repo.transaction {
                if (reset) repo.dao.clear(repo.account, "ownership-shared")
                val submissions = repo.list("ownership-submitted", OwnershipSubmission::class.java).associateBy { it.id }
                updates.forEach { row ->
                    repo.put("ownership-shared", row.trackId, row)
                    val personal = repo.get("ownership-personal", row.trackId, PersonalOwnership::class.java)
                    val submitted = submissions[row.requestId]?.tracks?.find { it.trackId == row.trackId }
                    if (personal != null && submitted != null && ArtistOwnership.follows(personal, submitted, row, row.requestId))
                        repo.dao.delete(repo.account, "ownership-personal", row.trackId)
                }
                repo.put("ownership-meta", "identity", identity)
                repo.put("ownership-meta", "cursor", cursor)
                repo.put("ownership-meta", "library-version", libraryVersion)
            }
        }
    }
}
