package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.BridgeJson
import com.sodre90.cmuxremote.model.FeedReply
import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.model.PendingFeedResponse
import com.sodre90.cmuxremote.model.RegisterDeviceRequest
import com.sodre90.cmuxremote.model.RenameWorkspaceRequest
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.model.WorkspacesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A non-2xx response from the bridge (e.g. Mac/cmux unavailable). */
class BridgeException(val code: Int, val bodyText: String) :
    IOException("bridge HTTP $code: $bodyText")

/**
 * REST calls against the bridge. The supplied [http] is expected to already
 * carry mTLS + the bearer token (see [Mtls.client]); this class only knows the
 * endpoint shapes. All calls run on [Dispatchers.IO].
 */
class BridgeClient(
    private val http: OkHttpClient,
    baseUrl: String,
) {
    private val root = baseUrl.trimEnd('/')

    suspend fun sessions(): List<Workspace> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$root/sessions").get().build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw BridgeException(resp.code, body)
            BridgeJson.decodeFromString(WorkspacesResponse.serializer(), body).workspaces
        }
    }

    suspend fun registerDevice(fcmToken: String) {
        val payload = BridgeJson.encodeToString(
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(fcmToken),
        )
        post("$root/devices/register", payload)
    }

    suspend fun pendingFeed(): List<PendingFeedItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$root/feed/pending").get().build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw BridgeException(resp.code, body)
            BridgeJson.decodeFromString(PendingFeedResponse.serializer(), body).items
        }
    }

    suspend fun replyFeed(feedId: String, reply: FeedReply) {
        val payload = BridgeJson.encodeToString(FeedReply.serializer(), reply)
        post("$root/feed/$feedId/reply", payload)
    }

    /** Sets a workspace's persistent display title in cmux (see
     *  bridge/internal/server/rename.go's workspace.rename RPC call). */
    suspend fun renameWorkspace(id: String, title: String) {
        val payload = BridgeJson.encodeToString(RenameWorkspaceRequest.serializer(), RenameWorkspaceRequest(title))
        post("$root/sessions/$id/rename", payload)
    }

    private suspend fun post(url: String, json: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw BridgeException(resp.code, resp.body?.string().orEmpty())
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
