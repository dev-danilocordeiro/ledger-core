import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.danilocordeiro"
version = "0.1.0-SNAPSHOT"

// Spring Boot's BOM pins org.testcontainers:testcontainers to 1.20.4, whose bundled
// docker-java defaults to Docker API 1.32 and is rejected by newer daemons (minimum
// 1.40). Override the BOM property so every org.testcontainers:* module resolves to a
// version whose docker-java negotiates the daemon's API version correctly.
extra["testcontainers.version"] = "1.21.3"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    // --- Web / validation (inbound adapter only) ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // --- Persistence: plain JdbcClient, no JPA. See ADR-0002. ---
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Messaging ---
    implementation("org.springframework.kafka:spring-kafka")

    // --- Observability ---
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Domain invariants rely on non-null contracts being enforced at runtime.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.test {
    useJUnitPlatform()
    // Integration tests are tagged and run in a separate task.
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
