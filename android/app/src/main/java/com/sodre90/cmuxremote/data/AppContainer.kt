package com.sodre90.cmuxremote.data

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.sodre90.cmuxremote.data.e2e.Cipher
import com.sodre90.cmuxremote.data.e2e.E2eInterceptor
import com.sodre90.cmuxremote.data.e2e.Identity
import com.sodre90.cmuxremote.data.e2e.Session
import com.sodre90.cmuxremote.data.pairing.PairingClient
import okhttp3.OkHttpClient

/**
 * Manual dependency container held by [com.sodre90.cmuxremote.CmuxApp]. Builds the
 * shared [OkHttpClient] (bearer token + opt-in e2e encryption) from the
 * current [Settings]/[Session] and hands out bridge clients/sockets.
 * Returns null while the bridge is not yet paired.
 */
class AppContainer(appContext: Context) {

    val settings = Settings(appContext)
    val identity = Identity(appContext)
    val session = Session(appContext)
    val cipher = Cipher(LazySodiumAndroid(SodiumAndroid()))

    private var clientKey: String? = null
    private var client: OkHttpClient? = null

    @Synchronized
    private fun httpClient(cfg: BridgeConfig): OkHttpClient {
        val key = "${cfg.baseUrl}|${cfg.deviceToken}|${session.isPaired()}"
        if (key != clientKey || client == null) {
            var built = Mtls.client(cfg)
            if (session.isPaired()) {
                built = built.newBuilder().addInterceptor(E2eInterceptor(session, cipher)).build()
            }
            client = built
            clientKey = key
        }
        return client!!
    }

    fun bridgeClient(): BridgeClient? =
        settings.bridgeConfig()?.let { BridgeClient(httpClient(it), it.baseUrl) }

    fun eventsSocket(): EventsSocket? =
        settings.bridgeConfig()?.let { EventsSocket(httpClient(it), it.baseUrl, session, cipher) }

    fun terminalSocket(surfaceId: String): TerminalSocket? =
        settings.bridgeConfig()?.let { TerminalSocket(httpClient(it), it.baseUrl, surfaceId, session, cipher) }

    /** Unauthenticated -- POST /devices/pair takes no bearer token (see
     *  bridge/internal/relay/relay.go's handleDevicePair). */
    fun pairingClient(): PairingClient = PairingClient(OkHttpClient(), identity, session, settings)
}
