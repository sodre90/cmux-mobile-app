package com.sodre90.cmuxremote.data

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MtlsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun clientAttachesBearerToken() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = Mtls.client(BridgeConfig(baseUrl = server.url("/").toString(), deviceToken = "tok-9"))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertEquals("Bearer tok-9", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun clientOmitsAuthorizationHeaderWhenTokenBlank() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = Mtls.client(BridgeConfig(baseUrl = server.url("/").toString(), deviceToken = ""))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
