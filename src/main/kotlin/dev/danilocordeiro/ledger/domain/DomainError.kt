package dev.danilocordeiro.ledger.domain

/**
 * Domain failures are exceptions, not a Result type.
 *
 * Trade-off: a sealed Result would make failure explicit in every signature, but here
 * every posting failure aborts the unit of work and maps 1:1 to an HTTP status.
 * Threading Either through four layers only to unwrap it at the edge buys type ceremony
 * and no behaviour. Outcomes callers are expected to *branch on* — see
 * PostTransactionResult.Replayed — are modelled as return values instead.
 */
sealed class DomainError(message: String) : RuntimeException(message) {

    class UnbalancedTransaction(detail: String) : DomainError("unbalanced transaction: $detail")

    class CurrencyMismatch(detail: String) : DomainError("currency mismatch: $detail")

    class AccountNotFound(val accountId: AccountId) :
        DomainError("account not found: ${accountId.value}")

    class InsufficientFunds(
        val accountId: AccountId,
        val availableMinorUnits: Long,
        val requestedMinorUnits: Long,
    ) : DomainError(
        "insufficient funds on ${accountId.value}: " +
            "available=$availableMinorUnits requested=$requestedMinorUnits",
    )

    class IdempotencyConflict(val key: String) :
        DomainError("idempotency key '$key' was already used with a different payload")
}
