# syntax=docker/dockerfile:1.7

# ---- Stage 1: dependency cache -------------------------------------------------
# Copying the build scripts before the source means a source-only change reuses the
# resolved dependency layer instead of re-downloading the world on every commit.
FROM eclipse-temurin:21-jdk-alpine AS deps
WORKDIR /build
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies || true

# ---- Stage 2: build ------------------------------------------------------------
FROM deps AS build
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Stage 3: layered extraction ----------------------------------------------
# Spring's layered jar splits dependencies from application classes, so a code change
# only invalidates the last (small) layer at pull time.
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /app
COPY --from=build /build/build/libs/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Stage 4: runtime ----------------------------------------------------------
# distroless: no shell, no package manager, no busybox. Removes an entire class of
# post-exploitation tooling from the image.
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /app
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

USER nonroot:nonroot
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "application.jar"]
