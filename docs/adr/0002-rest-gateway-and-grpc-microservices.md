# ADR 0002: REST API Gateway (BFF) with gRPC Microservices

## Status

Accepted

## Context

Following the extraction of `identity-service` (#149), the repository had two different internal communication patterns:
- `api-gateway` calling `identity-service` over gRPC (`:9091`).
- `todo-api` calling `identity-service` over internal HTTP endpoints (`/internal/users/**` on `:8081`).

Maintaining both HTTP `/internal/**` controllers/filters and gRPC services in `identity-service` duplicated authentication, serialization, error mapping, and tests.

## Decision

1. **External edge**: Expose a single Spring Boot REST API Gateway (`:services:api-gateway`) that owns external REST contracts, centralized JWT / `X-Admin-Key` validation, and gRPC-to-HTTP error translation.
2. **Internal communication**: Standardize **all** service-to-service communication on **gRPC** using shared Protobuf contracts under `libs/*-proto` (`:libs:identity-proto`, `:libs:todo-proto`).
3. **Service-to-service auth**: Authenticate all gRPC RPCs via `x-internal-api-key` metadata verified in `InternalApiKeyGrpcInterceptor` (guarded on non-local profiles by `SecurityStartupValidator`).
4. **Removal of internal HTTP surface & downstream REST controllers**: Remove `InternalIdentityController`, `InternalApiKeyFilter`, and all `@RestController` endpoints from `identity-service` and `todo-service`, and remove the transitional HTTP `TodoProxyController` from `api-gateway` so all external REST requests enter exclusively through `api-gateway` (`:8082`) and are converted to gRPC (`:9091` / `:9092`).

## Consequences

- **Positive**: Single strongly-typed `.proto` contract shared by `api-gateway`, `todo-api`, and `identity-service`; eliminates duplicated internal HTTP controllers and WireMock boilerplate; faster in-process gRPC unit tests.
- **Negative**: Requires maintaining `gradle/verification-metadata.xml` checksums for `protoc` and `protoc-gen-grpc-java` across `linux-aarch_64` and `linux-x86_64`.
