package com.example.todo

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApigeeProxyIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private var apigeeAvailable = false
    private var backendReachableFromProxy = false

    @BeforeEach
    fun setupApigee() {
        apigeeAvailable = isApigeeEmulatorReachable()
        if (apigeeAvailable) {
            deployProxyWithBackendPort(port)
            backendReachableFromProxy = checkBackendReachability()
        }
    }

    @Test
    fun corsPreflightRequestReturnsExpectedHeaders() {
        assumeTrue(apigeeAvailable, "Apigee Emulator is not available, skipping test")

        given()
            .port(EMULATOR_TRAFFIC_PORT)
            .header("Origin", "http://example.com")
            .header("Access-Control-Request-Method", "POST")
            .`when`()
            .options("/v1/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Methods", containsString("POST"))
            .header("Access-Control-Allow-Headers", containsString("Authorization"))
    }

    @Test
    fun proxyForwardsAuthenticationAndTodosToSpringBootBackend() {
        assumeTrue(
            apigeeAvailable && backendReachableFromProxy,
            "Backend is not reachable from Apigee emulator; skipping backend-forwarding test",
        )

        val uniqueUsername = "apigee_user_${System.currentTimeMillis()}"

        // 1. Register through Apigee reverse proxy
        given()
            .port(EMULATOR_TRAFFIC_PORT)
            .contentType(ContentType.JSON)
            .body("""{"username":"$uniqueUsername","password":"password123"}""")
            .`when`()
            .post("/v1/auth/register")
            .then()
            .statusCode(HttpStatus.CREATED.value())
            .body("username", equalTo(uniqueUsername))
            .header("Access-Control-Allow-Origin", equalTo("*"))

        // 2. Login through Apigee reverse proxy
        val token = given()
            .port(EMULATOR_TRAFFIC_PORT)
            .contentType(ContentType.JSON)
            .body("""{"username":"$uniqueUsername","password":"password123"}""")
            .`when`()
            .post("/v1/auth/login")
            .then()
            .statusCode(HttpStatus.OK.value())
            .body("token", notNullValue())
            .extract()
            .path<String>("token")

        // 3. Fetch Todos with valid token through Apigee
        given()
            .port(EMULATOR_TRAFFIC_PORT)
            .header("Authorization", "Bearer $token")
            .`when`()
            .get("/v1/todos")
            .then()
            .statusCode(HttpStatus.OK.value())
            .header("Access-Control-Allow-Origin", equalTo("*"))

        // 4. Fetch Todos without token through Apigee -> Expect 401 Unauthorized from Spring Boot
        given()
            .port(EMULATOR_TRAFFIC_PORT)
            .`when`()
            .get("/v1/todos")
            .then()
            .statusCode(HttpStatus.UNAUTHORIZED.value())
            .header("Access-Control-Allow-Origin", equalTo("*"))
    }

    @Test
    fun nonExistentTodoReturnsNotFoundFromBackend() {
        assumeTrue(
            apigeeAvailable && backendReachableFromProxy,
            "Backend is not reachable from Apigee emulator; skipping backend-forwarding test",
        )

        val uniqueUsername = "apigee_user_404_${System.currentTimeMillis()}"

        // Register & login to get token
        given()
            .port(EMULATOR_TRAFFIC_PORT)
            .contentType(ContentType.JSON)
            .body("""{"username":"$uniqueUsername","password":"password123"}""")
            .post("/v1/auth/register")

        val token = given()
            .port(EMULATOR_TRAFFIC_PORT)
            .contentType(ContentType.JSON)
            .body("""{"username":"$uniqueUsername","password":"password123"}""")
            .post("/v1/auth/login")
            .then()
            .extract()
            .path<String>("token")

        // Query non-existent Todo ID -> 404 Not Found from Todo application
        val nonExistentId = "550e8400-e29b-41d4-a716-446655440000"
        given()
            .port(EMULATOR_TRAFFIC_PORT)
            .header("Authorization", "Bearer $token")
            .`when`()
            .get("/v1/todos/$nonExistentId")
            .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .header("Access-Control-Allow-Origin", equalTo("*"))
    }

    private fun isApigeeEmulatorReachable(): Boolean =
        runCatching {
            val url = URI("http://localhost:$EMULATOR_MANAGEMENT_PORT/").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            responseCode in HTTP_SUCCESS_RANGE_START..HTTP_CLIENT_ERROR_RANGE_END
        }.getOrDefault(false)

    private fun checkBackendReachability(): Boolean =
        runCatching {
            val response = given()
                .port(EMULATOR_TRAFFIC_PORT)
                .contentType(ContentType.JSON)
                .body("""{"username":"health_check","password":"password123"}""")
                .post("/v1/auth/register")
            response.statusCode != HttpStatus.SERVICE_UNAVAILABLE.value()
        }.getOrDefault(false)

    private fun deployProxyWithBackendPort(backendPort: Int) {
        val zipBytes = buildWorkspaceZipWithPort(backendPort)
        val url = URI("http://localhost:$EMULATOR_MANAGEMENT_PORT/v1/emulator/deploy?environment=test").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/zip")
        connection.outputStream.use { it.write(zipBytes) }
        val code = connection.responseCode
        check(code in HTTP_SUCCESS_RANGE_START..HTTP_SUCCESS_RANGE_END) {
            "Failed to deploy proxy to Apigee Emulator. HTTP status: $code"
        }
    }

    private fun buildWorkspaceZipWithPort(backendPort: Int): ByteArray {
        val rootDir = File("src/main/apigee")
        check(rootDir.exists()) { "Directory src/main/apigee does not exist" }

        val byteOut = ByteArrayOutputStream()
        ZipOutputStream(byteOut).use { zipOut ->
            rootDir.walkTopDown().filter { it.isFile }.forEach { file ->
                writeZipEntry(zipOut, rootDir, file, backendPort)
            }
        }
        return byteOut.toByteArray()
    }

    private fun writeZipEntry(zipOut: ZipOutputStream, rootDir: File, file: File, backendPort: Int) {
        val relativePath = "src/main/apigee/" + file.relativeTo(rootDir).path
        zipOut.putNextEntry(ZipEntry(relativePath))
        if (file.name == "default.xml" && file.parentFile?.name == "targets") {
            val content = file.readText().replace(
                "http://host.docker.internal:8080/api",
                "http://host.docker.internal:$backendPort/api",
            )
            zipOut.write(content.toByteArray(Charsets.UTF_8))
        } else {
            file.inputStream().use { it.copyTo(zipOut) }
        }
        zipOut.closeEntry()
    }

    companion object {
        private const val EMULATOR_MANAGEMENT_PORT = 8088
        private const val EMULATOR_TRAFFIC_PORT = 8998
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val HTTP_SUCCESS_RANGE_START = 200
        private const val HTTP_SUCCESS_RANGE_END = 299
        private const val HTTP_CLIENT_ERROR_RANGE_END = 499
    }
}
