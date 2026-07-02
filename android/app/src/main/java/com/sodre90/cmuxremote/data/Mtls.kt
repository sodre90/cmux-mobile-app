package com.sodre90.cmuxremote.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Everything needed to reach the bridge: its base URL and the per-device
 * bearer token minted by pairing.
 */
class BridgeConfig(
    val baseUrl: String,
    val deviceToken: String,
)

/**
 * Builds the single [OkHttpClient] used for every bridge call (HTTP + WS).
 * The relay presents a publicly-trusted server certificate (Let's
 * Encrypt, per the multi-tenant relay design), so no custom trust manager
 * is needed -- the platform default trust store applies.
 */
object Mtls {
    fun client(cfg: BridgeConfig): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(cfg.deviceToken))
            .build()
}

/** Adds `Authorization: Bearer <token>` to every request (when a token is set). */
internal class BearerInterceptor(private val token: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (token.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
