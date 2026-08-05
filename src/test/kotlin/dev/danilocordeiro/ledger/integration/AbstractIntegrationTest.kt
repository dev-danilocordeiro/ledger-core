package dev.danilocordeiro.ledger.integration

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Real Postgres, real Kafka. Not H2 and not an embedded broker.
 *
 * The invariants this service depends on — deferred constraint triggers, FOR UPDATE
 * SKIP LOCKED, generated columns — do not exist in H2. A green suite against H2 would
 * be actively misleading here: it would pass while the production database rejects the
 * exact statements under test.
 *
 * Containers are static so they are started once per JVM and reused across classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
abstract class AbstractIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger")
                .also { it.start() }

        @JvmStatic
        val kafka: KafkaContainer =
            KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }
}
