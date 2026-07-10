package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.decryptFrame
import com.sodre90.cmuxremote.data.e2e.encryptFrame
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
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Bidirectional `WS /terminal/{surfaceId}`: [connect] streams server frames
 * (replay snapshot then output updates), [send] pushes input/paste/resize.
 * Every frame is XChaCha20-Poly1305-encrypted binary (see data/e2e/Frame.kt) --
 * plaintext JSON-over-WS is no longer the wire format.
 */
class TerminalSocket(
    private val http: OkHttpClient,
    baseUrl: String,
    surfaceId: String,
    private val session: PairedSession,
    private val cipher: Cipher,
) {
    private val url = "${baseUrl.trimEnd('/')}/terminal/$surfaceId"

    @Volatile
    private var socket: WebSocket? = null

    fun connect(): Flow<TerminalDown> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { decryptFrame(session, cipher, bytes.toByteArray()) }
                        .mapCatching {
                            BridgeJson.decodeFromString(
                                TerminalDown.serializer(),
                                it.toString(Charsets.UTF_8)
                            )
                        }
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
            }
        )
        socket = ws
        awaitClose {
            ws.cancel()
            if (socket === ws) socket = null
        }
    }

    /**
     * Sends a client->server message. Returns false (a no-op) if the socket
     * isn't open -- the caller can use that to know the message definitely
     * never left the phone, as opposed to having been sent but not yet (or
     * never) acknowledged.
     */
    fun send(up: TerminalUp): Boolean {
        val plaintext = BridgeJson.encodeToString(TerminalUp.serializer(), up).toByteArray(Charsets.UTF_8)
        return socket?.send(encryptFrame(session, cipher, plaintext).toByteString()) ?: false
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
