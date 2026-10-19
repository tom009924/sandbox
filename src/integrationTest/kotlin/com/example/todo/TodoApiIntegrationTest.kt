package com.example.todo

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules
import com.example.todo.domain.repository.TodoRepository
import com.example.todo.infrastructure.pubsub.PubSubProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import java.nio.file.Paths

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TodoApiIntegrationTest @Autowired constructor(
    private val todoRepository: TodoRepository,
    private val pubSubReceiver: PubSubTestReceiver,
) {
    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    fun cleanDatabase() {
        todoRepository.findAll().forEach(todoRepository::delete)
        if (pubSubReceiver.isAvailable) {
            pubSubReceiver.drain()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.example.todo.TodoApiTestCases#allTestCases")
    fun executeTodoApiTestCase(tc: TodoApiTestCase) {
        val context = TodoTestContext(
            todoRepository = todoRepository,
            pubSubReceiver = pubSubReceiver,
            requestExecutor = ::executeRequest,
        )
        tc.setup(context)

        val response = executeRequest(
            method = tc.method,
            path = tc.pathProvider(context),
            headers = tc.headersProvider(context),
            body = tc.requestBody,
            invalidRequest = tc.invalidRequest,
        ).statusCode(tc.expectedStatus)

        tc.verifyResponse(response, context)
        tc.verifySideEffects(context, response.extract())
    }

    @Test
    fun verifyAllOasOperationsHaveAcceptanceCriteriaInTests() {
        val oasExpectedResponses = coverageDefinition.operationResponses
        val testDefinedOperations = TodoApiTestCases.acceptanceCriteriaMap.keys
        val missing = oasExpectedResponses - testDefinedOperations
        assertTrue(
            missing.isEmpty(),
            "The following OAS operation responses do not have corresponding acceptance criteria in tests: " +
                missing.joinToString { "${it.first} -> ${it.second}" },
        )
    }

    private fun executeRequest(
        method: PathItem.HttpMethod,
        path: String,
        headers: Map<String, String>,
        body: String?,
        invalidRequest: Boolean,
    ): ValidatableResponse {
        val request = validatedRequest(invalidRequest)
        headers.forEach { (name, value) -> request.header(name, value) }
        if (body != null) {
            request.contentType(ContentType.JSON).body(body)
        }
        return request.`when`().request(method.name, path).then()
    }

    private fun validatedRequest(
        invalidRequest: Boolean = false,
    ) =
        given()
            .port(port)
            .filter(openApiCoverageFilter)
            .filter(if (invalidRequest) invalidRequestFilter else openApiValidationFilter)

    @TestConfiguration
    class PubSubTestConfiguration {
        @Bean(destroyMethod = "close")
        fun pubSubTestReceiver(
            pubSubProperties: PubSubProperties,
            objectMapper: ObjectMapper,
        ): PubSubTestReceiver =
            PubSubTestReceiver(
                projectId = pubSubProperties.projectId,
                topicId = pubSubProperties.topicId,
                emulatorHost = pubSubProperties.emulatorHost,
                objectMapper = objectMapper,
            )
    }

    companion object {
        const val OPEN_API_SPEC_PATH = "docs/openapi.yaml"
        val openApiSpecUrl: String = Paths.get(OPEN_API_SPEC_PATH).toAbsolutePath().toUri().toString()

        val openAPI = requireNotNull(OpenAPIV3Parser().read(OPEN_API_SPEC_PATH)) {
            "Failed to parse OpenAPI spec from $OPEN_API_SPEC_PATH"
        }

        val coverageDefinition = OpenApiCoverageDefinition.from(openAPI)
        val openApiCoverageFilter = OpenApiCoverageFilter(coverageDefinition)

        val strictValidator: OpenApiInteractionValidator =
            OpenApiInteractionValidator.createFor(openApiSpecUrl).build()

        val invalidRequestValidator: OpenApiInteractionValidator =
            OpenApiInteractionValidator.createFor(openApiSpecUrl)
                .withWhitelist(
                    ValidationErrorsWhitelist.create().withRule(
                        "Allow invalid title pattern and missing required fields for validation error requests",
                        WhitelistRules.allOf(
                            WhitelistRules.isRequest(),
                            WhitelistRules.anyOf(
                                WhitelistRules.methodIs(PathItem.HttpMethod.POST),
                                WhitelistRules.methodIs(PathItem.HttpMethod.PUT),
                            ),
                            WhitelistRules.pathContainsSubstring("/api/todos"),
                            WhitelistRules.anyOf(
                                WhitelistRules.messageHasKey("validation.request.body.schema.pattern"),
                                WhitelistRules.messageHasKey("validation.request.body.schema.required"),
                                WhitelistRules.messageHasKey("validation.request.body.schema.enum"),
                                WhitelistRules.messageHasKey("validation.request.body.schema.minimum"),
                            ),
                        ),
                    ),
                )
                .build()

        val openApiValidationFilter = OpenApiValidationFilter(strictValidator)
        val invalidRequestFilter = OpenApiValidationFilter(invalidRequestValidator)

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiResponsesCovered() {
            val missingResponses = coverageDefinition.responses - openApiCoverageFilter.coveredResponses
            assertTrue(
                missingResponses.isEmpty(),
                "The following OpenAPI responses were not exercised in integration tests: " +
                    missingResponses.joinToString { it.toString() },
            )
        }

        @JvmStatic
        @AfterAll
        fun verifyAllOpenApiConstraintsCovered() {
            val missingConstraints = coverageDefinition.constraints - openApiCoverageFilter.coveredConstraints
            assertTrue(
                missingConstraints.isEmpty(),
                "The following OpenAPI validation constraints were not exercised in integration tests: " +
                    missingConstraints.joinToString { it.toString() },
            )
        }
    }
}
