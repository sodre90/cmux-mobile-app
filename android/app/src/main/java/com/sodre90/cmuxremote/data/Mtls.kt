package com.sodre90.cmuxremote.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Everything needed to reach the bridge: its base URL, the per-device
 * bearer token minted by pairing, and (still declared here for
 * [AppContainer]'s pre-Task-16 cache-key logic to keep compiling in the
 * interim -- see Task 18, which removes these two fields once nothing
 * reads them anymore) the now-unused client-cert/CA fields from the old
 * manual-setup flow.
 */
class BridgeConfig(
    val baseUrl: String,
    val deviceToken: String,
    val clientP12: ByteArray? = null,
    val p12Password: String = "",
    val serverCaPem: String? = null,
)

/**
 * Builds the single [OkHttpClient] used for every bridge call (HTTP + WS).
 * The relay presents a publicly-trusted server certificate (Let's
 * Encrypt, per the multi-tenant relay design), so no custom trust manager
 * is needed -- the platform default trust store applies. No client
 * certificate is presented either: self-service pairing replaced the old
 * mTLS-client-cert setup entirely, so [cfg]'s `clientP12`/`serverCaPem`
 * fields are intentionally unused here now.
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
