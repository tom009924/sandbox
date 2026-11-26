package com.example.integration.support

/**
 * Runtime endpoints and credentials for container-backed cross-service integration tests.
 */
object ContainerTestConfig {
    val gatewayBaseUrl: String =
        System.getenv("GATEWAY_BASE_URL") ?: "http://127.0.0.1:8082"

    val wireMockBaseUrl: String =
        System.getenv("WIREMOCK_BASE_URL") ?: "http://127.0.0.1:8089"

    val spannerProxyBaseUrl: String =
        System.getenv("SPANNER_PROXY_BASE_URL") ?: "http://127.0.0.1:9030"

    val pubSubBaseUrl: String =
        System.getenv("PUBSUB_EMULATOR_BASE_URL") ?: "http://127.0.0.1:8085"

    val pubSubProjectId: String =
        System.getenv("PUBSUB_PROJECT_ID") ?: "todo-project"

    val pubSubTopicId: String =
        System.getenv("PUBSUB_TOPIC_ID") ?: "todo-events"

    val spannerRestBaseUrl: String =
        System.getenv("SPANNER_EMULATOR_REST_URL") ?: "http://127.0.0.1:9020"

    val spannerProjectId: String =
        System.getenv("SPANNER_PROJECT_ID") ?: "todo-project"

    val spannerInstanceId: String =
        System.getenv("SPANNER_INSTANCE_ID") ?: "todo-instance"

    val identityDatabase: String =
        System.getenv("IDENTITY_SPANNER_DATABASE") ?: "identity-database"

    val todoDatabase: String =
        System.getenv("TODO_SPANNER_DATABASE") ?: "todo-database"

    val adminApiKey: String =
        System.getenv("ADMIN_API_KEY") ?: "admin-secret-key"
}
