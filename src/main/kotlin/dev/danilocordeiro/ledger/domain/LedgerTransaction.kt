package dev.danilocordeiro.ledger.domain

import java.time.Instant
import java.util.UUID

@JvmInline
value class TransactionId(val value: UUID) {
    companion object {
        fun new(): TransactionId = TransactionId(UUID.randomUUID())
    }
}

enum class Direction(val sign: Int) {
    DEBIT(1),
    CREDIT(-1);

    companion object {
        fun fromSign(sign: Int): Direction =
            entries.firstOrNull { it.sign == sign } ?: error("invalid direction sign: $sign")
    }
}

data class Entry(
    val accountId: AccountId,
    val direction: Direction,
    val amount: Money,
) {
    init {
        require(amount.isPositive) { "entry amount must be positive; use direction to express sign" }
    }

    val signedMinorUnits: Long get() = amount.minorUnits * direction.sign
}

/**
 * A balanced set of entries. The zero-sum invariant is enforced here *and* again by a
 * deferred constraint trigger in Postgres (V1__ledger.sql).
 *
 * Duplicating the check is deliberate. The domain check gives a fast, typed error with
 * a useful message; the database check is the one that still holds when someone writes
 * to the table from a migration script, a psql session, or a future service in another
 * language. An invariant that only lives in application code is a convention, not an
 * invariant.
 */
class LedgerTransaction private constructor(
    val id: TransactionId,
    val currency: CurrencyCode,
    val entries: List<Entry>,
    val occurredAt: Instant,
    val externalReference: String?,
) {
    val touchedAccountIds: Set<AccountId> = entries.map { it.accountId }.toSet()

    /** Net effect per account, in raw ledger sign (debit positive). */
    fun netByAccount(): Map<AccountId, Long> =
        entries.groupingBy { it.accountId }.fold(0L) { acc, e -> acc + e.signedMinorUnits }

    companion object {
        fun post(
            id: TransactionId,
            currency: CurrencyCode,
            entries: List<Entry>,
            occurredAt: Instant,
            externalReference: String? = null,
        ): LedgerTransaction {
            if (entries.size < 2) {
                throw DomainError.UnbalancedTransaction("a transaction needs at least two entries")
            }
            val imbalance = entries.sumOf { it.signedMinorUnits }
            if (imbalance != 0L) {
                throw DomainError.UnbalancedTransaction(
                    "debits and credits differ by $imbalance minor units",
                )
            }
            return LedgerTransaction(id, currency, entries, occurredAt, externalReference)
        }
    }
}
