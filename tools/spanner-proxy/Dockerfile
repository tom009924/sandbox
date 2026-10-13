FROM gradle:8.10.2-jdk21 AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew :spanner-proxy:installDist --no-daemon

FROM eclipse-temurin:21-jre
COPY --from=build /workspace/spanner-proxy/build/install/spanner-proxy /opt/spanner-proxy
EXPOSE 8666 8474
ENTRYPOINT ["/opt/spanner-proxy/bin/spanner-proxy"]
