FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
# Copy the full service source tree so Gradle can build the runnable jar inside the image
COPY . .
# Build the service artifact; tests are skipped here because the CI/local Gradle task already covers them
RUN ./gradlew build -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Container hygiene: non-root runtime user; port 8080 needs no privileges
RUN addgroup -S app && adduser -S app -G app
# Reuse the jar produced by the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
