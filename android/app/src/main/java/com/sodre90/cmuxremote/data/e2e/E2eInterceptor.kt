package com.sodre90.cmuxremote.data.e2e

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Transparently encrypts outgoing HTTP request bodies and decrypts incoming
 * response bodies for every call through this client -- BridgeClient and its
 * callers never see ciphertext. Skips WebSocket upgrade requests entirely
 * (see this task's header note) since those have no JSON body to encrypt;
 * WS frame encryption is handled separately by Frame.kt.
 *
 * Slot-aware: [isRelaySlot] tells this instance whether it's protecting the
 * RELAY connection slot or the DIRECT one. Only RELAY has a relay in front of
 * the agent that terminates certain routes itself and expects them in
 * plaintext ([RELAY_TERMINATED_PATHS]); DIRECT talks straight to the agent
 * with no relay to terminate anything, so on DIRECT those same paths are
 * encrypted like normal. [UNENCRYPTED_PATHS] is the narrower exception that
 * holds on both slots.
 */
class E2eInterceptor(
    private val session: PairedSession,
    private val cipher: Cipher,
    private val isRelaySlot: Boolean,
) : Interceptor {

    companion object {
        // Paths terminated at the relay itself (not proxied to the agent), so
        // the relay decodes them as plaintext. Do NOT encrypt request bodies for
        // these when running on the RELAY slot. Add any future relay-terminated
        // endpoints here to avoid silent regressions. (/devices/pair uses a
        // separate un-intercepted client.) These paths do NOT apply on the
        // DIRECT slot: there is no relay in that path to terminate anything in
        // plaintext, so DIRECT encrypts them like any other route -- gating on
        // isRelaySlot below is what makes that distinction. UNENCRYPTED_PATHS
        // is the list that isn't gated that way.
        private val RELAY_TERMINATED_PATHS = setOf("/devices/register")

        // Plaintext on BOTH slots, unlike the list above. Forget retires this
        // device's token and clears its e2e session without waiting for the
        // network, so by the time this request is written there is usually no
        // shared secret left to seal it with -- encrypting it on DIRECT would
        // make Forget's revocation fail in the ordinary case. Both servers
        // mount it outside their encryption layer to match (see
        // bridge/internal/server/direct.go).
        private val UNENCRYPTED_PATHS = setOf("/devices/self-revoke")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header("Upgrade")?.equals("websocket", ignoreCase = true) == true) {
            return chain.proceed(original)
        }

        val path = original.url.encodedPath
        val sentInPlaintext = UNENCRYPTED_PATHS.any { path.endsWith(it) } ||
            (isRelaySlot && RELAY_TERMINATED_PATHS.any { path.endsWith(it) })

        val requestBody = original.body
        val request = if (requestBody != null && !sentInPlaintext) {
            val plaintext = Buffer().also { requestBody.writeTo(it) }.readByteArray()
            val encrypted = try {
                encryptBody(session, cipher, plaintext)
            } catch (e: NotPairedException) {
                throw IOException("not_paired", e)
            }
            original.newBuilder()
                .method(original.method, encrypted.toRequestBody(requestBody.contentType()))
                .build()
        } else {
            original
        }

        val response = chain.proceed(request)
        if (response.header("X-Cmux-Encrypted") != "1") {
            return response
        }
        val responseBody = response.body ?: return response
        val plaintext = try {
            decryptBody(session, cipher, responseBody.bytes())
        } catch (e: NotPairedException) {
            throw IOException("not_paired", e)
        } catch (e: DecryptFailedException) {
            // e.message, not a literal: a ReplayRejectedException names the
            // counter and the window it was refused against, and that detail
            // is the whole point of raising it separately.
            throw IOException(e.message, e)
        }
        return response.newBuilder()
            .body(plaintext.toResponseBody(responseBody.contentType()))
            .build()
    }
}
