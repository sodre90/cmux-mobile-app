package com.sodre90.cmuxremote.data

import android.content.Context
import okhttp3.OkHttpClient

/**
 * Manual dependency container held by [com.sodre90.cmuxremote.CmuxApp]. Builds the
 * shared [OkHttpClient] (mTLS + bearer) from the current [Settings] and hands out
 * bridge clients/sockets. Returns null while the bridge is not yet configured.
 */
class AppContainer(appContext: Context) {

    val settings = Settings(appContext)

    private var clientKey: String? = null
    private var client: OkHttpClient? = null

    @Synchronized
    private fun httpClient(cfg: BridgeConfig): OkHttpClient {
        val key = listOf(
            cfg.baseUrl,
            cfg.deviceToken,
            cfg.serverCaPem.orEmpty(),
            (cfg.clientP12?.size ?: 0).toString(),
        ).joinToString("|")
        if (key != clientKey || client == null) {
            client = Mtls.client(cfg)
            clientKey = key
        }
        return client!!
    }

    fun bridgeClient(): BridgeClient? =
        settings.bridgeConfig()?.let { BridgeClient(httpClient(it), it.baseUrl) }

    fun eventsSocket(): EventsSocket? =
        settings.bridgeConfig()?.let { EventsSocket(httpClient(it), it.baseUrl) }

    fun terminalSocket(surfaceId: String): TerminalSocket? =
        settings.bridgeConfig()?.let { TerminalSocket(httpClient(it), it.baseUrl, surfaceId) }
}
