package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.BridgeJson
import com.sodre90.cmuxremote.model.TerminalDown
import com.sodre90.cmuxremote.model.TerminalUp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Bidirectional `WS /terminal/{surfaceId}`: [connect] streams server frames
 * (replay snapshot then output updates), [send] pushes input/paste/resize.
 */
class TerminalSocket(
    private val http: OkHttpClient,
    baseUrl: String,
    surfaceId: String,
) {
    private val url = "${baseUrl.trimEnd('/')}/terminal/$surfaceId"

    @Volatile
    private var socket: WebSocket? = null

    fun connect(): Flow<TerminalDown> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { BridgeJson.decodeFromString(TerminalDown.serializer(), text) }
                    .onFailure { android.util.Log.w("TerminalSocket", "dropped frame: ${it.message}") }
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
        socket = ws
        awaitClose {
            ws.cancel()
            if (socket === ws) socket = null
        }
    }

    /** Sends a client->server message; no-op if the socket is not open. */
    fun send(up: TerminalUp) {
        socket?.send(BridgeJson.encodeToString(TerminalUp.serializer(), up))
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
