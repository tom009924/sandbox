FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY build/libs/app.jar app.jar
USER app
EXPOSE 9010 9030
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
