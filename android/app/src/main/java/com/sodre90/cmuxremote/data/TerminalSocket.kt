package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.PairedSession
import com.sodre90.cmuxremote.data.e2e.decodePayload
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
/**
 * The bridge's `wire.CloseSurfaceGone`: this surface does not exist and never
 * will again, so stop reconnecting to it. Mirrored from
 * `bridge/internal/wire/terminal.go` -- keep the two in step.
 *
 * 4404 is in RFC 6455's private application range. It arrives with an empty
 * reason string on purpose; the number carries the whole message.
 */
internal const val CLOSE_SURFACE_GONE = 4404

/**
 * The response header the bridge sets on the 101 to confirm it accepted the
 * `?deflate=1` request. Mirrors `deflateHeader` in
 * bridge/internal/server/terminal.go.
 *
 * Asking is not enough to start stripping codec tags: an older bridge ignores
 * the query and keeps sending untagged JSON, whose leading `{` would be read as
 * a tag and drop every frame. Compression is armed only once the bridge has
 * said it is compressing, which makes all four app/bridge version pairings
 * work.
 */
internal const val DEFLATE_HEADER = "X-Cmux-Deflate"

class TerminalSocket(
    private val http: OkHttpClient,
    baseUrl: String,
    surfaceId: String,
    private val session: PairedSession,
    private val cipher: Cipher,
) {
    private val url = "${baseUrl.trimEnd('/')}/terminal/$surfaceId?deflate=1"

    @Volatile
    private var socket: WebSocket? = null

    // Set from onOpen, which OkHttp guarantees runs before any onMessage, so
    // no frame is ever read before this is known.
    @Volatile
    private var deflated = false

    /** [onOpen] fires on the WebSocket upgrade, before any frame -- see
     *  [EventsSocket.connect] for why that can't come through the flow. */
    fun connect(onOpen: () -> Unit = {}): Flow<TerminalDown> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val ws = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    deflated = response.header(DEFLATE_HEADER) == "1"
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { decryptFrame(session, cipher, bytes.toByteArray()) }
                        .mapCatching { if (deflated) decodePayload(it) else it }
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
                    // Closing with a cause rather than gracefully is what makes
                    // the reconnect loop see this at all: a flow that simply
                    // completes is indistinguishable from a dropped connection,
                    // which is how the phone retried a dead surface every 5s
                    // behind a spinner (cmux-app-34c).
                    close(if (code == CLOSE_SURFACE_GONE) SubscriptionGoneException() else null)
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
