package com.example.gateway.presentation

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class TodoProxyControllerTest {
    private lateinit var backendServer: HttpServer
    private lateinit var controller: TodoProxyController

    @BeforeEach
    fun setUp() {
        backendServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        backendServer.createContext("/api/todos") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val query = exchange.requestURI.rawQuery
            val responseBytes = if (auth == "Bearer test-jwt" && query == "status=OPEN") {
                """[{"title":"hello"}]""".toByteArray(StandardCharsets.UTF_8)
            } else {
                """{"error":"unexpected"}""".toByteArray(StandardCharsets.UTF_8)
            }
            val statusCode = if (auth == "Bearer test-jwt" && query == "status=OPEN") 200 else 400
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        backendServer.start()
        val baseUrl = "http://127.0.0.1:${backendServer.address.port}"
        controller = TodoProxyController(RestClient.builder().baseUrl(baseUrl).build())
    }

    @AfterEach
    fun tearDown() {
        backendServer.stop(0)
    }

    @Test
    fun `forwards todo requests with auth headers and query params`() {
        val request = MockHttpServletRequest("GET", "/api/todos").apply {
            queryString = "status=OPEN"
            addHeader("Authorization", "Bearer test-jwt")
        }

        val response = controller.forwardToTodoService(request, null)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("""[{"title":"hello"}]""", String(requireNotNull(response.body), StandardCharsets.UTF_8))
    }
}
