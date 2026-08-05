package dev.danilocordeiro.ledger.application.port.input

import dev.danilocordeiro.ledger.domain.AccountId
import dev.danilocordeiro.ledger.domain.CurrencyCode
import dev.danilocordeiro.ledger.domain.Direction
import dev.danilocordeiro.ledger.domain.Money
import dev.danilocordeiro.ledger.domain.TransactionId
import java.time.Instant

/**
 * Command objects live in the application layer, not the web adapter. The HTTP DTO is a
 * wire concern that changes with API versioning; this command is the stable contract a
 * CLI, a Kafka consumer or a test can also drive.
 */
data class PostTransactionCommand(
    val idempotencyKey: String,
    val currency: CurrencyCode,
    val entries: List<EntryCommand>,
    val externalReference: String?,
) {
    data class EntryCommand(
        val accountId: AccountId,
        val direction: Direction,
        val amount: Money,
    )
}

sealed interface PostTransactionResult {
    val transactionId: TransactionId

    /** The transaction was written by this call. */
    data class Posted(
        override val transactionId: TransactionId,
        val postedAt: Instant,
    ) : PostTransactionResult

    /**
     * An identical request had already been processed. Returned rather than thrown:
     * a replay is a success from the caller's perspective, and the distinction only
     * affects the HTTP status the adapter chooses.
     */
    data class Replayed(
        override val transactionId: TransactionId,
        val originallyPostedAt: Instant,
    ) : PostTransactionResult
}

fun interface PostTransactionUseCase {
    fun post(command: PostTransactionCommand): PostTransactionResult
}
