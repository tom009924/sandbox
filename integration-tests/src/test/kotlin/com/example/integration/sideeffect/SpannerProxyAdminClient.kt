package com.example.integration.sideeffect

import com.example.integration.support.ContainerTestConfig
import io.restassured.RestAssured.given

enum class DbOperation {
    INSERT,
    UPDATE,
    UPSERT,
    DELETE,
}

data class RecordedDatabaseMutation(
    val operation: DbOperation,
    val table: String,
)

/**
 * REST admin client for the `spanner-proxy` container.
 */
object SpannerProxyAdminClient {
    private const val HTTP_OK = 200

    fun startTracking() {
        given()
            .baseUri(ContainerTestConfig.spannerProxyBaseUrl)
            .`when`()
            .post("/__admin/tracking/start")
            .then()
            .statusCode(HTTP_OK)
    }

    fun stopTracking() {
        given()
            .baseUri(ContainerTestConfig.spannerProxyBaseUrl)
            .`when`()
            .post("/__admin/tracking/stop")
            .then()
            .statusCode(HTTP_OK)
    }

    fun resetMutations() {
        given()
            .baseUri(ContainerTestConfig.spannerProxyBaseUrl)
            .`when`()
            .delete("/__admin/mutations")
            .then()
            .statusCode(HTTP_OK)
    }

    fun fetchMutations(): List<RecordedDatabaseMutation> {
        val raw = given()
            .baseUri(ContainerTestConfig.spannerProxyBaseUrl)
            .`when`()
            .get("/__admin/mutations")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .jsonPath()
            .getList<Map<String, String>>("$")

        return raw.map { entry ->
            RecordedDatabaseMutation(
                operation = DbOperation.valueOf(requireNotNull(entry["operation"])),
                table = requireNotNull(entry["table"]).lowercase(),
            )
        }
    }
}
