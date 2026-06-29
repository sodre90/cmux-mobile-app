package com.sodre90.cmuxremote.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Everything needed to reach the bridge: its base URL, the per-device bearer
 * token, the client certificate (PKCS#12) presented for mTLS, and optionally a
 * PEM CA to trust for the server (else the system trust store is used).
 */
class BridgeConfig(
    val baseUrl: String,
    val deviceToken: String,
    val clientP12: ByteArray? = null,
    val p12Password: String = "",
    val serverCaPem: String? = null,
)

/** Builds the single [OkHttpClient] used for every bridge call (HTTP + WS). */
object Mtls {

    fun client(cfg: BridgeConfig): OkHttpClient {
        val trust = trustManager(cfg.serverCaPem)
        val keyManagers = cfg.clientP12?.let { keyManagers(it, cfg.p12Password) }

        val ssl = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trust), SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .addInterceptor(BearerInterceptor(cfg.deviceToken))
            .build()
    }

    /** Loads the client cert/key from a PKCS#12 blob into JSSE key managers. */
    private fun keyManagers(p12: ByteArray, password: String): Array<KeyManager> {
        val pw = password.toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply {
            ByteArrayInputStream(p12).use { load(it, pw) }
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, pw) }
            .keyManagers
    }

    /**
     * Trust manager that trusts only [caPem] when supplied (the user's private
     * CA), otherwise the platform default trust store.
     */
    private fun trustManager(caPem: String?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (caPem.isNullOrBlank()) {
            tmf.init(null as KeyStore?)
        } else {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(caPem.toByteArray())) as X509Certificate
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("bridge-ca", cert)
            }
            tmf.init(ks)
        }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
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
