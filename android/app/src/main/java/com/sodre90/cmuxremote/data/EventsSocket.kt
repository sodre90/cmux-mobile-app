package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.decryptFrame
import com.sodre90.cmuxremote.model.BridgeJson
import com.sodre90.cmuxremote.model.EventFrame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Streams the bridge's `WS /events` as decoded [EventFrame]s. Every frame is
 *  XChaCha20-Poly1305-encrypted binary (see data/e2e/Frame.kt). */
class EventsSocket(
    private val http: OkHttpClient,
    baseUrl: String,
    private val session: PairedSession,
    private val cipher: Cipher,
) {
    private val url = "${baseUrl.trimEnd('/')}/events"

    /** Cold flow; opening the socket on collect and closing it on cancel. */
    fun connect(): Flow<EventFrame> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { decryptFrame(session, cipher, bytes.toByteArray()) }
                    .mapCatching { BridgeJson.decodeFromString(EventFrame.serializer(), it.toString(Charsets.UTF_8)) }
                    .getOrNull()
                    ?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })
        awaitClose { socket.cancel() }
    }
}
