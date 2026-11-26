package com.example.integration.support

import io.restassured.RestAssured.given
import io.restassured.http.ContentType

data class RecordedWireMockRequest(
    val method: String,
    val url: String,
)

/**
 * Lightweight client for verifying and resetting HTTP requests recorded by the WireMock container.
 */
object WireMockAdminClient {
    private const val HTTP_OK = 200

    fun resetRequests() {
        given()
            .baseUri(ContainerTestConfig.wireMockBaseUrl)
            .`when`()
            .delete("/__admin/requests")
            .then()
            .statusCode(HTTP_OK)
    }

    fun countPostRequests(urlPath: String): Int =
        given()
            .baseUri(ContainerTestConfig.wireMockBaseUrl)
            .contentType(ContentType.JSON)
            .body("""{"method":"POST","urlPath":"$urlPath"}""")
            .`when`()
            .post("/__admin/requests/count")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .path("count")

    fun fetchRequests(): List<RecordedWireMockRequest> {
        val rawRequests = given()
            .baseUri(ContainerTestConfig.wireMockBaseUrl)
            .`when`()
            .get("/__admin/requests")
            .then()
            .statusCode(HTTP_OK)
            .extract()
            .jsonPath()
            .getList<Map<String, Any>>("requests")

        return rawRequests.mapNotNull { entry ->
            val request = entry["request"] as? Map<*, *> ?: return@mapNotNull null
            val method = request["method"]?.toString() ?: return@mapNotNull null
            val url = request["url"]?.toString() ?: return@mapNotNull null
            RecordedWireMockRequest(method = method, url = url)
        }
    }
}
