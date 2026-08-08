package dev.danilocordeiro.ledger.adapter.outbound.persistence

import dev.danilocordeiro.ledger.application.port.output.AccountRepository
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRaceLost
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRecord
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRepository
import dev.danilocordeiro.ledger.application.port.output.LedgerRepository
import dev.danilocordeiro.ledger.application.port.output.OutboxEvent
import dev.danilocordeiro.ledger.application.port.output.OutboxRepository
import dev.danilocordeiro.ledger.domain.Account
import dev.danilocordeiro.ledger.domain.AccountBalance
import dev.danilocordeiro.ledger.domain.AccountId
import dev.danilocordeiro.ledger.domain.CurrencyCode
import dev.danilocordeiro.ledger.domain.LedgerTransaction
import dev.danilocordeiro.ledger.domain.NormalSide
import dev.danilocordeiro.ledger.domain.TransactionId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class JdbcAccountRepository(private val jdbc: JdbcClient) : AccountRepository {

    override fun findAll(ids: Set<AccountId>): Map<AccountId, Account> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT id, currency, normal_side, allows_negative
              FROM account
             WHERE id = ANY (?)
            """.trimIndent(),
        )
            .param(ids.map { it.value }.toTypedArray())
            .query { rs, _ ->
                Account(
                    id = AccountId(rs.getObject("id", java.util.UUID::class.java)),
                    currency = CurrencyCode(rs.getString("currency").trim()),
                    normalSide = NormalSide.fromSign(rs.getInt("normal_side")),
                    allowsNegative = rs.getBoolean("allows_negative"),
                )
            }
            .list()
            .associateBy { it.id }
    }
}

@Repository
class JdbcLedgerRepository(private val jdbc: JdbcClient) : LedgerRepository {

    /**
     * ORDER BY account_id is load-bearing, not cosmetic. Concurrent transfers A->B and
     * B->A that lock in payload order deadlock; a global lock order makes that
     * impossible, turning a deadlock into a short wait.
     */
    override fun lockAndLoadBalances(ids: Set<AccountId>): Map<AccountId, AccountBalance> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT account_id, balance_minor, version
              FROM account_balance
             WHERE account_id = ANY (?)
             ORDER BY account_id
               FOR UPDATE
            """.trimIndent(),
        )
            .param(ids.map { it.value }.toTypedArray())
            .query { rs, _ ->
                AccountBalance(
                    accountId = AccountId(rs.getObject("account_id", java.util.UUID::class.java)),
                    signedMinorUnits = rs.getLong("balance_minor"),
                    version = rs.getLong("version"),
                )
            }
            .list()
            .associateBy { it.accountId }
    }

    override fun append(transaction: LedgerTransaction) {
        jdbc.sql(
            """
            INSERT INTO ledger_transaction (id, currency, external_reference, occurred_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        )
            // externalReference is nullable; JdbcClient's params(vararg) requires non-null
            // elements now that spring-jdbc is JSpecify @NullMarked, so each parameter is
            // bound individually via the overload that still accepts a @Nullable value.
            .param(1, transaction.id.value)
            .param(2, transaction.currency.code)
            .param(3, transaction.externalReference)
            .param(4, Timestamp.from(transaction.occurredAt))
            .update()

        transaction.entries.forEach { entry ->
            jdbc.sql(
                """
                INSERT INTO ledger_entry (transaction_id, account_id, direction, amount_minor_units)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            )
                .params(
                    transaction.id.value,
                    entry.accountId.value,
                    entry.direction.sign,
                    entry.amount.minorUnits,
                )
                .update()
        }
    }

    override fun applyBalanceDeltas(deltas: Map<AccountId, Long>) {
        deltas.entries
            .sortedBy { it.key.value } // same lock order as above
            .forEach { (accountId, delta) ->
                jdbc.sql(
                    """
                    UPDATE account_balance
                       SET balance_minor = balance_minor + ?,
                           version       = version + 1,
                           updated_at    = now()
                     WHERE account_id = ?
                    """.trimIndent(),
                )
                    .params(delta, accountId.value)
                    .update()
            }
    }
}

@Repository
class JdbcIdempotencyRepository(private val jdbc: JdbcClient) : IdempotencyRepository {

    override fun find(key: String): IdempotencyRecord? =
        jdbc.sql(
            """
            SELECT key, request_fingerprint, transaction_id, created_at
              FROM idempotency_record
             WHERE key = ?
            """.trimIndent(),
        )
            .param(key)
            .query { rs, _ ->
                IdempotencyRecord(
                    key = rs.getString("key"),
                    requestFingerprint = rs.getString("request_fingerprint"),
                    transactionId = TransactionId(rs.getObject("transaction_id", java.util.UUID::class.java)),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
            .optional()
            .orElse(null)

    override fun save(record: IdempotencyRecord) {
        try {
            jdbc.sql(
                """
                INSERT INTO idempotency_record (key, request_fingerprint, transaction_id, created_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            )
                .params(
                    record.key,
                    record.requestFingerprint,
                    record.transactionId.value,
                    Timestamp.from(record.createdAt),
                )
                .update()
        } catch (e: DuplicateKeyException) {
            // Expected under concurrency: translate the driver concern into a domain
            // signal so the application layer never imports a Spring exception type.
            throw IdempotencyRaceLost(record.key)
        }
    }
}

@Repository
class JdbcOutboxRepository(private val jdbc: JdbcClient) : OutboxRepository {

    override fun append(event: OutboxEvent) {
        jdbc.sql(
            """
            INSERT INTO outbox_event (aggregate_id, event_type, payload)
            VALUES (?, ?, ?::jsonb)
            """.trimIndent(),
        )
            .params(event.aggregateId, event.eventType, event.payloadJson)
            .update()
    }
}
