FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/identity-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
