package com.example.todo.sideeffect

import com.example.todo.PubSubTestReceiver
import com.example.todo.ReceivedTodoEvent
import com.example.todo.domain.event.TodoEvent
import com.example.todo.domain.event.TodoEventType
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.RequestMethod
import io.restassured.RestAssured
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Suppress("MagicNumber")
class ExternalSideEffectVerifierTest {

    @BeforeEach
    fun resetState() {
        DatabaseSideEffectInspector.stopTracking()
        DatabaseSideEffectInspector.clear()
        wireMockServer.resetRequests()
    }

    @Test
    fun databaseInspectorIgnoresSelectsAndCapturesMutations() {
        DatabaseSideEffectInspector.startTracking()

        DatabaseSideEffectInspector.inspect("SELECT * FROM todos WHERE user_id = 'user-1'")
        DatabaseSideEffectInspector.inspect("select count(*) from users")
        assertTrue(DatabaseSideEffectInspector.getMutations().isEmpty())

        DatabaseSideEffectInspector.inspect(
            "/* insert for com.example.todo */ INSERT INTO todos (id, title) VALUES ('id1', 'Test')",
        )
        DatabaseSideEffectInspector.inspect("UPDATE users SET password_hash = 'new' WHERE id = 'user-1'")
        DatabaseSideEffectInspector.inspect("DELETE FROM idempotency_records WHERE created_at < NOW()")

        val mutations = DatabaseSideEffectInspector.getMutations()
        assertEquals(3, mutations.size)

        assertEquals(DbOperation.INSERT, mutations[0].operation)
        assertEquals("todos", mutations[0].table)

        assertEquals(DbOperation.UPDATE, mutations[1].operation)
        assertEquals("users", mutations[1].table)

        assertEquals(DbOperation.DELETE, mutations[2].operation)
        assertEquals("idempotency_records", mutations[2].table)
    }

    @Test
    fun databaseInspectorDoesNotCaptureWhenNotTracking() {
        DatabaseSideEffectInspector.stopTracking()
        DatabaseSideEffectInspector.inspect("INSERT INTO todos (id, title) VALUES ('id1', 'Test')")
        assertTrue(DatabaseSideEffectInspector.getMutations().isEmpty())
    }

    @Test
    fun verifierThrowsWhenUnverifiedDatabaseMutationRemains() {
        val mockReceiver = mock(PubSubTestReceiver::class.java)
        `when`(mockReceiver.isAvailable).thenReturn(false)

        val verifier = ExternalSideEffectVerifier(wireMockServer, mockReceiver)
        verifier.startTracking()

        DatabaseSideEffectInspector.inspect("INSERT INTO todos (id, title) VALUES ('id1', 'Test')")

        val exception = assertThrows(AssertionError::class.java) {
            verifier.assertNoUnverifiedSideEffects()
        }
        assertTrue(exception.message!!.contains("Expected no database mutations"))

        // 検証すると成功する
        verifier.verifyDatabase(DbOperation.INSERT, "todos")
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun verifierThrowsWhenUnverifiedMutatingHttpRequestRemains() {
        val mockReceiver = mock(PubSubTestReceiver::class.java)
        `when`(mockReceiver.isAvailable).thenReturn(false)

        val verifier = ExternalSideEffectVerifier(wireMockServer, mockReceiver)
        wireMockServer.resetRequests()
        verifier.startTracking()

        // WireMock に POST を送信
        RestAssured.given()
            .port(wireMockServer.port())
            .contentType("application/json")
            .body("""{"to":"test@example.com"}""")
            .post("/api/v1/emails")
            .then()
            .statusCode(202)

        val exception = assertThrows(AssertionError::class.java) {
            verifier.assertNoUnverifiedSideEffects()
        }
        assertTrue(exception.message!!.contains("Expected no external HTTP mutating requests"))

        // 検証すると成功する
        verifier.verifyHttpRequest(RequestMethod.POST, "/api/v1/emails")
        verifier.assertNoUnverifiedSideEffects()
    }

    @Test
    fun verifierThrowsWhenUnverifiedPubSubEventRemains() {
        val mockReceiver = mock(PubSubTestReceiver::class.java)
        `when`(mockReceiver.isAvailable).thenReturn(true)
        val dummyEvent = ReceivedTodoEvent(
            event = TodoEvent(
                eventType = TodoEventType.CREATED,
                timestamp = Instant.now(),
                todoId = UUID.randomUUID(),
                todo = null,
            ),
            attributes = mapOf("eventType" to "CREATED"),
        )
        `when`(mockReceiver.pullEvents(10, Duration.ofMillis(200))).thenReturn(listOf(dummyEvent))

        val verifier = ExternalSideEffectVerifier(wireMockServer, mockReceiver)
        wireMockServer.resetRequests()
        verifier.startTracking()

        val exception = assertThrows(AssertionError::class.java) {
            verifier.assertNoUnverifiedSideEffects()
        }
        assertTrue(exception.message!!.contains("Expected no Pub/Sub events"))
    }

    companion object {
        private lateinit var wireMockServer: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startWireMock() {
            wireMockServer = WireMockServer(wireMockConfig().dynamicPort())
            wireMockServer.start()
            wireMockServer.stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/emails"))
                    .willReturn(WireMock.aResponse().withStatus(202).withBody("""{"id":"test"}""")),
            )
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMockServer.stop()
        }
    }
}
