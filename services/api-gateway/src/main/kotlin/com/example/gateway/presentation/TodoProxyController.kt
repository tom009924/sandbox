package com.example.gateway.presentation

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

@RestController
class TodoProxyController(
    private val todoServiceRestClient: RestClient,
) {
    @RequestMapping(
        path = [
            "/api/todos",
            "/api/todos/**",
            "/api/projects",
            "/api/projects/**",
            "/api/admin/stats",
        ],
    )
    fun forwardToTodoService(
        request: HttpServletRequest,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<ByteArray> {
        val method = HttpMethod.valueOf(request.method)
        val pathAndQuery = buildString {
            append(request.requestURI)
            request.queryString?.takeIf { it.isNotBlank() }?.let {
                append('?')
                append(it)
            }
        }

        val spec = todoServiceRestClient.method(method)
            .uri(pathAndQuery)
            .headers { headers -> copyForwardHeaders(request, headers) }

        val prepared = if (body != null && body.isNotEmpty()) {
            spec.body(body)
        } else {
            spec
        }

        return prepared.exchange { _, clientResponse ->
            val responseHeaders = HttpHeaders()
            clientResponse.headers.contentType?.let { responseHeaders.contentType = it }
            val responseBody = clientResponse.body.readAllBytes()
            ResponseEntity.status(clientResponse.statusCode)
                .headers(responseHeaders)
                .body(if (responseBody.isEmpty()) null else responseBody)
        }
    }

    private fun copyForwardHeaders(request: HttpServletRequest, targetHeaders: HttpHeaders) {
        FORWARDED_HEADERS.forEach { headerName ->
            request.getHeader(headerName)?.let { value ->
                targetHeaders.set(headerName, value)
            }
        }
        if (targetHeaders.contentType == null && request.contentType != null) {
            targetHeaders.contentType = MediaType.parseMediaType(request.contentType)
        }
    }

    private companion object {
        val FORWARDED_HEADERS = listOf(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            "Idempotency-Key",
            "X-Admin-Key",
        )
    }
}
