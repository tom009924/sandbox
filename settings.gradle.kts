rootProject.name = "sandbox"

include("libs:grpc-common")
include("libs:identity-proto")
include("libs:todo-proto")
include("services:api-gateway")
include("services:identity-service")
include("services:todo-service")
include("integration-tests")
