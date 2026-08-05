package dev.danilocordeiro.ledger.application.port.output

import dev.danilocordeiro.ledger.domain.Account
import dev.danilocordeiro.ledger.domain.AccountBalance
import dev.danilocordeiro.ledger.domain.AccountId
import dev.danilocordeiro.ledger.domain.LedgerTransaction
import dev.danilocordeiro.ledger.domain.TransactionId
import java.time.Instant

interface AccountRepository {
    fun findAll(ids: Set<AccountId>): Map<AccountId, Account>
}

interface LedgerRepository {
    /**
     * Acquires row locks on the given accounts and returns their current balances.
     *
     * Implementations MUST lock in a deterministic order (account id ascending).
     * Two concurrent transfers A->B and B->A that lock in request order will deadlock;
     * ordering by id makes that impossible. This is the single most important
     * implementation constraint in this port, which is why it is stated here and not
     * only in the adapter.
     */
    fun lockAndLoadBalances(ids: Set<AccountId>): Map<AccountId, AccountBalance>

    fun append(transaction: LedgerTransaction)

    fun applyBalanceDeltas(deltas: Map<AccountId, Long>)
}

interface IdempotencyRepository {
    fun find(key: String): IdempotencyRecord?

    /**
     * Persists the key. Implementations must surface a unique-constraint violation as
     * [IdempotencyRaceLost] rather than a driver-specific exception: two concurrent
     * requests with the same key are expected traffic, not an error condition.
     */
    fun save(record: IdempotencyRecord)
}

data class IdempotencyRecord(
    val key: String,
    val requestFingerprint: String,
    val transactionId: TransactionId,
    val createdAt: Instant,
)

class IdempotencyRaceLost(val key: String) : RuntimeException("lost idempotency race for key '$key'")

interface OutboxRepository {
    fun append(event: OutboxEvent)
}

data class OutboxEvent(
    val aggregateId: String,
    val eventType: String,
    val payloadJson: String,
)

/**
 * Transaction boundary as a port so the application layer never imports
 * org.springframework.transaction. Keeping @Transactional out of the use case is what
 * lets these services be unit-tested with plain MockK and no Spring context.
 */
// Kotlin does not allow SAM conversion for a generic abstract method, so this stays a
// plain interface; callers use an object expression instead of a lambda.
interface UnitOfWork {
    fun <T> inTransaction(block: () -> T): T
}

fun interface ClockPort {
    fun now(): Instant
}
