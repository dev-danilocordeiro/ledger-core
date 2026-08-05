package dev.danilocordeiro.ledger.adapter.outbound.messaging

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Publishes outbox rows to Kafka.
 *
 * Trade-off vs. Debezium CDC: CDC removes this component entirely and has lower
 * latency, at the cost of running Kafka Connect plus a replication slot and making the
 * event schema a function of the table schema. Polling is ~40 lines, has no extra
 * infrastructure, and keeps the published contract decoupled from the physical model.
 * At this service's volume the added latency is irrelevant. Revisit if p99 publish
 * latency ever becomes a product requirement.
 *
 * FOR UPDATE SKIP LOCKED lets multiple replicas poll the same table without contending
 * or double-publishing: each instance claims a disjoint batch.
 */
@Component
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val kafka: KafkaTemplate<String, String>,
    meterRegistry: MeterRegistry,
    @Value("\${ledger.outbox.topic}") private val topic: String,
    @Value("\${ledger.outbox.batch-size}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val publishedCounter = meterRegistry.counter("ledger.outbox.published")
    private val failureCounter = meterRegistry.counter("ledger.outbox.publish.failed")

    @Scheduled(fixedDelayString = "\${ledger.outbox.poll-interval-ms}")
    @Transactional
    fun relay() {
        val batch = jdbc.sql(
            """
            SELECT id, aggregate_id, event_type, payload::text AS payload
              FROM outbox_event
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT ?
               FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        )
            .param(batchSize)
            .query { rs, _ ->
                PendingEvent(
                    id = rs.getLong("id"),
                    aggregateId = rs.getString("aggregate_id"),
                    eventType = rs.getString("event_type"),
                    payload = rs.getString("payload"),
                )
            }
            .list()

        if (batch.isEmpty()) return

        batch.forEach { event ->
            try {
                // Blocking get is intentional: the surrounding transaction must not
                // commit before the broker acknowledges, or a crash here would mark
                // rows published that were never delivered.
                kafka.send(topic, event.aggregateId, event.payload).get()
                markPublished(event.id)
                publishedCounter.increment()
            } catch (e: Exception) {
                failureCounter.increment()
                log.atError()
                    .addKeyValue("outbox_id", event.id)
                    .addKeyValue("event_type", event.eventType)
                    .setCause(e)
                    .log("failed to publish outbox event; batch will be retried")
                throw e // roll back the whole batch; the next poll retries from the head
            }
        }
    }

    private fun markPublished(id: Long) {
        jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = ?")
            .param(id)
            .update()
    }

    private data class PendingEvent(
        val id: Long,
        val aggregateId: String,
        val eventType: String,
        val payload: String,
    )
}
