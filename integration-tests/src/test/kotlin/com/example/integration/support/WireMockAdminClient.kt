package com.example.integration.support

import io.restassured.RestAssured.given
import io.restassured.http.ContentType

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
}
